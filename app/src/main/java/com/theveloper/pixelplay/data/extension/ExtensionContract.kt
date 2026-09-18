package com.theveloper.pixelplay.data.extension

/**
 * The discovery contract between PixelPlay and any extension APK.
 *
 * An extension declares an exported, non-functional marker `<service>` in its manifest:
 *
 * ```xml
 * <service
 *     android:name=".YourMarkerService"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="com.theveloper.pixelplay.extension.BIND" />
 *     </intent-filter>
 *     <meta-data
 *         android:name="com.theveloper.pixelplay.extension.ENTRY_CLASS"
 *         android:value="com.example.yourextension.YourMusicExtension" />
 * </service>
 * ```
 *
 * PixelPlay never actually binds to that service — `queryIntentServices` is used purely to find
 * installed extension APKs via PackageManager, then [ExtensionLoader] instantiates the class
 * named by the ENTRY_CLASS meta-data directly via reflection. The `<service>` only needs to
 * exist so the manifest has something to attach the intent-filter and meta-data to; `YourMarkerService`
 * itself can be a completely empty `Service` subclass that's never started.
 */
object ExtensionContract {
    const val ACTION_BIND = "com.theveloper.pixelplay.extension.BIND"
    const val META_ENTRY_CLASS = "com.theveloper.pixelplay.extension.ENTRY_CLASS"

    /**
     * Bump this if [com.theveloper.pixelplay.extension.api.MusicExtension] ever changes in a
     * breaking way. Extensions can declare the max version they were built against via this same
     * meta-data key so the host can warn instead of crashing on an incompatible extension.
     */
    const val META_API_VERSION = "com.theveloper.pixelplay.extension.API_VERSION"
    const val CURRENT_API_VERSION = 1
}
