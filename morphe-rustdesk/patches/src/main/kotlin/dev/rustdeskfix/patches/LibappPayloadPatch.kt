package dev.rustdeskfix.patches

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.rawResourcePatch
import java.io.InputStream
import java.security.MessageDigest
import java.util.Properties

private const val APP_VERSION = "1.5.3"
private const val ABI = "arm64-v8a"
private const val LIBRARY_PATH = "lib/$ABI/"
private const val RESOURCE_PATH = "/rustdesk/$APP_VERSION/$ABI/"
private const val LIBAPP = "libapp.so"

/** Official libapp.so digests, so a mismatch can name the version it came from. */
private val KNOWN_LIBAPP = mapOf(
    "fc50ade6197915566ec1191ad4102a56400dfcfbed4fcf753ff0f2c7ba7d6b9a" to "1.5.1",
    "0eeabd4dcb190425534248ecc5dede159505e9d4237c64284b2054db48a4f8f1" to "1.5.3",
)

private fun describe(digest: String): String =
    KNOWN_LIBAPP[digest]?.let { "RustDesk $it" } ?: "an unknown build (${digest.take(12)}…)"

private object LibappPayload {
    fun open(name: String): InputStream = javaClass.getResourceAsStream(RESOURCE_PATH + name)
        ?: throw PatchException("Missing RustDesk patch payload: $name")
}

/**
 * Replaces the Flutter library with a build of this fork, so the Settings page gains the backup
 * entry. Only libapp.so is replaced: libflutter.so and the Flutter assets are byte-identical to
 * the official APK, which is what makes the Dart snapshot compatible.
 */
internal val libappPayloadPatch = rawResourcePatch {
    execute {
        val hashes = Properties().apply { LibappPayload.open("sha256.properties").use(::load) }
        val path = LIBRARY_PATH + LIBAPP
        val entry = get(path)
        val actual = entry.inputStream().use(::sha256)
        if (actual != hashes.getProperty("original.$LIBAPP")) {
            throw PatchException(
                "This bundle targets RustDesk $APP_VERSION, but the APK carries libapp.so from " +
                    "${describe(actual)}. Use the official RustDesk $APP_VERSION ARM64 APK.",
            )
        }

        val replacement = LibappPayload.open(LIBAPP).use { it.readBytes() }
        if (sha256(replacement) != hashes.getProperty("patched.$LIBAPP")) {
            throw PatchException("RustDesk patch payload checksum mismatch: $LIBAPP")
        }

        val original = entry.readBytes()
        try {
            entry.writeBytes(replacement)
            if (entry.inputStream().use(::sha256) != hashes.getProperty("patched.$LIBAPP")) {
                throw PatchException("The rewritten $path could not be verified.")
            }
        } catch (failure: Exception) {
            runCatching { entry.writeBytes(original) }
            throw PatchException("Unable to replace $path.", failure)
        }
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

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
