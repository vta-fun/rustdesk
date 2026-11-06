package dev.rustdeskfix.patches

import app.morphe.patcher.patch.bytecodePatch

/**
 * Owns the Java side of this bundle.
 *
 * Every other patch here only adds calls; the classes they call live in this extension and are
 * merged into the patched app by the patcher.
 */
internal val rustdeskExtension = bytecodePatch {
    extendWith("extensions/rustdesk-extension.mpe")
}
