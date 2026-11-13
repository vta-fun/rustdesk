package dev.rustdeskfix.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val BACKUP_ACTIVITY = "dev.rustdeskfix.backup.BackupActivity"
private const val MAIN_APPLICATION = "Lcom/carriez/flutter_hbb/MainApplication;"
private const val MAIN_ACTIVITY = "Lcom/carriez/flutter_hbb/MainActivity;"
private const val ANDROID_ON_CREATE = "Landroid/app/Application;->onCreate()V"
private const val RUSTDESK_ON_APP_START = "Lffi/FFI;->onAppStart(Landroid/content/Context;)V"
private const val APPLY_PENDING = "Ldev/rustdeskfix/backup/BackupStore;->applyPending(Landroid/content/Context;)V"
private const val METHOD_NAME = "rustdesk_connection_backup"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

private val backupStartupPatch = bytecodePatch {
    execute {
        val onCreate = mutableClassDefBy(MAIN_APPLICATION).methods.singleOrNull { method ->
            method.name == "onCreate" && method.parameterTypes.isEmpty() && method.returnType == "V"
        } ?: throw PatchException("The RustDesk application startup method was not found.")
        val references = onCreate.implementation?.instructions?.map { instruction ->
            (instruction as? ReferenceInstruction)?.reference?.toString()
        } ?: throw PatchException("The RustDesk application startup method has no instructions.")
        if (APPLY_PENDING in references) {
            throw PatchException("The RustDesk restore hook is already installed. Patch an original RustDesk APK.")
        }
        val superCall = references.indexOf(ANDROID_ON_CREATE)
        if (superCall < 0) {
            throw PatchException("The RustDesk startup does not call Application.onCreate.")
        }
        val nativeStart = references.indexOf(RUSTDESK_ON_APP_START)
        if (nativeStart in 0..superCall) {
            throw PatchException("The RustDesk startup sequence does not match the expected order.")
        }
        onCreate.addInstructions(superCall + 1, "invoke-static {p0}, $APPLY_PENDING")

        // Handlers are reached through Flutter's MethodCallHandler, which R8 renames on every
        // release, so they are located by the shape of their arguments. RustDesk registers more
        // than one channel, and an unmatched method name simply falls through to the stock code,
        // so every handler of this shape is hooked.
        val main = mutableClassDefBy(MAIN_ACTIVITY)
        val lookup = { type: String -> runCatching { mutableClassDefBy(type) }.getOrNull() }
        val isMethodCall = { type: String ->
            val def = lookup(type)
            def != null && def.fields.count { it.type == STRING } == 1 &&
                def.fields.count { it.type == OBJECT } == 1
        }
        val isResult = { type: String ->
            val def = lookup(type)
            def != null && def.methods.count { method ->
                method.returnType == "V" && method.parameterTypes.map { it.toString() } == listOf(OBJECT)
            } == 1
        }
        val hooks = main.methods.filter { method ->
            method.returnType == "V" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0].toString() == MAIN_ACTIVITY &&
                isMethodCall(method.parameterTypes[1].toString()) &&
                isResult(method.parameterTypes[2].toString()) &&
                (method.implementation?.let { it.registerCount - method.parameterTypes.size >= 3 } == true)
        }
        if (hooks.isEmpty()) {
            throw PatchException("The RustDesk method channel handler was not found. Patch an original RustDesk APK.")
        }

        for (channel in hooks) {
            val channelBody = channel.implementation
                ?: throw PatchException("The RustDesk method channel handler has no instructions.")
            val callClass = channel.parameterTypes[1].toString()
            val callDef = lookup(callClass) ?: throw PatchException("The RustDesk method call class was not found.")
            val nameField = callDef.fields.single { it.type == STRING }.name
            val argsField = callDef.fields.single { it.type == OBJECT }.name
            val resultClass = channel.parameterTypes[2].toString()
            val resultDef = lookup(resultClass)
                ?: throw PatchException("The RustDesk method channel result class was not found.")
            val resultMethod = resultDef.methods.single { method ->
                method.returnType == "V" && method.parameterTypes.map { it.toString() } == listOf(OBJECT)
            }.name

            channel.addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p1
                    iget-object v1, v0, $callClass->$nameField:$STRING
                    const-string v2, "$METHOD_NAME"
                    invoke-virtual {v2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                    move-result v1
                    if-eqz v1, :stock_handler
                    iget-object v1, v0, $callClass->$argsField:$OBJECT
                    move-object/from16 v0, p0
                    invoke-static {v0, v1}, Ldev/rustdeskfix/backup/BackupActivity;->fromSettings(Landroid/app/Activity;Ljava/lang/Object;)Z
                    move-result v1
                    invoke-static {v1}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                    move-result-object v1
                    move-object/from16 v0, p2
                    invoke-interface {v0, v1}, $resultClass->$resultMethod($OBJECT)V
                    return-void
                """.trimIndent(),
                ExternalLabel("stock_handler", channelBody.instructions.first()),
            )
        }
    }
}

@Suppress("unused")
val settingsBackupPatch = resourcePatch(
    name = "Settings backup and restore",
    description = "Adds Backup and restore to RustDesk Settings: connection history, favorites and " +
        "server settings are written to a file through the Android file picker and restored at " +
        "app startup. Replaces libapp.so with a build of this fork, which carries the entry.",
    default = false,
) {
    compatibleWith(
        Compatibility(
            packageName = "com.carriez.flutter_hbb",
            name = "RustDesk",
            apkFileType = ApkFileType.APK,
            // The hooks are located by shape, so the patch is offered for releases newer than
            // 1.5.1 as well. A mismatch fails while patching instead of shipping a broken APK.
            targets = listOf(AppTarget(version = "1.5.1"), AppTarget(version = null)),
        ),
    )
    dependsOn(rustdeskExtension, backupStartupPatch, libappPayloadPatch)

    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("The RustDesk application declaration was not found.")
            val activities = application.getElementsByTagName("activity")
            for (index in 0 until activities.length) {
                val activity = activities.item(index) as Element
                if (activity.getAttribute("android:name") == BACKUP_ACTIVITY) {
                    throw PatchException("The RustDesk backup activity is already installed. Patch an original RustDesk APK.")
                }
            }
            application.appendChild(manifest.activity())
        }
    }
}

private fun Document.activity(): Element {
    val activity = createElement("activity")
    activity.setAttributeNS(ANDROID_NS, "android:name", BACKUP_ACTIVITY)
    activity.setAttributeNS(ANDROID_NS, "android:exported", "false")
    activity.setAttributeNS(ANDROID_NS, "android:label", "RustDesk Backup")
    activity.setAttributeNS(ANDROID_NS, "android:launchMode", "singleTop")
    activity.setAttributeNS(ANDROID_NS, "android:theme", "@android:style/Theme.Material.Light.NoActionBar")
    return activity
}

private fun Document.element(name: String, attribute: String, value: String): Element =
    createElement(name).apply { setAttributeNS(ANDROID_NS, attribute, value) }

