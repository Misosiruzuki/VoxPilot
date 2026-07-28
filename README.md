# VoxPilot 1.2.1

VoxPilot（ボックスパイロット）は、Forge 1.20.1（Forge 47.x）のMDKをJSONシナリオで実プレイ自動テストする単体Javaアプリです。`VoxPilot.jar` の中に開発環境用Forgeエージェントを内蔵しているため、対象Modへソースをコピーする必要はありません。

## すぐ試す

Java 17が必要です。コマンドプロンプトから対象MDKを指定して実行できます。

```text
run-example.cmd C:\path\to\forge-1.20.1-mdk
```

コマンドラインからは次のように実行します。

```powershell
java -jar 'VoxPilot.jar' run `
  --project 'C:\path\to\forge-1.20.1-mdk' `
  --scenario 'examples\walk-third-person.json'
```

対象MDKの `run/voxpilot-reports/日時/` に、`report.html`、全フレームの `frames.jsonl`、PNG、クライアント／サーバーログが作られます。既存レポートのHTMLだけを再生成する場合は次を使います。

```powershell
java -jar VoxPilot.jar report --dir 'C:\path\to\report'
```

## デフォルト軽量化構成

`run`を実行すると、Forge 1.20.1用の次の構成を各作者の公式配布先から自動取得します。
ダウンロードはSHA-512で検証され、`run/voxpilot-cache/performance/`へ保存されるため、
2回目以降はネットワークへ再取得せずに使えます。

| 対象 | 導入内容 |
|---|---|
| クライアント・サーバー | ModernFix 5.27.66、FerriteCore 6.0.1 |
| サーバー・シングルプレイ | ServerCore 1.5.2 |
| クライアント | Embeddium 0.3.31、ImmediatelyFast 1.2.4、Entity Culling 1.7.4（いずれも1.20.1ネイティブ版） |
| リソースパック | F8thful v6.0（8x8、Minecraft 1.20.1） |

F8thfulは`run/resourcepacks/F8thful-v6.0.zip`へ配置され、`run/options.txt`の
`resourcePacks`へ追加されるためデフォルトで有効になります。既存のリソースパック指定は保持します。

### 最大軽量化プロファイル

初回実行時に、クライアントとサーバーへ次の軽量化設定も読み込みます。

- 描画距離6、シミュレーション距離5、Fast描画、最小パーティクル、雲・影・ビネット・AO停止
- Embeddiumの可視面・フォグ・エンティティカリング、コンパクト頂点、遅延チャンク更新を有効化
- Entity Cullingのブロックエンティティ／葉越しカリングを強化し、判定頻度と追跡距離を軽量化
- FerriteCoreの省メモリブロックステート表現を有効化
- ServerCoreの動的描画／シミュレーション距離、スポーンチャンク停止、XP・アイテム統合、村人軽量化、Activation Rangeを有効化
- ローカル専用サーバーではネットワーク圧縮を省略してCPU負荷を削減

設定はプロファイルごとに一度だけ適用します。適用前のファイルは
`run/voxpilot-config-backup/`（専用サーバーは`run-server/voxpilot-config-backup/`）へ退避されます。
以後は利用者が変更した設定をVoxPilotが上書きしません。再適用したい場合は、該当する
`voxpilot-performance-*-v1.applied`を削除してください。

最大軽量化のため、ServerCoreのActivation Range、スポーン頻度、流体tick最適化など、
遠距離Mob・装置・流体の挙動をわずかに変える設定を含みます。完全なバニラ同等性を検査する場合は
バックアップへ戻すか、該当設定だけ無効化してください。ImmediatelyFastのエラーチェック停止など、
描画破損を起こしやすい実験設定はテストの信頼性を優先して有効化していません。

専用サーバーを同時起動するシナリオでは、サーバーを`run-server/`、クライアントを`run/`へ分離します。
共通・サーバー用Modだけを`run-server/mods`へ、共通・クライアント用Modだけを`run/mods`へ導入するため、
Embeddiumなどを専用サーバーへ読み込ませず、実行中のmodsディレクトリも変更しません。導入ファイルの配布元とライセンスは
[`THIRD-PARTY.md`](THIRD-PARTY.md)に記載しています。第三者ファイルはVoxPilot.jarへ同梱していません。

配布用Modの難読化名は、対象MDKのForgeGradleを一時初期化スクリプトから呼び出して
一時出力から`.gradle/voxpilot-stable/performance-remapped/`へ退避します。対象の`build.gradle`は変更しません。
BadOptimizations 2.4.1はこの開発用変換後に一部Mixinが不正なスタックフレームを生成するため除外しています。
VoxPilotでは実測でエラーなく完走できた組み合わせだけを採用します。

## JSONシナリオ

