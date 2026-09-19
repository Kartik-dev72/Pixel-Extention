package com.theveloper.pixelplay.data.extension

import android.content.Context
import android.os.Build
import com.theveloper.pixelplay.extension.api.MusicExtension
import dalvik.system.DexClassLoader
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

sealed class ExtensionLoadResult {
    data class Success(val instance: MusicExtension) : ExtensionLoadResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : ExtensionLoadResult()
}

@Singleton
class ExtensionLoader @Inject constructor() {

    /**
     * Loads [metadata]'s file directly — no PackageManager, no installed package, just
     * `DexClassLoader` pointed at a file path. The parent classloader is the host app's own
     * (`context.classLoader`), which is what makes the loaded instance castable to
     * [MusicExtension] here without a ClassCastException: class-loading delegates to the parent
     * first, finds the host's own already-loaded copy of that interface, and both sides agree on
     * one single `Class<MusicExtension>` object. This only works if the extension depends on
     * `:extension-api` with `compileOnly`, not `implementation` — see [MusicExtension]'s KDoc.
     */
    fun load(context: Context, metadata: ExtensionFileMetadata): ExtensionLoadResult {
        return try {
            val apkFile = File(metadata.filePath)
            if (!apkFile.exists()) {
                return ExtensionLoadResult.Failure("File no longer exists: ${metadata.filePath}")
            }

            // Android 14+ refuses to load dex from files the app can write to. This also
            // repairs extensions that were imported before the import path sealed them.
            if (!ExtensionFiles.sealReadOnly(apkFile)) {
                return ExtensionLoadResult.Failure(
                    "Couldn't make ${apkFile.name} read-only; Android 14+ refuses to load writable dex files"
                )
            }

            val optimizedDir = context.codeCacheDir.also { it.mkdirs() }
            val nativeLibDir = extractNativeLibsIfAny(context, apkFile, metadata.id)

            val classLoader = DexClassLoader(
                apkFile.absolutePath,
                optimizedDir.absolutePath,
                nativeLibDir,
                context.classLoader // parent — see KDoc above, do not change
            )

            val loadedClass = Class.forName(metadata.className, true, classLoader)
            val instance = loadedClass.getDeclaredConstructor().newInstance()

            val extension = instance as? MusicExtension
                ?: return ExtensionLoadResult.Failure(
                    "${metadata.className} does not implement MusicExtension (or the extension " +
                        "was built with 'implementation' instead of 'compileOnly' on " +
                        ":extension-api — see MusicExtension's KDoc)"
                )

            ExtensionLoadResult.Success(extension)
        } catch (e: ClassNotFoundException) {
            ExtensionLoadResult.Failure("Entry class '${metadata.className}' not found in ${metadata.filePath}", e)
        } catch (e: NoSuchMethodException) {
            ExtensionLoadResult.Failure("${metadata.className} has no public no-argument constructor", e)
        } catch (e: Exception) {
            Timber.e(e, "ExtensionLoader: failed to load ${metadata.id}")
            ExtensionLoadResult.Failure(e.message ?: "Unknown error loading extension", e)
        }
    }

    /**
     * Extensions that bundle native (.so) libraries need those extracted to a real directory —
     * DexClassLoader can't read native libs straight out of the APK's zip the way it can dex/class
     * files. Ported from Echo's own `DexLoader.extractNativeLibs`. Returns null (fine — most
     * extensions have no native code) if the APK has no lib/ entries for a supported ABI.
     *
     * Extraction failures are rethrown rather than swallowed: [load]'s catch-all logs them with
     * the extension id and converts them into an [ExtensionLoadResult.Failure].
     */
    private fun extractNativeLibsIfAny(context: Context, apkFile: File, extensionId: String): String? {
        val libDir = File(context.codeCacheDir, "extension_libs/$extensionId")

        return try {
            ZipFile(apkFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val bestAbi = Build.SUPPORTED_ABIS.firstOrNull { abi ->
                    entries.any { it.name.startsWith("lib/$abi/") }
                } ?: return null

                libDir.mkdirs()
                entries.filter { it.name.startsWith("lib/$bestAbi/") && it.name.endsWith(".so") }
                    .forEach { entry ->
                        val outFile = File(libDir, entry.name.substringAfterLast("/"))
                        if (!outFile.exists() || outFile.length() != entry.size) {
                            zip.getInputStream(entry).use { input ->
                                outFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    }
                libDir.absolutePath
            }
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to extract native libs for $extensionId: ${e.message ?: e.javaClass.simpleName}",
                e
            )
        }
    }
}
