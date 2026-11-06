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
private const val BACKUP_ALIAS = "dev.rustdeskfix.backup.BackupLauncher"
private const val MAIN_APPLICATION = "Lcom/carriez/flutter_hbb/MainApplication;"
private const val MAIN_ACTIVITY = "Lcom/carriez/flutter_hbb/MainActivity;"
private const val ANDROID_ON_CREATE = "Landroid/app/Application;->onCreate()V"
private const val RUSTDESK_ON_APP_START = "Lffi/FFI;->onAppStart(Landroid/content/Context;)V"
private const val APPLY_PENDING = "Ldev/rustdeskfix/backup/BackupStore;->applyPending(Landroid/content/Context;)V"

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
        val nativeStart = references.indexOf(RUSTDESK_ON_APP_START)
        if (superCall < 0 || nativeStart <= superCall) {
            throw PatchException("The RustDesk startup sequence does not match version 1.5.1.")
        }
        onCreate.addInstructions(superCall + 1, "invoke-static {p0}, $APPLY_PENDING")

        // R8 names in the official RustDesk 1.5.1 ARM64 APK's MethodChannel handler.
        val channel = mutableClassDefBy(MAIN_ACTIVITY).methods.singleOrNull { method ->
            method.name == "J0" && method.parameterTypes.map { it.toString() } ==
                listOf(MAIN_ACTIVITY, "Lz2/j;", "Lz2/k\$d;") && method.returnType == "V"
        } ?: throw PatchException("The RustDesk settings channel was not found.")
        val channelBody = channel.implementation
            ?: throw PatchException("The RustDesk settings channel has no instructions.")
        if (channelBody.registerCount != 36) {
            throw PatchException("The RustDesk settings channel does not match version 1.5.1 ARM64.")
        }
        channel.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                iget-object v1, v0, Lz2/j;->a:Ljava/lang/String;
                const-string v2, "rustdesk_connection_backup"
                invoke-virtual {v2, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v1
                if-eqz v1, :stock_handler
                iget-object v1, v0, Lz2/j;->b:Ljava/lang/Object;
                move-object/from16 v0, p0
                invoke-static {v0, v1}, Ldev/rustdeskfix/backup/BackupActivity;->fromSettings(Landroid/app/Activity;Ljava/lang/Object;)Z
                move-result v1
                invoke-static {v1}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                move-result-object v1
                move-object/from16 v0, p2
                invoke-interface {v0, v1}, Lz2/k${'$'}d;->a(Ljava/lang/Object;)V
                return-void
            """.trimIndent(),
            ExternalLabel("stock_handler", channelBody.instructions.first()),
        )
    }
}

@Suppress("unused")
val settingsBackupPatch = resourcePatch(
    name = "Settings backup and restore",
    description = "Adds Backup and restore to RustDesk: connection history, favorites and server " +
        "settings are written to a file through the Android file picker and restored at app startup.",
    default = false,
) {
    compatibleWith(
        Compatibility(
            packageName = "com.carriez.flutter_hbb",
            name = "RustDesk",
            apkFileType = ApkFileType.APK,
            targets = listOf(AppTarget(version = "1.5.1")),
        ),
    )
    dependsOn(rustdeskExtension, backupStartupPatch)

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
            application.appendChild(manifest.launcherAlias())
        }
    }
}

/**
 * The settings page that calls `rustdesk_connection_backup` is Dart compiled into libapp.so, so a
 * launcher alias is the entry point that does not require replacing any Rust or Flutter library.
 */
private fun Document.launcherAlias(): Element {
    val alias = createElement("activity-alias")
    alias.setAttributeNS(ANDROID_NS, "android:name", BACKUP_ALIAS)
    alias.setAttributeNS(ANDROID_NS, "android:targetActivity", BACKUP_ACTIVITY)
    alias.setAttributeNS(ANDROID_NS, "android:exported", "true")
    alias.setAttributeNS(ANDROID_NS, "android:label", "RustDesk Backup")
    alias.setAttributeNS(ANDROID_NS, "android:launchMode", "singleTop")
    alias.appendChild(
        createElement("intent-filter").apply {
            appendChild(element("action", "android:name", "android.intent.action.MAIN"))
            appendChild(element("category", "android:name", "android.intent.category.LAUNCHER"))
        },
    )
    return alias
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
