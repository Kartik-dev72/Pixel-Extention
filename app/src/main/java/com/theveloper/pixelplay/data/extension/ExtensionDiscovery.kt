package com.theveloper.pixelplay.data.extension

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A discovered-but-not-yet-loaded extension APK. */
data class DiscoveredExtension(
    val packageName: String,
    val entryClassName: String,
    val declaredApiVersion: Int
)

@Singleton
class ExtensionDiscovery @Inject constructor() {

    /**
     * Scans installed packages for anything declaring the PixelPlay extension intent-filter.
     * Safe to call repeatedly (e.g. after ACTION_PACKAGE_ADDED) — cheap PackageManager query,
     * no APK content is touched here.
     */
    fun discover(context: Context): List<DiscoveredExtension> {
        val pm = context.packageManager
        val intent = Intent(ExtensionContract.ACTION_BIND)

        val resolveInfos: List<ResolveInfo> = try {
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Timber.e(e, "ExtensionDiscovery: queryIntentServices failed")
            emptyList()
        }

        return resolveInfos.mapNotNull { resolveInfo ->
            val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
            val metaData = serviceInfo.metaData

            val entryClass = metaData?.getString(ExtensionContract.META_ENTRY_CLASS)
            if (entryClass.isNullOrBlank()) {
                Timber.w(
                    "ExtensionDiscovery: ${serviceInfo.packageName} declares the extension " +
                        "intent-filter but is missing the ENTRY_CLASS meta-data — skipping"
                )
                return@mapNotNull null
            }

            val apiVersion = metaData.getInt(
                ExtensionContract.META_API_VERSION,
                /* default, for extensions built before this field existed */ 1
            )

            if (apiVersion > ExtensionContract.CURRENT_API_VERSION) {
                Timber.w(
                    "ExtensionDiscovery: ${serviceInfo.packageName} declares API version " +
                        "$apiVersion, newer than this app supports " +
                        "(${ExtensionContract.CURRENT_API_VERSION}) — skipping until PixelPlay is updated"
                )
                return@mapNotNull null
            }

            DiscoveredExtension(
                packageName = serviceInfo.packageName,
                entryClassName = entryClass,
                declaredApiVersion = apiVersion
            )
        }
    }
}
