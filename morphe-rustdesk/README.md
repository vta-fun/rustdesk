# RustDesk 1.5.1 用 Morphe パッチ

RustDesk 1.5.1 の公式 AArch64 APK 用の Morphe パッチです。キーボード修正は Android IME で確定した文字を接続先へ送信します。バックアップを有効にすると、設定から接続データを保存・復元できます。LAN で見つけた接続先のお気に入り名の保存修正は、どちらのパッチにも含まれます。

- [公式 RustDesk 1.5.1 AArch64 APK](https://github.com/rustdesk/rustdesk/releases/download/1.5.1/rustdesk-1.5.1-aarch64-signed.apk)
- [パッチ適用済み RustDesk と Morphe バンドルのソース](https://github.com/vta-fun/rustdesk/tree/codex/morphe-ime-favorites)
- [ARM64 パッチ素材のビルド](https://github.com/vta-fun/rustdesk/actions/runs/42135503457)

## Morphe Manager で適用

1. Sources で Remote ソースを追加し、`patches-bundle.json` の公開 URL を入力します。ローカルの `.mpp` を Sources に取り込むこともできます。 [Morphe ソース URL](https://pub-c66c63f24a8f448da23039e3ba85c92e.r2.dev/patches-bundle.json)
2. 公式 `rustdesk-1.5.1-aarch64-signed.apk` を選択し、`Android keyboard and Japanese IME` を適用します。
3. 従来の改造 APK に上書きする場合は、適用前に Morphe の署名鍵設定に従来と同じ鍵を読み込んでください。公式版に使われている署名鍵は別です。 [Morphe の署名鍵設定](https://github.com/MorpheApp/morphe-manager/blob/main/docs/backup-and-keystore.md)

### 接続先のバックアップと復元

Morphe のパッチ一覧で `Settings backup and restore` を選択して適用します。インストールしたら、RustDesk 本体の **設定 → バックアップと復元** を開きます。保存先や復元元は Android のファイル選択画面で指定します。Shizuku は不要です。

- バックアップ対象は接続先、最後に使った接続 ID、お気に入り、ID・リレー・API サーバーの設定とサーバー公開鍵です。
- 保存パスワード、ログイントークン、端末の RustDesk ID と秘密鍵は書き出しません。
- 復元は現在の設定に取り込みます。復元ファイルを選んで確認したら、RustDesk を完全に終了して開き直すと反映されます。

## バンドルをビルド

Python 3.11 以上と JDK 21 以上を使用します。下記のコマンドはこのディレクトリで実行します。

1. 上記の修正済み RustDesk ブランチをチェックアウトし、`fixes-payload-build.yml` をその `.github/workflows/morphe-fixes.yml` に配置します。Flutter と Rust を 2 種類ビルドします。ブリッジのアーティファクトを再生成した場合は、ワークフローの参照先を更新します。
2. 公式 APK と `keyboard-and-backup`、`backup` のアーティファクトに含まれる APK から素材を生成します。例では展開後の APK を `keyboard-and-backup.apk` と `backup.apk` にリネームしています。

   ```powershell
   python .\prepare_payload.py .\rustdesk-1.5.1-aarch64-signed.apk .\keyboard-and-backup.apk --backup-only .\backup.apk
   .\gradlew.bat :patches:buildAndroid
   ```

3. Morphe Desktop で `patches/build/libs/patches-1.1.1.mpp` を公式 APK に適用し、生成 APK の差し替え内容を検証します。以下はキーボードパッチ単体の検証例です。

   ```powershell
   java -jar .\morphe-desktop-2.0.0-all.jar patch --exclusive -p .\patches\build\libs\patches-1.1.1.mpp -e 'Android keyboard and Japanese IME' .\rustdesk-1.5.1-aarch64-signed.apk --out .\rustdesk-patched.apk
   python .\prepare_payload.py .\rustdesk-1.5.1-aarch64-signed.apk .\keyboard-and-backup.apk --patched .\rustdesk-patched.apk
   ```

## Cloudflare R2 に配布

認証済みの `cf` で、バージョンと SHA-256 を含む `.mpp` を先にアップロードします。公開 URL からダウンロードしてハッシュを確認できた時点で、Morphe Manager が参照する `patches-bundle.json` を更新します。

```powershell
.\deploy-r2.ps1 `
  -BundlePath .\patches\build\libs\patches-1.1.1.mpp `
  -Bucket rustdesk-morphe-patches `
  -PublicBaseUrl https://pub-c66c63f24a8f448da23039e3ba85c92e.r2.dev `
  -SourceUrl https://github.com/vta-fun/rustdesk/tree/codex/morphe-ime-favorites
```

`-StageDirectory .\dist` を指定すると、ローカルに配布ファイルだけを生成できます。公開後にバンドルの中身を変更する場合は `patches/build.gradle.kts` のバンドルバージョンも上げてください。

RustDesk 由来のソースとバイナリは GNU AGPL v3 に従います。 [ライセンス本文](https://github.com/vta-fun/rustdesk/blob/a6a7388bf16705af24b50ad5f6f45bd854f49197/LICENCE)
