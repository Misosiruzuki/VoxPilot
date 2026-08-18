# Changelog

## Unreleased

## 1.2.7

### Fixed
- Fail fast when the client never reaches a world / multiplayer server (90s agent-side timeout, connection-error log scan, client process death detection)
- Clearer `VOXPILOT_FAILED=...` messages with `client.log` tail instead of waiting until long scenario timeout



## 1.2.6

### Added
- Scenario actions: `hotbar`, `containerClick` (pickup/quick_move), `closeScreen`
- Frame telemetry: `screen`, `hasContainerScreen`, `mainHand`, `carried`, `hotbarSelected`



## 1.2.5

### Fixed
- Embed **dev** (official-mapped) agent jar so MDK `runClient` no longer crashes with `NoSuchMethodError Minecraft.m_91087_()`.



## 1.2.4

### Changed
- 長時間シナリオ対応（クライアント終了待ちをシナリオ長から算出、最大2時間）
- ソケット読み取りタイムアウト 60分、デフォルト totalFrames 600
- GitHub Actions でビルド／Release 自動化（`scripts/build-jar.sh`）


### Changed
- 長時間シナリオ対応: クライアント終了待ちをシナリオの `totalTicks`/`totalFrames` から算出（最大2時間）。ソケット読み取りタイムアウトを60分に延長。デフォルト `totalFrames` を 600 に変更。


## 1.2.3

- Added per-frame `trackedBlocks` block-state telemetry for crop age and similar stateful tests.

- tick/frameアクションの初回にMinecraftコマンドを実行できる`commands`を追加。
- 追跡LivingEntityへ体力、被弾時間、腕振り状態・時間・腕を記録。

## 1.2.2

- 各フレームへワールドの`gameTime`を記録し、描画FPSに左右されない`fromTick`／`toTick`アクションと`totalTicks`終了条件に対応
- カスタム名`VoxPilotTrack`のエンティティの種類・座標・Yawを各フレームへ記録

## 1.2.1

- JSONアクションへ`debugScreen`を追加し、OS入力やフォーカス移動なしでF3画面を検証可能に変更
- 新規MDKで`run/mods`がまだ存在しない場合も、クライアント用Modの導入前に自動作成
- ForgeGradleの変換出力を安定キャッシュへ退避し、起動構成間で安全に再利用

## 1.2.0

- 初回だけ読み込む最大軽量化プロファイルを追加
- Minecraft、Embeddium、ImmediatelyFast、Entity Culling、FerriteCore、ServerCoreを自動設定
- 適用前コンフィグの自動バックアップと、以後の手動設定を保持する適用マーカーを追加
- 専用テストサーバーのシミュレーション距離を5へ縮小し、localhost通信の圧縮を停止

## 1.1.0

- Forge 1.20.1 MDKで実測済みの既定軽量化構成を自動導入
- クライアント専用Modを専用サーバー起動後に分離導入
- F8thful v6.0（8x8）の自動ダウンロードとデフォルト有効化を追加
- すべての自動取得ファイルをSHA-512で検証
- ダウンロードキャッシュと管理対象ファイル一覧を追加
- ForgeGradleによる配布用Modの開発名前空間への自動再マッピングを追加

## 1.0.0

- JSONシナリオ、フレーム同期入力、画面外実行、スクリーンショットレポートを追加
