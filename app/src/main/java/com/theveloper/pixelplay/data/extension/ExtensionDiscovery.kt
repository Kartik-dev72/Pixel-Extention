package com.theveloper.pixelplay.data.extension

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionDiscovery @Inject constructor() {

    /**
     * Scans [ExtensionFiles.directory] and parses manifest metadata out of every extension file
     * found there — using `getPackageArchiveInfo`, which reads an APK's manifest without
     * installing it. Files that fail to parse (corrupt, not actually an APK, missing required
     * meta-data) are logged and skipped, never thrown.
     */
    fun discover(context: Context): List<ExtensionFileMetadata> =
        ExtensionFiles.listExtensionFiles(context).mapNotNull { file ->
            parseOne(context, file)
        }

    private fun parseOne(context: Context, file: File): ExtensionFileMetadata? {
        val packageInfo = try {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
        } catch (e: Exception) {
            Timber.w(e, "ExtensionDiscovery: failed to read ${file.name} as an APK")
            null
        } ?: run {
            Timber.w("ExtensionDiscovery: ${file.name} isn't a valid APK — skipping")
            return null
        }

        val appInfo = packageInfo.applicationInfo
        val declaresFeature = packageInfo.reqFeatures
            ?.any { it.name == ExtensionManifestKeys.FEATURE }
            ?: false

        if (!declaresFeature) {
            Timber.w(
                "ExtensionDiscovery: ${file.name} doesn't declare the " +
                    "'${ExtensionManifestKeys.FEATURE}' uses-feature — skipping " +
                    "(is this actually a PixelPlay extension?)"
            )
            return null
        }

        val metaData = appInfo?.metaData
        fun required(key: String): String? {
            val value = metaData?.getString(key)?.takeIf { it.isNotBlank() }
            if (value == null) {
                Timber.w("ExtensionDiscovery: ${file.name} is missing required meta-data '$key' — skipping")
            }
            return value
        }

        val id = required(ExtensionManifestKeys.META_ID) ?: return null
        val className = required(ExtensionManifestKeys.META_CLASS) ?: return null
        val name = required(ExtensionManifestKeys.META_NAME) ?: return null
        val version = metaData?.getString(ExtensionManifestKeys.META_VERSION) ?: "unknown"
        val author = metaData?.getString(ExtensionManifestKeys.META_AUTHOR) ?: "Unknown"
        val description = metaData?.getString(ExtensionManifestKeys.META_DESCRIPTION) ?: ""

        return ExtensionFileMetadata(
            filePath = file.absolutePath,
            id = id,
            className = className,
            displayName = name,
            version = version,
            author = author,
            description = description
        )
    }

    companion object {
        @Suppress("DEPRECATION")
        private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
            PackageManager.GET_META_DATA or
            PackageManager.GET_SIGNATURES or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else 0
    }
}
