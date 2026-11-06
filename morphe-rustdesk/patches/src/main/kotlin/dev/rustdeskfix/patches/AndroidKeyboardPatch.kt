package dev.rustdeskfix.patches

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patcher.patch.ResourcePatchContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Properties

private const val APP_VERSION = "1.5.1"
private const val ABI = "arm64-v8a"
private const val LIBRARY_PATH = "lib/$ABI/"
private const val RESOURCE_PATH = "/rustdesk/$APP_VERSION/$ABI/"
private const val BACKUP_APP_LIBRARY = "libapp-backup.so"
private const val BACKUP_RUST_LIBRARY = "librustdesk-backup.so"
private val patchedLibraries = listOf("libapp.so", "librustdesk.so")
private val expectedLibraries = patchedLibraries + listOf("libflutter.so", "libc++_shared.so")

private object Payload {
    fun open(name: String): InputStream = javaClass.getResourceAsStream(RESOURCE_PATH + name)
        ?: throw PatchException("Missing RustDesk patch payload: $name")
}

@Suppress("unused")
val androidKeyboardPatch = rawResourcePatch(
    name = "Android keyboard and Japanese IME",
    description = "Adds the Android keyboard mode selector and sends committed Japanese IME text to the remote session. Requires the official ARM64 RustDesk $APP_VERSION APK.",
) {
    compatibleWith(
        Compatibility(
            packageName = "com.carriez.flutter_hbb",
            name = "RustDesk",
            apkFileType = ApkFileType.APK,
            targets = listOf(AppTarget(version = APP_VERSION)),
        ),
    )

    execute {
        val hashes = Properties().apply { Payload.open("sha256.properties").use(::load) }
        val originals = checkedLibraries(hashes, mapOf("libapp.so" to BACKUP_APP_LIBRARY, "librustdesk.so" to BACKUP_RUST_LIBRARY))
        replaceLibraries(hashes, originals, patchedLibraries.associateWith { it })
    }
}

internal val backupFlutterPatch = rawResourcePatch {
    execute {
        val hashes = Properties().apply { Payload.open("sha256.properties").use(::load) }
        val originals = checkedLibraries(hashes, patchedLibraries.associateWith { it })
        // The keyboard variant already contains the backup settings and favorites fix.
        if (originals.getValue("libapp.so").inputStream().use(::sha256) != hashes.getProperty("patched.libapp.so")) {
            replaceLibraries(hashes, originals, mapOf("libapp.so" to BACKUP_APP_LIBRARY, "librustdesk.so" to BACKUP_RUST_LIBRARY))
        }
    }
}

private fun ResourcePatchContext.checkedLibraries(
    hashes: Properties,
    alternateLibraries: Map<String, String>,
): Map<String, File> {
    val libraries = listApkEntries("lib/").filterNot { it.endsWith('/') }
    if (libraries.any { !it.startsWith(LIBRARY_PATH) }) {
        throw PatchException("Use the official ARM64 RustDesk $APP_VERSION APK (aarch64-signed.apk).")
    }
    return expectedLibraries.associateWith { name ->
        val path = LIBRARY_PATH + name
        if (path !in libraries) throw PatchException("Missing $path in the RustDesk APK.")
        get(path).also { library ->
            val digest = library.inputStream().use(::sha256)
            val alternate = alternateLibraries[name]?.let { hashes.getProperty("patched.$it") }
            if (digest != hashes.getProperty("original.$name") && digest != alternate) {
                throw PatchException("Unexpected $path. Use the original official ARM64 RustDesk $APP_VERSION APK.")
            }
        }
    }
}

private fun replaceLibraries(hashes: Properties, originals: Map<String, File>, replacements: Map<String, String>) {
    val staging = Files.createTempDirectory("rustdesk-payload-").toFile()
    try {
        for ((name, payloadName) in replacements) {
            val replacement = staging.resolve(name)
            Payload.open(payloadName).use { input -> replacement.outputStream().use { input.copyTo(it) } }
            if (replacement.inputStream().use(::sha256) != hashes.getProperty("patched.$payloadName")) {
                throw PatchException("RustDesk patch payload checksum mismatch: $payloadName")
            }
            originals.getValue(name).copyTo(staging.resolve("$name.original"))
        }
        installLibraries(staging, originals, replacements.keys)
    } finally {
        staging.deleteRecursively()
    }
}

private fun installLibraries(staging: File, originals: Map<String, File>, names: Set<String>) {
    val attempted = mutableListOf<String>()
    try {
        for (name in names) {
            attempted += name
            Files.copy(staging.resolve(name).toPath(), originals.getValue(name).toPath(), REPLACE_EXISTING)
        }
    } catch (failure: Exception) {
        for (name in attempted) {
            try {
                Files.copy(staging.resolve("$name.original").toPath(), originals.getValue(name).toPath(), REPLACE_EXISTING)
            } catch (restoreFailure: Exception) {
                failure.addSuppressed(restoreFailure)
            }
        }
        throw PatchException("Unable to replace the RustDesk native libraries.", failure)
    }
}

private fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
