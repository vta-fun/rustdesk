# RustDesk 1.5.1 用 Morphe パッチ

Android のキーボードモード選択と日本語 IME の変換中テキスト反映を、RustDesk 1.5.1 の公式 AArch64 APK に適用します。Morphe が APK 内の Flutter アプリと Rust ライブラリを差し替えます。公式 ARM64 APK と一致するネイティブライブラリを検証してから適用するため、Universal APK や別バージョンは対象外です。

- [公式 RustDesk 1.5.1 AArch64 APK](https://github.com/rustdesk/rustdesk/releases/download/1.5.1/rustdesk-1.5.1-aarch64-signed.apk)
- [パッチ適用済み RustDesk ソースとビルド手順](https://github.com/vta-fun/rustdesk/tree/a6a7388bf16705af24b50ad5f6f45bd854f49197)
- [ARM64 パッチ素材のビルド](https://github.com/vta-fun/rustdesk/actions/runs/42126226934)

## Morphe Manager で適用

1. Sources で Remote ソースを追加し、R2 の `patches-bundle.json` の公開 URL を入力します。ローカルの `.mpp` を Sources に取り込むこともできます。
2. 公式 `rustdesk-1.5.1-aarch64-signed.apk` を選択し、`Android keyboard and Japanese IME` を適用します。
3. 従来の改造 APK に上書きする場合は、適用前に Morphe の署名鍵設定に従来と同じ鍵を読み込んでください。公式版に使われている署名鍵は別です。 [Morphe の署名鍵設定](https://github.com/MorpheApp/morphe-manager/blob/main/docs/backup-and-keystore.md)

### 接続先のバックアップと復元

Morphe のパッチ一覧で `Settings backup and restore` を選択して適用します。インストールすると、Android のアプリ一覧に RustDesk のバックアップ用画面が追加されます。保存先や復元元は Android のファイル選択画面で指定します。Shizuku は不要です。

- バックアップ対象は接続先、最後に使った接続 ID、お気に入り、ID・リレー・API サーバーの設定とサーバー公開鍵です。
- 保存パスワード、ログイントークン、端末の RustDesk ID と秘密鍵は書き出しません。
- 復元は現在の設定に取り込みます。復元ファイルを選んで確認したら、RustDesk を完全に終了して開き直すと反映されます。

## バンドルをビルド

Python 3.11 以上と JDK 21 以上を使用します。下記のコマンドはこのディレクトリで実行します。

1. 公式 1.5.1 のソースに `rustdesk-1.5.1.patch` を `git apply` で適用します。上記のビルド済みソースには適用済みです。`payload-build.yml` をそのチェックアウトの `.github/workflows/morphe-payload.yml` に配置すると、Actions で既存の Flutter / Android NDK ツールチェーンを使って `rustdesk-1.5.1-morphe-payload-arm64` をビルドできます。
2. 公式 APK と、そのアーティファクトを展開した `app-arm64-v8a-release.apk` からパッチ素材を生成します。

   ```powershell
   python .\prepare_payload.py .\rustdesk-1.5.1-aarch64-signed.apk .\app-arm64-v8a-release.apk
   .\gradlew.bat :patches:buildAndroid
   ```

3. Morphe Desktop で `patches/build/libs/patches-1.0.0.mpp` を公式 APK に適用し、生成 APK の差し替え内容を検証します。署名と動作は、Android のビルドツールと端末で確認できます。

   ```powershell
   java -jar .\morphe-desktop-2.0.0-all.jar patch --exclusive -p .\patches\build\libs\patches-1.0.0.mpp -e 'Android keyboard and Japanese IME' .\rustdesk-1.5.1-aarch64-signed.apk --out .\rustdesk-patched.apk
   python .\prepare_payload.py .\rustdesk-1.5.1-aarch64-signed.apk .\app-arm64-v8a-release.apk --patched .\rustdesk-patched.apk
   ```

## Cloudflare R2 に配布

認証済みの `cf` で、バージョンと SHA-256 を含む `.mpp` を先にアップロードします。公開 URL からダウンロードしてハッシュを確認できた時点で、Morphe Manager が参照する `patches-bundle.json` を更新します。

```powershell
.\deploy-r2.ps1 `
  -BundlePath .\patches\build\libs\patches-1.0.0.mpp `
  -Bucket rustdesk-morphe-patches `
  -PublicBaseUrl https://pub-c66c63f24a8f448da23039e3ba85c92e.r2.dev `
  -SourceUrl https://github.com/vta-fun/rustdesk/tree/a6a7388bf16705af24b50ad5f6f45bd854f49197
```

`-StageDirectory .\dist` を指定すると、ローカルに配布ファイルだけを生成できます。公開後にバンドルの中身を変更する場合は `patches/build.gradle.kts` のバンドルバージョンも上げてください。

RustDesk 由来のソースとバイナリは GNU AGPL v3 に従います。 [ライセンス本文](https://github.com/vta-fun/rustdesk/blob/a6a7388bf16705af24b50ad5f6f45bd854f49197/LICENCE)
