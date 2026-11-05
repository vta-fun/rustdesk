package dev.rustdeskfix.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.w3c.dom.Element

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val BACKUP_ACTIVITY = "dev.rustdeskfix.backup.BackupActivity"
private const val MAIN_APPLICATION = "Lcom/carriez/flutter_hbb/MainApplication;"
private const val ANDROID_ON_CREATE = "Landroid/app/Application;->onCreate()V"
private const val RUSTDESK_ON_APP_START = "Lffi/FFI;->onAppStart(Landroid/content/Context;)V"
private const val APPLY_PENDING = "Ldev/rustdeskfix/backup/BackupStore;->applyPending(Landroid/content/Context;)V"

private val backupStartupPatch = bytecodePatch {
    extendWith("extensions/rustdesk-backup.mpe")

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
    }
}

@Suppress("unused")
val settingsBackupPatch = resourcePatch(
    name = "Settings backup and restore",
    description = "Adds a RustDesk Backup launcher for exporting settings and restoring them at app startup using the Android file picker.",
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
    dependsOn(backupStartupPatch)

    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("The RustDesk application declaration was not found.")
            val activities = application.getElementsByTagName("activity")
            for (index in 0 until activities.length) {
                val activity = activities.item(index) as Element
                if (activity.getAttribute("android:name") == BACKUP_ACTIVITY) {
                    throw PatchException("The RustDesk Backup launcher is already installed. Patch an original RustDesk APK.")
                }
            }
            val activity = manifest.createElement("activity").apply {
                setAttributeNS(ANDROID_NS, "android:name", BACKUP_ACTIVITY)
                setAttributeNS(ANDROID_NS, "android:exported", "true")
                setAttributeNS(ANDROID_NS, "android:label", "RustDesk Backup")
                setAttributeNS(ANDROID_NS, "android:launchMode", "singleTop")
                setAttributeNS(ANDROID_NS, "android:theme", "@android:style/Theme.Material.Light.NoActionBar")
            }
            val intentFilter = manifest.createElement("intent-filter")
            for ((tag, name) in listOf("action" to "android.intent.action.MAIN", "category" to "android.intent.category.LAUNCHER")) {
                intentFilter.appendChild(manifest.createElement(tag).apply {
                    setAttributeNS(ANDROID_NS, "android:name", name)
                })
            }
            activity.appendChild(intentFilter)
            application.appendChild(activity)
        }
    }
}
