package dev.rustdeskfix.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val REPLAYER = "Ldev/rustdeskfix/ime/ImeEditReplayer;"
private const val REMEMBER = "$REPLAYER->rememberDartText(Ljava/lang/String;)V"
private const val REPLAY = "$REPLAYER->replayRemovals(Ljava/lang/Object;ILjava/lang/String;)V"

/** Flutter's text input plugin in the RustDesk APK is obfuscated, so it is matched by shape. */
private object DidChangeEditingStateFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z", "Z", "Z"),
    filters = listOf(
        methodCall(definingClass = "Ljava/lang/String;", name = "equals"),
        opcode(Opcode.MOVE_RESULT, InstructionLocation.MatchAfterImmediately()),
        string("send EditingState to flutter: "),
    ),
)

private object UpdateEditingStateFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("I", "Ljava/lang/String;", "I", "I", "I", "I"),
    filters = listOf(string("TextInputClient.updateEditingState")),
)

@Suppress("unused")
val imeKeyboardPatch = bytecodePatch(
    name = "Fix Japanese soft keyboard input",
    description = "Sends the text an IME replaces on the hidden input field to the remote computer " +
        "as it is typed, so dakuten, small kana, romaji conversion and ATOK-style commits stop " +
        "arriving garbled. Port of the RustDesk IME patch by Mikunyan_346 (GPLv3).",
) {
    compatibleWith(
        Compatibility(
            packageName = "com.carriez.flutter_hbb",
            name = "RustDesk",
            apkFileType = ApkFileType.APK,
            targets = listOf(AppTarget(version = "1.5.1"), AppTarget(version = null)),
        ),
    )
    dependsOn(rustdeskExtension)

    execute {
        val changed = DidChangeEditingStateFingerprint
        val matches = changed.instructionMatches
        // The register holding the text Flutter currently shows is the argument of String.equals.
        val shown = matches.first().instruction as? FiveRegisterInstruction
            ?: throw PatchException("The RustDesk editing state comparison does not match version 1.5.1.")
        changed.method.addInstruction(
            matches[1].index + 1,
            "invoke-static {v${shown.registerD}}, $REMEMBER",
        )
        UpdateEditingStateFingerprint.method.addInstruction(0, "invoke-static {p0, p1, p2}, $REPLAY")
    }
}
