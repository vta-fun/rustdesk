package dev.rustdeskfix.backup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class BackupActivity extends Activity {
    private static final int EXPORT_DOCUMENT = 3101;
    private static final int IMPORT_DOCUMENT = 3102;
    private static final String PICKER_REQUEST = "pickerRequest";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private enum Status {
        READY, CHECKING, EXPORTING, EXPORTED, EXPORT_FAILED,
        READING, INVALID_BACKUP, STAGING, STAGE_FAILED, SIGN_OUT_REQUIRED, PICKER_FAILED, SETTINGS_FAILED
    }

    private static final class ScreenState {
        BackupActivity activity;
        Status status = Status.READY;
        int pickerRequest;
        boolean pendingRestore;
        BackupStore.RestoreSummary restoreSummary;
    }

    private interface FileOperation {
        BackupStore.RestoreSummary run(Context context) throws IOException;
    }

    private ScreenState screen;
    private Button exportButton;
    private Button restoreButton;
    private Button settingsButton;
    private TextView statusText;
    private TextView restartInstructions;
    private AlertDialog restoreDialog;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScreenState retained = (ScreenState) getLastNonConfigurationInstance();
        screen = retained == null ? new ScreenState() : retained;
        screen.activity = this;
        if (retained == null && savedInstanceState != null) {
            screen.pickerRequest = savedInstanceState.getInt(PICKER_REQUEST);
        }

        String title = localized("RustDesk Backup", "RustDesk バックアップ");
        setTitle(title);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> applyInsets(content, padding, insets));
        scroll.addView(content);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 35) {
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                bars.setSystemBarsAppearance(light, light);
            }
        }

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextSize(20);
        heading.setPadding(0, 0, 0, Math.round(12 * getResources().getDisplayMetrics().density));
        content.addView(heading);

        TextView introduction = new TextView(this);
        introduction.setText(localized(
                "Save connection IDs from history and favorites, and your server settings, to a file. Restoring a backup preserves this device’s passwords and identity.",
                "接続履歴やお気に入りの接続先 ID と、サーバー設定をファイルに保存できます。復元しても、この端末のパスワードと端末固有の ID は保持されます。"));
        introduction.setTextSize(16);
        content.addView(introduction);

        exportButton = addButton(content, localized("Save backup", "バックアップを保存"),
                () -> openDocumentPicker(EXPORT_DOCUMENT));
        restoreButton = addButton(content, localized("Restore from backup", "バックアップから復元"),
                () -> openDocumentPicker(IMPORT_DOCUMENT));

        statusText = new TextView(this);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(statusText);

        restartInstructions = new TextView(this);
        restartInstructions.setText(localized(
                "Restore is ready. End any active connections, open App info, tap Force stop, then open RustDesk again to apply the backup.",
                "復元の準備ができました。接続中のセッションを終了し、アプリ情報で「強制停止」を選んでから RustDesk を開き直すと、バックアップが適用されます。"));
        restartInstructions.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(restartInstructions);
        settingsButton = addButton(content, localized("Open App info", "アプリ情報を開く"), this::openAppInfo);

        if (retained == null) {
            ScreenState current = screen;
            Context context = getApplicationContext();
            current.status = Status.CHECKING;
            new Thread(() -> {
                boolean pending = BackupStore.hasPendingRestore(context);
                MAIN.post(() -> {
                    if (current.status == Status.CHECKING) current.status = Status.READY;
                    current.pendingRestore |= pending;
                    if (current.activity != null) current.activity.render();
                });
            }, "rustdesk-backup-status").start();
        }
        render();
    }

    @SuppressWarnings("deprecation")
    private WindowInsets applyInsets(LinearLayout content, int padding, WindowInsets insets) {
        content.setPadding(padding + insets.getSystemWindowInsetLeft(), padding + insets.getSystemWindowInsetTop(),
                padding + insets.getSystemWindowInsetRight(), padding + insets.getSystemWindowInsetBottom());
        return insets;
    }

    private Button addButton(LinearLayout content, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        content.addView(button);
        return button;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        render();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(PICKER_REQUEST, screen.pickerRequest);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return screen;
    }

    @Override
    protected void onDestroy() {
        screen.activity = null;
        if (restoreDialog != null) restoreDialog.dismiss();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void openDocumentPicker(int request) {
        boolean export = request == EXPORT_DOCUMENT;
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(export ? "application/json" : "*/*");
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "rustdesk-connections.json");
        screen.status = Status.READY;
        screen.pickerRequest = request;
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException | SecurityException error) {
            screen.pickerRequest = 0;
            screen.status = Status.PICKER_FAILED;
        }
        render();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT_DOCUMENT && request != IMPORT_DOCUMENT) return;
        screen.pickerRequest = 0;
        Uri uri = data == null ? null : data.getData();
        if (result != RESULT_OK || uri == null) {
            render();
            return;
        }

        if (request == EXPORT_DOCUMENT) {
            perform(Status.EXPORTING, Status.EXPORTED, Status.EXPORT_FAILED, context -> {
                byte[] backup = BackupStore.exportBackup(context);
                try (OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new IOException("Destination unavailable");
                    output.write(backup);
                }
                return null;
            });
        } else {
            perform(Status.READING, Status.READY, Status.INVALID_BACKUP, context -> {
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("Backup unavailable");
                    return BackupStore.inspectBackup(input);
                }
            });
        }
    }

    private void perform(Status progress, Status success, Status failure, FileOperation operation) {
        ScreenState current = screen;
        Context context = getApplicationContext();
        current.status = progress;
        render();
        new Thread(() -> {
            BackupStore.RestoreSummary result = null;
            Status outcome;
            try {
                result = operation.run(context);
                outcome = success;
            } catch (BackupStore.SignOutRequiredException error) {
                outcome = Status.SIGN_OUT_REQUIRED;
            } catch (IOException | SecurityException | IllegalArgumentException error) {
                outcome = failure;
            }
            BackupStore.RestoreSummary summary = result;
            Status completed = outcome;
            MAIN.post(() -> {
                current.status = completed;
                current.restoreSummary = summary;
                if (progress == Status.STAGING && completed == Status.READY) current.pendingRestore = true;
                if (current.activity != null) current.activity.render();
            });
        }, "rustdesk-backup").start();
    }

    private void confirmRestore() {
        BackupStore.RestoreSummary summary = screen.restoreSummary;
        String details = String.format(Locale.getDefault(), localized(
                "Merge %1$d recent connections and %2$d favorites? RustDesk will apply the backed-up ID, relay and API server settings and public key on restart.",
                "接続履歴 %1$d 件とお気に入り %2$d 件を追加・更新しますか？ RustDesk の再起動時に、バックアップの ID・リレー・API サーバー設定と公開鍵が適用されます。"),
                summary.peerCount, summary.favoriteCount);
        if (!summary.lastPeerId.isEmpty()) {
            details += "\n\n" + localized("The last connection ID will also be restored.",
                    "最後に使った接続先 ID も復元されます。");
        }
        if (screen.pendingRestore) {
            details += "\n\n" + localized("This replaces the restore already waiting to be applied.",
                    "適用待ちのバックアップは、このファイルに置き換わります。");
        }
        restoreDialog = new AlertDialog.Builder(this)
                .setTitle(localized("Restore connections", "接続データを復元"))
                .setMessage(details)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> clearConfirmation())
                .setOnCancelListener(dialog -> clearConfirmation())
                .setPositiveButton(localized("Restore", "復元する"), (dialog, which) -> {
                    screen.restoreSummary = null;
                    perform(Status.STAGING, Status.READY, Status.STAGE_FAILED, context -> {
                        BackupStore.stageRestore(context, summary);
                        return null;
                    });
                })
                .show();
    }

    private void clearConfirmation() {
        screen.restoreSummary = null;
        render();
    }

    private void openAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException | SecurityException error) {
            screen.status = Status.SETTINGS_FAILED;
            render();
        }
    }

    private void render() {
        boolean idle = screen.pickerRequest == 0 && screen.restoreSummary == null &&
                screen.status != Status.CHECKING && screen.status != Status.EXPORTING &&
                screen.status != Status.READING && screen.status != Status.STAGING;
        exportButton.setEnabled(idle);
        restoreButton.setEnabled(idle);
        settingsButton.setEnabled(idle);
        restartInstructions.setVisibility(screen.pendingRestore ? View.VISIBLE : View.GONE);
        settingsButton.setVisibility(screen.pendingRestore ? View.VISIBLE : View.GONE);

        String message = statusMessage();
        statusText.setText(message);
        statusText.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        if (resumed && !isFinishing() && screen.restoreSummary != null &&
                (restoreDialog == null || !restoreDialog.isShowing())) {
            confirmRestore();
        }
    }

    private String statusMessage() {
        switch (screen.status) {
            case EXPORTING: return localized("Saving backup…", "バックアップを保存しています…");
            case EXPORTED: return localized("Backup saved.", "バックアップを保存しました。");
            case EXPORT_FAILED: return localized("Could not save the backup.", "バックアップを保存できませんでした。");
            case READING: return localized("Checking the backup…", "バックアップを確認しています…");
            case INVALID_BACKUP: return localized("Could not read a valid RustDesk backup.", "有効な RustDesk バックアップを読み込めませんでした。");
            case STAGING: return localized("Preparing restore…", "復元の準備をしています…");
            case STAGE_FAILED: return localized("Could not prepare the restore.", "復元の準備ができませんでした。");
            case SIGN_OUT_REQUIRED: return localized("Sign out of RustDesk before restoring a backup that changes the API server.",
                    "API サーバーを変更するには、RustDesk でログアウトしてから復元してください。");
            case PICKER_FAILED: return localized("Could not open the file picker.", "ファイル選択画面を開けませんでした。");
            case SETTINGS_FAILED: return localized("Open the RustDesk App info page in Android Settings.", "Android の設定から RustDesk のアプリ情報を開いてください。");
            default: return "";
        }
    }

    private static String localized(String english, String japanese) {
        return "ja".equals(Locale.getDefault().getLanguage()) ? japanese : english;
    }
}
