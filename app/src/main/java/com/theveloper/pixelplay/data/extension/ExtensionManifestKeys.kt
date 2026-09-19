package com.theveloper.pixelplay.data.extension

/**
 * The manifest contract every PixelPlay extension file declares.
 *
 * Modeled directly on how Echo (the extension framework Captain-Vikram's PixelPlay fork builds
 * on) does this: an extension is just an .apk file sitting in [ExtensionFiles.directory] — never
 * installed via Android's package installer, no launcher icon, invisible to the OS as an
 * installed app. PixelPlay reads its manifest straight off the file with
 * `PackageManager.getPackageArchiveInfo()`, which works on APK files whether or not they're
 * installed, then loads its code directly from that file path with `DexClassLoader`.
 *
 * An extension declares itself with this in its `<application>` tag:
 *
 * ```xml
 * <application>
 *     <uses-feature android:name="com.theveloper.pixelplay.extension.music" android:required="false" />
 *
 *     <meta-data android:name="com.theveloper.pixelplay.extension.id" android:value="com.example.myext" />
 *     <meta-data android:name="com.theveloper.pixelplay.extension.class" android:value="com.example.myext.MyExtension" />
 *     <meta-data android:name="com.theveloper.pixelplay.extension.name" android:value="My Extension" />
 *     <meta-data android:name="com.theveloper.pixelplay.extension.version" android:value="1.0.0" />
 *     <meta-data android:name="com.theveloper.pixelplay.extension.author" android:value="Your Name" />
 *     <meta-data android:name="com.theveloper.pixelplay.extension.description" android:value="One sentence." />
 * </application>
 * ```
 *
 * The `class` named there must implement
 * [com.theveloper.pixelplay.extension.api.MusicExtension] and have a public no-argument
 * constructor. That module must be a `compileOnly` dependency on the extension's side — see
 * MusicExtension's own KDoc for why.
 */
object ExtensionManifestKeys {
    const val FEATURE = "com.theveloper.pixelplay.extension.music"

    const val META_ID = "com.theveloper.pixelplay.extension.id"
    const val META_CLASS = "com.theveloper.pixelplay.extension.class"
    const val META_NAME = "com.theveloper.pixelplay.extension.name"
    const val META_VERSION = "com.theveloper.pixelplay.extension.version"
    const val META_AUTHOR = "com.theveloper.pixelplay.extension.author"
    const val META_DESCRIPTION = "com.theveloper.pixelplay.extension.description"
}

/** Parsed straight from an extension file's manifest — before the file is ever loaded/run. */
data class ExtensionFileMetadata(
    val filePath: String,
    val id: String,
    val className: String,
    val displayName: String,
    val version: String,
    val author: String,
    val description: String
)
