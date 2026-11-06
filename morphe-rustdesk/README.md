# RustDesk 用 Morphe パッチ

RustDesk 本体の `libapp.so` / `librustdesk.so` を差し替えず、dex と AndroidManifest だけを書き換える軽量パッチです。公式の RustDesk 1.5.1 AArch64 APK にそのまま適用でき、パッチ一式は 50 KB 程度です。

- [公式 RustDesk 1.5.1 AArch64 APK](https://github.com/rustdesk/rustdesk/releases/download/1.5.1/rustdesk-1.5.1-aarch64-signed.apk)
- [パッチのソース](https://github.com/vta-fun/rustdesk/tree/codex/morphe-ime-favorites)

## パッチ一覧

| パッチ | 既定 | 内容 |
|---|---|---|
| `Fix Japanese soft keyboard input` | 有効 | IME が隠し入力欄を書き換えたときの削除を 1 文字ずつ接続先へ送ります。濁点・小書き・ローマ字変換・ATOK の確定入力が崩れなくなります。 |
| `Settings backup and restore` | 無効 | 接続履歴・お気に入り・ID/リレー/API サーバー設定・公開鍵を JSON に保存し、再起動時に取り込みます。 |

どちらもネイティブライブラリを置き換えないため、公式 APK の署名済みバイナリはそのまま残ります。

## Morphe Manager で適用

1. Sources で Remote ソースを追加し、`patches-bundle.json` の公開 URL を入力します。ローカルの `.mpp` を Sources に取り込むこともできます。
   [Morphe ソース URL](https://pub-c66c63f24a8f448da23039e3ba85c92e.r2.dev/patches-bundle.json)
2. 公式 `rustdesk-1.5.1-aarch64-signed.apk` を選び、必要なパッチを有効にします。
3. 従来の改造 APK に上書きする場合は、適用前に Morphe の署名鍵設定へ従来と同じ鍵を読み込んでください。公式版の署名鍵は別です。
   [Morphe の署名鍵設定](https://github.com/MorpheApp/morphe-manager/blob/main/docs/backup-and-keystore.md)

## バックアップと復元

パッチ適用後に追加される **RustDesk Backup** のアイコン（ランチャー）から開きます。保存先と復元元は Android のファイル選択画面で指定し、Shizuku は不要です。

RustDesk の設定画面に項目を出すには Dart 側（`libapp.so`）の差し替えが要るため、軽量版では設定画面ではなくランチャーから開く形にしています。`rustdesk_connection_backup` の設定チャンネルフックは残してあるので、Dart を差し替えた APK では設定画面からも開けます。

- バックアップ対象: 接続先、最後に使った接続 ID、お気に入り、ID・リレー・API サーバーの設定とサーバー公開鍵。
- 保存パスワード、ログイントークン、端末の RustDesk ID と秘密鍵は書き出しません。
- 復元は現在の設定に取り込みます。復元ファイルを選んで確認したら、RustDesk を完全に終了して開き直すと反映されます。

## 構成

| パス | 内容 |
|---|---|
| `patches/src/main/kotlin/` | パッチ定義。フィンガープリントで難読化された対象メソッドを探し、smali を注入します。 |
| `backup/src/main/java/` | バックアップ／復元の画面と TOML・JSON の読み書き。 |
| `ime/src/main/java/` | IME の編集差分を `TextInputPlugin` へ再送するヘルパー。 |
| `deploy-r2.ps1` | R2 への公開と `patches-bundle.json` の更新。 |

## ビルド

JDK 17 以上とネットワーク接続が必要です（Morphe CLI・D8・Android platform jar を SHA-256 検証付きで取得します）。

```powershell
cd morphe-rustdesk
.\gradlew.bat :patches:buildAndroid
# => patches\build\libs\patches-<version>.mpp
```

公式 APK への適用確認まで行う場合は JDK 21 が必要です。

```powershell
java -jar patches\build\tools\morphe-desktop-2.0.0-all.jar patch --unsigned `
  -p patches\build\libs\patches-1.2.0.mpp `
  rustdesk-1.5.1-aarch64-signed.apk -o rustdesk-patched.apk
```

`%LOCALAPPDATA%\Temp` に書き込めない環境では `java.nio.file.AccessDeniedException` で再構築が止まります。`-Djava.io.tmpdir=<書き込み可能なパス>` を JVM に渡してください。

## Cloudflare R2 に配布

認証済みの `cf` で、バージョンと SHA-256 を含む `.mpp` を先にアップロードします。公開 URL からダウンロードしてハッシュを確認できた時点で、Morphe Manager が参照する `patches-bundle.json` を更新します。

```powershell
.\deploy-r2.ps1 `
  -BundlePath .\patches\build\libs\patches-1.2.0.mpp `
  -Bucket rustdesk-morphe-patches `
  -PublicBaseUrl https://pub-c66c63f24a8f448da23039e3ba85c92e.r2.dev `
  -SourceUrl https://github.com/vta-fun/rustdesk/tree/codex/morphe-ime-favorites
```

`-StageDirectory .\dist` を指定すると、ローカルに配布ファイルだけを生成できます。公開後にバンドルの中身を変更する場合は `patches/build.gradle.kts` のバージョンも上げてください。

## 旧方式（ペイロード差し替え）

`prepare_payload.py`、`rustdesk-1.5.1.patch`、`rustdesk-backup-settings.patch`、`.github/workflows/morphe-*.yml` は、ビルド済み `libapp.so` / `librustdesk.so` を差し替える以前の方式の名残です。現在のバンドルはこれらを必要としません。

## ライセンス

RustDesk 由来のソースとバイナリは GNU AGPL v3 に従います。IME パッチは Mikunyan_346 氏の `patches-1.1.0`（GPLv3）の移植で、元の実装は `app.rustdeskime.patches.ime.JapaneseSoftKeyboardPatch` と `app.rustdeskime.extension.ImeEditReplayer` です。
