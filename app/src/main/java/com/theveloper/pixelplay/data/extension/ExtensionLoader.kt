package com.theveloper.pixelplay.data.extension

import android.content.Context
import dalvik.system.DexClassLoader
import com.theveloper.pixelplay.extension.api.MusicExtension
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed class ExtensionLoadResult {
    data class Success(val instance: MusicExtension) : ExtensionLoadResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : ExtensionLoadResult()
}

@Singleton
class ExtensionLoader @Inject constructor() {

    /**
     * Loads [discovered]'s APK into its own DexClassLoader and instantiates its entry class.
     *
     * The critical detail: this DexClassLoader's parent is `context.classLoader` (the host app's
     * own classloader), not the default system classloader. That parent-delegation is what lets
     * the loaded instance be cast to [MusicExtension] here without a ClassCastException — the
     * extension's ENTRY_CLASS references [MusicExtension], class-loading delegates to the parent
     * first, finds the host's own already-loaded copy, and both sides agree on one single
     * `Class<MusicExtension>` object. This only works if the extension's `build.gradle.kts`
     * depends on `:extension-api` with `compileOnly` (see the KDoc on [MusicExtension]) — if the
     * extension bundled its own copy of the interface into its dex instead, this cast would fail
     * even though everything else works.
     */
    fun load(context: Context, discovered: DiscoveredExtension): ExtensionLoadResult {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(discovered.packageName, 0)
            val apkPath = appInfo.sourceDir

            val optimizedDir = context.codeCacheDir.also { it.mkdirs() }

            val classLoader = DexClassLoader(
                apkPath,
                optimizedDir.absolutePath,
                appInfo.nativeLibraryDir,
                context.classLoader // parent — see KDoc above, do not change to null/system
            )

            val loadedClass = Class.forName(discovered.entryClassName, true, classLoader)
            val instance = loadedClass.getDeclaredConstructor().newInstance()

            val extension = instance as? MusicExtension
                ?: return ExtensionLoadResult.Failure(
                    "${discovered.entryClassName} does not implement MusicExtension " +
                        "(or was built with 'implementation' instead of 'compileOnly' on " +
                        ":extension-api — see MusicExtension's KDoc)"
                )

            ExtensionLoadResult.Success(extension)
        } catch (e: ClassNotFoundException) {
            ExtensionLoadResult.Failure(
                "Entry class '${discovered.entryClassName}' not found in ${discovered.packageName}'s APK",
                e
            )
        } catch (e: NoSuchMethodException) {
            ExtensionLoadResult.Failure(
                "${discovered.entryClassName} has no public no-argument constructor",
                e
            )
        } catch (e: Exception) {
            Timber.e(e, "ExtensionLoader: failed to load ${discovered.packageName}")
            ExtensionLoadResult.Failure(e.message ?: "Unknown error loading extension", e)
        }
    }
}