主なフィールドは次の通りです。

| フィールド | 内容 |
|---|---|
| `launchServer` | localhost専用の平坦ワールドサーバーも起動する |
| `totalFrames` | 計測するレンダーフレーム数 |
| `captureEvery` | NフレームごとにPNG保存。`1`なら全フレーム、`0`なら無効 |
| `warmupFrames` | 接続と場面コマンド後、計測開始まで待つフレーム数 |
| `display.background` | ウィンドウを `(-32000,-32000)` へ移して作業画面から外す |
| `commands` | プレイヤー参加後に順番に実行するMinecraftコマンド |
| `actions` | `frame`、または`fromFrame`～`toFrame`に適用する操作 |
| `closeClient` | 完走時にMinecraftを閉じる |

アクションでは `camera`（`first`、`third_back`、`third_front`）、F3表示を内部から切り替える
`debugScreen`、`yaw`、`pitch`、`deltaYaw`、`deltaPitch`、および次のキーを指定できます。

```json
{
  "fromFrame": 30,
  "toFrame": 69,
  "camera": "third_back",
  "keys": {
    "forward": true,
    "back": false,
    "left": false,
    "right": false,
    "jump": false,
    "sneak": false,
    "sprint": true,
    "attack": false,
    "use": false
  }
}
```

`commands` では、たとえば `tp Dev 0 -60 0`、`fill ...`、`summon ...`、`time set day` を使って実際のテスト場面を構築できます。VoxPilotが作るoffline localhostサーバー上の `Dev` だけにコマンド権限を付けます。外部サーバーへは接続しません。

## 作業を邪魔しない仕組み

VoxPilotはWindowsの `SendInput` やマウス移動を使いません。localhostエージェントがMinecraft内部の `KeyMapping` をレンダーフレーム境界で直接更新します。また `pauseOnLostFocus=false` とし、GLFWウィンドウを画面外へ移します。これにより、Minecraftへフォーカスを渡さず、ユーザーの実キーボードとマウスを占有せずに描画と操作を続けられます。

仮想モニター用ドライバーは管理者権限、署名済みドライバー、PCごとのGPU設定が必要になるため、標準方式には採用しませんでした。必要になった場合だけ追加の隔離層として使えます。

## 検討した10方式

1. **localhostエージェント＋Minecraft内部入力（採用）** — フレーム同期が正確で、フォーカス不要。
2. **GLFWウィンドウを画面外へ移動（採用）** — ドライバー不要で描画を継続できる。
3. GLFW hidden window — 環境によって描画停止するため標準採用せず。
4. 仮想ディスプレイドライバー — 隔離は強いが導入コストと管理者権限が重い。
5. Windows仮想デスクトップ — 表示整理には有効だが、入力フォーカスの完全分離ではない。
6. 別Windowsユーザー／RDPセッション — 隔離は強いがGPU・セッション制約がある。
7. Hyper-V/VM内実行 — 再現性は高いがGPU性能と起動時間で不利。
8. Forge GameTestのみ — ロジック検証は高速だが、実クライアント描画や三人称を検査できない。
9. OSレベルのSendInput/AutoHotkey — 実装は簡単だがユーザー操作を奪うため不採用。
10. 録画後のリプレイ解析 — 回帰比較向きだが、その場の操作分岐と修正ループが遅い。

最適解として1と2を組み合わせました。高速なロジックテストにはForge GameTestも併用し、見た目・操作・カメラの検証だけVoxPilotへ寄せると、Mod開発全体のテスト時間を最小化できます。

## 実測済み

付属シナリオをForge 1.20.1-47.4.10 MDKで実行し、次を確認しました。

- JSONの場面コマンド5件（時刻、天候、テレポート、81ブロックの床、目標ブロック）を実行
- 100/100フレーム完走、完了イベント受信
- PNG 10枚生成（`captureEvery: 10`）
- 一人称30、三人称背面60、三人称前面10フレーム
- 水平移動44.166ブロックをテレメトリで確認
- 終了後にクライアント／サーバーのプロセスツリーを停止

## 対応範囲

現バージョンはForge 1.20.1 / Forge 47.xの**開発用MDK**に対応します。他の1.20.1 Modでは `--project` をそのMDKルートへ変えるだけです。製品版Minecraftランチャー、NeoForge、Fabric、別Minecraftバージョンには、それぞれ対応エージェントのビルドが必要です。

VoxPilotは対象MDKの `run/mods/voxpilot-agent-1.0.0.jar`、既定軽量化Mod、
`run/resourcepacks/F8thful-v6.0.zip`、`run/eula.txt`、`run/server.properties`、`run/ops.json`、
テストワールドとレポートを作成します。Modの `src/` や `build.gradle` は変更しません。
