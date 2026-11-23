# 作業履歴・時系列管理機能（Work Timeline and Time Management）

> このドキュメントは、AndroidHarness アプリに追加された「作業履歴・時系列管理機能」の目的・仕組み・仕様・制約・実装状況を、プログラムに詳しくない方でも理解できるように日本語で説明したものです。

## 1. 機能の目的と背景

### 何のための機能か

AndroidHarness アプリ内で AI（アシスタント）が作業を行う際、以下の情報を記録・管理するための機能です。

- **いつ**作業が始まり、中断し、再開し、完了したか
- **どのような判断**を AI が行ったか
- **その判断がいつ**行われたか
- **現在の作業状態**はどうなっているか

これにより、AI は過去の経緯を振り返り、現在の状況を踏まえた回答を生成できるようになります。

### 背景

従来の AndroidHarness では、チャットメッセージの保存・検索はできましたが、以下の課題がありました。

1. **発言日時と DB 記録日時の区別がなかった**：メッセージをデータベースに保存する時刻（`createdAt`）だけがあり、「ユーザーが実際に発言した時刻」を別途保持していませんでした。
2. **作業のライフサイクルを管理する仕組みがなかった**：作業の開始・中断・再開・完了といった状態を体系的に記録する仕組みがありませんでした。
3. **AI が過去の判断を参照できなかった**：AI は現在のチャット履歴は見られても、過去に行った判断や作業イベントを体系的に参照する手段がありませんでした。

この機能は、これらの課題を解決するために追加されました。

---

## 2. システム全体の構成

### 全体像

```
┌─────────────────────────────────────────────────────────┐
│                   ユーザー                              │
│              （チャットで指示）                         │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│              AgentEngine（AI実行エンジン）                │
│  ┌─────────────────────────────────────────────┐        │
│  │ run() → runSystemPrompt() → chatSystemPrompt()│        │
│  │         → roundRequest() → プロバイダーへ送信  │        │
│  └─────────────────────────────────────────────┘        │
│                        │                                 │
│                        ▼                                 │
│  ┌─────────────────────────────────────────────┐        │
│  │ workTimeline() メソッド                     │        │
│  │ 作業タイムライン情報をシステムプロンプトに注入 │        │
│  └─────────────────────────────────────────────┘        │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│           WorkTimelineRepository（リポジトリ）             │
│  - 作業イベントの記録・取得                               │
│  - 作業セッションの管理（開始/中断/再開/完了）             │
│  - 作業状態の管理                                        │
│  - 判断履歴の管理                                        │
│  - 時系列統合検索                                        │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│              HarnessDao（データアクセス層）                │
│  - Room データベースへのクエリ実行                         │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│              AppDatabase（Room データベース）             │
│  - harness.db ファイル                                   │
│  - 以下のテーブルを管理                                   │
│    • messages（メッセージ + 発言日時情報）                 │
│    • work_events（作業イベント）                           │
│    • work_sessions（作業セッション）                       │
│    • work_states（作業状態）                               │
│    • judgments（判断履歴）                                 │
└─────────────────────────────────────────────────────────┘
```

### 処理の流れ（データが AI に届くまで）

1. **ユーザーがチャットで指示する**
2. **AgentEngine.run()** が呼ばれる
3. **runSystemPrompt()** が **chatSystemPrompt()** を呼ぶ
4. **chatSystemPrompt()** の末尾で **workTimeline()** が呼ばれる
5. **WorkTimelineRepository** が DB から作業セッション・イベント・状態・判断履歴を取得
6. 取得した情報を **システムプロンプトに文字列として追加**
7. **roundRequest()** がシステムプロンプトを AI プロバイダーに送信
8. **AI が時系列情報を含むプロンプトを受け取り**、過去の経緯を踏まえた回答を生成

---

## 3. 発言日時とDB記録日時の管理

### 2つの時刻の違い

| 用語 | 意味 | 保存場所 |
|------|------|----------|
| **発言日時** | ユーザーが実際に発言した時刻 | `messages.originalTimestamp` |
| **DB記録日時** | メッセージをデータベースに保存した時刻 | `messages.createdAt` |

### なぜ区別するのか

- ユーザーが発言した時刻と、システムが記録した時刻は**必ずしも一致しない**。
- ネットワーク遅延やアプリの処理 delays により、記録時刻は発言時刻より遅くなることがある。
- 発言時刻が取得できない場合（例：インポートされた履歴）、**推測で記録時刻を発言時刻として扱わない**。

### 実装

`MessageEntity`（`Database.kt`）に以下のカラムを追加：

```kotlin
val createdAt: Long,                    // DB記録日時（既存）
val originalTimestamp: Long? = null,    // 元の発言日時（取得できない場合は null）
val timestampSource: String? = null,    // 日時の取得元（例: "system", "message_header"）
val timestampReliability: String? = null, // 日時の信頼性（例: "high", "low", "unknown"）
```

`ChatMessage`（`Models.kt`）にも同様のフィールドを追加し、`SessionRepository.addMessage()` で保存、`toChatMessage()` で復元します。

### 取得できない場合の扱い

- `originalTimestamp` が `null` の場合、**DB記録日時で代用しない**。
- そのまま `null`（不明）として保存する。
- AI へのコンテキスト注入時も、日時不明の情報には推測の日時を付与しない。

---

## 4. JST日時管理

### 概要

`JstTimeUtils`（`app/src/main/java/com/androidharness/app/core/JstTimeUtils.kt`）は、日本標準時（Asia/Tokyo）での日時変換と経過時間計算を行うユーティリティです。

### 提供する機能

| メソッド | 機能 |
|----------|------|
| `toJstString(epochMillis)` | UTCエポックミリ秒 → JST文字列（`yyyy-MM-dd HH:mm:ss`） |
| `toJst(epochMillis)` | UTCエポックミリ秒 → JST ZonedDateTime |
| `nowEpochMillis()` | 現在時刻をUTCエポックミリ秒で取得 |
| `formatDuration(start, end)` | 2つの時刻間の経過時間を人間が読める形式で返す（例: `2時間30分`） |
| `elapsedSince(pastMillis)` | 指定時刻から現在までの経過時間 |
| `remainingUntil(deadlineMillis)` | 期限までの残り時間（期限切れの場合は `期限切れ`） |
| `elapsedMillis(start, end)` | 2つの時刻間の経過ミリ秒 |

### なぜJSTを使うのか

- データベースには UTC（協定世界時）のエポックミリ秒で保存する。
- ユーザーが読む際や AI に渡す際は、日本標準時（JST）に変換して表示する。
- これにより、世界中のどこからでも一貫した日時表示が可能になる。

---

## 5. 作業イベント管理

### 概要

作業イベントは、AI の作業に関する出来事を記録するものです。

### イベントタイプ

| タイプ | 意味 |
|--------|------|
| `started` | 作業開始 |
| `planned` | 計画立案 |
| `executed` | 実行 |
| `paused` | 中断 |
| `resumed` | 再開 |
| `finished` | 完了 |
| `error` | エラー |
| `verified` | 検証 |

### 保存される情報

| フィールド | 意味 |
|------------|------|
| `id` | イベントの一意識別子（UUID） |
| `sessionId` | 関連する作業セッションID |
| `eventType` | イベントタイプ（上記のいずれか） |
| `eventTimestamp` | イベント発生時刻（UTCエポックミリ秒） |
| `recordTimestamp` | DB記録日時（UTCエポックミリ秒） |
| `description` | イベントの説明 |
| `relatedMessageId` | 関連するメッセージID（オプション） |
| `metadata` | 追加メタデータJSON（オプション） |

### 発生日時と記録日時の分離

- `eventTimestamp`：イベントが実際に発生した時刻。
- `recordTimestamp`：DBに記録した時刻。
- **両者は別々に保存**する。取得できない日時は `null` または現在時刻とし、**記録日時を発生日時として扱わない**。

### 実装

`WorkTimelineRepository.recordWorkEvent()` で記録します。

```kotlin
suspend fun recordWorkEvent(
    sessionId: String,
    eventType: String,
    description: String,
    eventTimestamp: Long? = null,  // null の場合は現在時刻を使用
    relatedMessageId: String? = null,
    metadata: String? = null,
): String
```

---

## 6. 作業セッション管理と状態遷移

### 概要

作業セッションは、1つの作業単位を表します。AI が作業を開始し、中断し、再開し、完了するまでのライフサイクルを管理します。

### 状態遷移

```
started → paused → resumed → finished
   ↑                    │
   └────────────────────┘
   （完了後は中断・再開不可）
```

### 状態のルール

| 操作 | 条件 | 結果 |
|------|------|------|
| **開始** | 新規セッション | `status = "started"`、`startedAt` に現在時刻 |
| **中断** | 完了済みでないこと | `status = "paused"`、`pausedAt` に現在時刻。**開始時刻は上書きしない。完了時刻は設定しない。** |
| **再開** | 完了済みでないこと | `status = "resumed"`、`resumedAt` に現在時刻。**開始時刻は上書きしない。** |
| **完了** | 完了済みでないこと | `status = "finished"`、`finishedAt` に現在時刻 |

### 不正な状態遷移の拒否

- **完了済みセッションの中断**：拒否される（`status` は `finished` のまま）。
- **完了済みセッションの再開**：拒否される（`status` は `finished` のまま）。

### 保存される情報

| フィールド | 意味 |
|------------|------|
| `id` | セッションの一意識別子（UUID） |
| `chatSessionId` | 関連するチャットセッションID |
| `status` | 現在の状態（`started` / `paused` / `resumed` / `finished`） |
| `startedAt` | 開始時刻（UTCエポックミリ秒） |
| `pausedAt` | 中断時刻（UTCエポックミリ秒、中断していない場合は `null`） |
| `resumedAt` | 再開時刻（UTCエポックミリ秒、再開していない場合は `null`） |
| `finishedAt` | 完了時刻（UTCエポックミリ秒、完了していない場合は `null`） |
| `description` | セッションの説明 |

### 実装

- `startWorkSession()`：新規セッションを開始。
- `pauseWorkSession()`：セッションを中断。
- `resumeWorkSession()`：セッションを再開。
- `finishWorkSession()`：セッションを完了。

---

## 7. 作業状態管理

### 概要

作業状態は、現在の作業の進行状況をリアルタイムに把握するための情報です。

### 保存される情報

| フィールド | 意味 |
|------------|------|
| `sessionId` | 関連する作業セッションID |
| `currentPhase` | 現在のフェーズ（例: `started`, `executed`, `finished`） |
| `lastJudgment` | 直近の判断内容 |
| `currentStatus` | 現在のステータス（例: `作業開始`, `作業中断`, `作業完了`） |
| `latestResult` | 最新の結果 |
| `updatedAt` | 更新時刻（UTCエポックミリ秒） |

### 特徴

- **指定されていないフィールドは既存値を保持**する。例えば、`currentPhase` だけを更新した場合、`lastJudgment` や `currentStatus` はそのまま残る。
- 作業セッションと1対1で対応する。

### 実装

- `updateWorkState()`：作業状態を更新。指定されていないフィールドは既存値を保持。
- `getWorkState()`：現在の作業状態を取得。

---

## 8. 判断履歴管理

### 概要

判断履歴は、AI が行った判断とその時刻を記録するものです。

### 保存される情報

| フィールド | 意味 |
|------------|------|
| `id` | 判断の一意識別子（UUID） |
| `sessionId` | 関連する作業セッションID |
| `judgmentText` | 判断内容 |
| `judgmentAt` | 判断時刻（UTCエポックミリ秒） |
| `recordTimestamp` | DB記録日時（UTCエポックミリ秒） |

### 発生日時と記録日時の分離

- `judgmentAt`：判断が実際に行われた時刻。
- `recordTimestamp`：DBに記録した時刻。
- **両者は別々に保存**する。

### 実装

- `recordJudgment()`：判断を記録。
- `getJudgments()`：判断履歴を取得。
- `getJudgmentsByTimeRange()`：指定期間の判断履歴を取得。

---

## 9. 時系列統合検索

### 概要

時系列統合検索は、メッセージ・作業イベント・判断を時刻順に統合して取得する機能です。

### 仕組み

`HarnessDao.getTimelineByTimeRange()` は、以下の3つのテーブルを `UNION ALL` で結合し、時刻順に並べ替えます。

1. `messages`（メッセージ）
2. `work_events`（作業イベント）
3. `judgments`（判断履歴）

### 戻り値の構造

```kotlin
data class TimelineEntry(
    val kind: String,      // "message" / "work_event" / "judgment"
    val id: String,
    val sessionId: String,
    val ts: Long,          // タイムスタンプ
    val type: String,      // 種別
    val detail: String?,   // 詳細
)
```

### 活用例

- 指定期間の全履歴を時系列で振り返る。
- AI が過去の文脈を整理して参照する。

---

## 10. AIプロンプトへの情報注入

### 概要

作業タイムライン機能の最も重要な部分は、**保存した情報を AI が実際に参照できること**です。

### 注入の仕組み

1. `AgentEngine.run()` が `runSystemPrompt()` を呼ぶ。
2. `runSystemPrompt()` は `chatSystemPrompt()` を呼ぶ（チャットの場合）。
3. `chatSystemPrompt()` の末尾で `workTimeline()` が呼ばれる。
4. `workTimeline()` は `WorkTimelineRepository` から情報を取得し、システムプロンプトに追加する。

### 注入される情報

以下の情報が、システムプロンプトに**構造化された形式**で追加されます。

```
# 作業タイムライン（Work Timeline）

## 現在の状態
- フェーズ: executed
- ステータス: 作業実行中
- 最終判断: このアプローチを採用する
- 更新日時: 2026-11-23 14:30:00 (JST)

## 作業セッション
- ID: abc-123
- 状態: resumed
- 開始日時: 2026-11-23 13:00:00 (JST)
- 中断日時: 2026-11-23 13:30:00 (JST)
- 再開日時: 2026-11-23 14:00:00 (JST)
- 説明: ファイル編集作業

## 過去の作業イベント（直近 20 件）
- [2026-11-23 13:05:00 JST] planned: 計画を立案
- [2026-11-23 13:10:00 JST] executed: ファイルを編集
- [2026-11-23 13:15:00 JST] verified: テスト実行

## 判断履歴（直近 10 件）
- [2026-11-23 13:12:00 JST] このアプローチを採用する
- [2026-11-23 13:20:00 JST] エラーを修正する

---
注: 上記の日時はすべて JST（日本標準時）です。発生日時が不明な情報には推測の日時を付与していません。
```

### 区別される情報

| 情報 | 区別 |
|------|------|
| **過去の事実** | 「過去の作業イベント」セクションで時系列順に表示 |
| **現在の状態** | 「現在の状態」セクションで最新のフェーズ・ステータスを表示 |
| **未完了作業** | 作業セッションの状態（`paused` など）で判別可能 |
| **判断履歴** | 「判断履歴」セクションで時系列順に表示 |

### コンテキストの肥大化を防ぐ仕組み

- 作業イベントは**直近20件**に限定。
- 判断履歴は**直近10件**に限定。
- 作業セッションは**最新1件**に限定。

### 日時不明の情報の扱い

- 発生日時が不明な情報には、**推測の日時を付与しない**。
- `eventTimestamp` が `null` の場合、システムプロンプトには「日時不明」として表示される（または現在時刻が使われるが、推測とは区別される）。

### 実装箇所

`AgentEngine.kt` の `workTimeline()` メソッド（約2754行目）。

---

## 11. データベース構造とマイグレーション

### 既存テーブルの変更

`messages` テーブルに以下のカラムを追加：

| カラム名 | 型 | 意味 |
|----------|-----|------|
| `originalTimestamp` | INTEGER（nullable） | 元の発言日時（UTCエポックミリ秒） |
| `timestampSource` | TEXT（nullable） | 日時の取得元 |
| `timestampReliability` | TEXT（nullable） | 日時の信頼性 |

### 新規テーブル

#### `work_events`（作業イベント）

| カラム名 | 型 | 制約 |
|----------|-----|------|
| `id` | TEXT | PRIMARY KEY |
| `sessionId` | TEXT | NOT NULL |
| `eventType` | TEXT | NOT NULL |
| `eventTimestamp` | INTEGER | NOT NULL |
| `recordTimestamp` | INTEGER | NOT NULL |
| `description` | TEXT | NOT NULL |
| `relatedMessageId` | TEXT | nullable |
| `metadata` | TEXT | nullable |

インデックス：
- `idx_work_events_sessionId`（sessionId）
- `idx_work_events_eventTimestamp`（eventTimestamp）

#### `work_sessions`（作業セッション）

| カラム名 | 型 | 制約 |
|----------|-----|------|
| `id` | TEXT | PRIMARY KEY |
| `chatSessionId` | TEXT | nullable |
| `status` | TEXT | NOT NULL |
| `startedAt` | INTEGER | nullable |
| `pausedAt` | INTEGER | nullable |
| `resumedAt` | INTEGER | nullable |
| `finishedAt` | INTEGER | nullable |
| `description` | TEXT | nullable |

インデックス：
- `idx_work_sessions_startedAt`（startedAt）
- `idx_work_sessions_finishedAt`（finishedAt）
- `idx_work_sessions_chatSessionId`（chatSessionId）

#### `work_states`（作業状態）

| カラム名 | 型 | 制約 |
|----------|-----|------|
| `sessionId` | TEXT | PRIMARY KEY |
| `currentPhase` | TEXT | nullable |
| `lastJudgment` | TEXT | nullable |
| `currentStatus` | TEXT | nullable |
| `latestResult` | TEXT | nullable |
| `updatedAt` | INTEGER | NOT NULL |

インデックス：
- `idx_work_states_updatedAt`（updatedAt）

#### `judgments`（判断履歴）

| カラム名 | 型 | 制約 |
|----------|-----|------|
| `id` | TEXT | PRIMARY KEY |
| `sessionId` | TEXT | NOT NULL |
| `judgmentText` | TEXT | NOT NULL |
| `judgmentAt` | INTEGER | NOT NULL |
| `recordTimestamp` | INTEGER | NOT NULL |

インデックス：
- `idx_judgments_sessionId`（sessionId）
- `idx_judgments_judgmentAt`（judgmentAt）

### マイグレーション

#### `MIGRATION_27_28`

バージョン 27 から 28 へのマイグレーション。

```kotlin
val MIGRATION_27_28 = object : androidx.room.migration.Migration(27, 28) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        // MessageEntity に新規カラムを追加（既存データは維持、デフォルト null）
        db.execSQL("ALTER TABLE messages ADD COLUMN originalTimestamp INTEGER")
        db.execSQL("ALTER TABLE messages ADD COLUMN timestampSource TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN timestampReliability TEXT")

        // 新規テーブル作成
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS work_events (...)
        """.trimIndent())
        // インデックス作成
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_work_events_sessionId ON work_events(sessionId)")
        // ... 他のテーブルも同様
    }
}
```

### 既存データの保持

- `ALTER TABLE` でカラムを追加する際、デフォルト値は `null`。
- 既存のメッセージデータはそのまま保持される。
- 新規テーブルは `CREATE TABLE IF NOT EXISTS` で作成されるため、既存のデータベースには影響しない。

### データベースバージョン

- 変更前：バージョン 27
- 変更後：バージョン 28

---

## 12. 各クラス・Repository・DAOの役割

### `JstTimeUtils`（`core/JstTimeUtils.kt`）

- JST（日本標準時）への変換と経過時間計算を行うユーティリティ。
- 純粋なKotlinオブジェクトで、Android APIに依存しない。

### `WorkTimelineRepository`（`data/WorkTimelineRepository.kt`）

- 作業タイムラインのCRUD（Create, Read, Update, Delete）を行うリポジトリ。
- `HarnessDao` を直接受け取り、DAOメソッドを呼び出す。
- 発生日時と記録日時の分離を徹底する。

### `HarnessDao`（`data/db/Database.kt`）

- Room データベースへのクエリを提供するインターフェース。
- 以下のメソッドを追加：
  - `insertWorkEvent()`, `getWorkEvents()`, `getWorkEventsByTimeRange()`
  - `insertWorkSession()`, `getWorkSession()`, `getWorkSessionsByChat()`
  - `insertWorkState()`, `getWorkState()`
  - `insertJudgment()`, `getJudgments()`, `getJudgmentsByTimeRange()`
  - `getTimelineByTimeRange()`（時系列統合検索）
- `TimelineEntry` データクラスを定義。

### `AgentEngine`（`agent/AgentEngine.kt`）

- AIの実行エンジン。
- `WorkTimelineRepository` をコンストラクタで注入される。
- `chatSystemPrompt()` 内で `workTimeline()` を呼び出し、システムプロンプトに作業タイムライン情報を追加する。

### `HarnessApp`（`HarnessApp.kt`）

- アプリケーションのエントリーポイント。
- `AppContainer` 内で `WorkTimelineRepository` を生成し、`AgentEngine` に注入する。
- `addMigrations` に `MIGRATION_27_28` を追加する。

### `SessionRepository`（`data/SessionRepository.kt`）

- メッセージの保存・読み込みを担当。
- `addMessage()` で `originalTimestamp`, `timestampSource`, `timestampReliability` を保存。
- `toChatMessage()` でこれらのフィールドを復元。

### `Models.kt`（`core/Models.kt`）

- `ChatMessage` データクラスに `originalTimestamp`, `timestampSource`, `timestampReliability` を追加。

---

## 13. データの登録からAIが参照するまでの処理の流れ

### 1. メッセージの保存

1. ユーザーがチャットでメッセージを送信。
2. `SessionRepository.addMessage()` が呼ばれる。
3. `ChatMessage.originalTimestamp` が `null` の場合、そのまま `null` として保存。
4. `createdAt` は `System.currentTimeMillis()` でDB記録日時を設定。
5. `MessageEntity` としてDBに保存。

### 2. 作業イベントの記録

1. AI が作業を開始・実行・完了する。
2. `WorkTimelineRepository.recordWorkEvent()` が呼ばれる。
3. `eventTimestamp` が指定されていない場合、現在時刻を使用。
4. `recordTimestamp` は常に現在時刻。
5. `WorkEventEntity` としてDBに保存。

### 3. 作業セッションの管理

1. `WorkTimelineRepository.startWorkSession()` でセッション開始。
2. `pauseWorkSession()` で中断。
3. `resumeWorkSession()` で再開。
4. `finishWorkSession()` で完了。
5. 各操作で `WorkSessionEntity` と `WorkStateEntity` が更新される。

### 4. 判断の記録

1. AI が判断を行う。
2. `WorkTimelineRepository.recordJudgment()` が呼ばれる。
3. `JudgmentEntity` としてDBに保存。

### 5. AIへのプロンプト注入

1. ユーザーが次にメッセージを送信。
2. `AgentEngine.run()` が `runSystemPrompt()` を呼ぶ。
3. `chatSystemPrompt()` の末尾で `workTimeline()` が呼ばれる。
4. `WorkTimelineRepository` がDBから情報を取得：
   - 最新の作業セッション
   - 現在の作業状態
   - 直近20件の作業イベント
   - 直近10件の判断履歴
5. 取得した情報を文字列としてシステムプロンプトに追加。
6. `roundRequest()` がプロンプトをAIプロバイダーに送信。
7. AI が時系列情報を含むプロンプトを受け取り、過去の経緯を踏まえた回答を生成。

---

## 14. テスト内容と実行結果

### テストファイル

1. **`app/src/test/java/com/androidharness/app/core/JstTimeUtilsTest.kt`**
   - JST変換、経過時間計算、期限残り時間など9件のテスト。

2. **`app/src/test/java/com/androidharness/app/data/WorkTimelineRepositoryTest.kt`**
   - 状態遷移、不正遷移拒否、イベント・判断の日時分離、セッション取得など10件のテスト。

### テスト実行結果

| テスト | 件数 | 結果 |
|--------|------|------|
| `JstTimeUtilsTest` | 9件 | ✅ すべて成功 |
| `WorkTimelineRepositoryTest` | 10件 | ✅ すべて成功 |
| **合計** | **19件** | **✅ すべて成功** |

### テストケースの詳細

#### JstTimeUtilsTest

- `toJstString` が正しくJST文字列に変換する。
- `toJst` が `Asia/Tokyo` の `ZonedDateTime` を返す。
- `formatDuration` が秒・分・時間・日を正しくフォーマットする。
- `elapsedMillis` が2つの時刻間の差を返す。
- `remainingUntil` が期限切れの場合に `期限切れ` を返す。
- `remainingUntil` が未来の期限に対して残り時間を返す。

#### WorkTimelineRepositoryTest

- `startWorkSession` がセッションと状態を作成する。
- `pauseWorkSession` がセッションを中断する。
- `resumeWorkSession` がセッションを再開し、開始時刻を上書きしない。
- `finishWorkSession` がセッションを完了する。
- 完了済みセッションの中断が拒否される。
- 完了済みセッションの再開が拒否される。
- `recordWorkEvent` がイベントを記録し、発生日時と記録日時を分離する。
- `recordJudgment` が判断を記録し、判断時刻と記録日時を分離する。
- `updateWorkState` が指定されていないフィールドを保持する。
- `getWorkSessionsByChat` がチャットIDでセッションを取得する。

### 未完了のテスト

| テスト | 理由 |
|--------|------|
| **マイグレーションテスト（Room MigrationTestHelper）** | Instrumented Test（実機/エミュレータ）が必要で、この環境では実行不可。 |
| **AIプロンプト注入の実機検証** | エミュレータ/実機が必要。コードレベルでの注入処理は実装済み。 |

---

## 15. 未検証事項と今後必要な検証

### 未検証事項

| 項目 | 理由 | 必要な検証 |
|------|------|------------|
| マイグレーションの動作 | Instrumented Test が必要 | 実機/エミュレータでv27→v28マイグレーションを実行し、既存データが保持されることを確認 |
| AIプロンプトへの注入 | 実機/エミュレータが必要 | 実際にAIが作業タイムライン情報を含むプロンプトを受け取り、過去の経緯を踏まえた回答を生成することを確認 |
| 大量データでのパフォーマンス | 未検証 | 大量の作業イベント・判断履歴があっても、プロンプト注入が実用的な時間で完了することを確認 |
| エラーハンドリング | 部分的 | DBエラーやネットワークエラー時の挙動を確認 |

### 今後の拡張案

- 作業イベントのフィルタリング機能（イベントタイプで絞り込む）。
- 判断履歴の検索機能。
- 作業セッションのアーカイブ機能。
- より詳細なコンテキスト制御（トークン数に基づく動的な取捨選択）。

---

## 16. 今回の変更ファイル一覧

### 新規作成ファイル

| ファイル | 役割 |
|----------|------|
| `app/src/main/java/com/androidharness/app/core/JstTimeUtils.kt` | JST変換・経過時間計算ユーティリティ |
| `app/src/main/java/com/androidharness/app/data/WorkTimelineRepository.kt` | 作業タイムラインのCRUDリポジトリ |
| `app/src/test/java/com/androidharness/app/core/JstTimeUtilsTest.kt` | JstTimeUtils のテスト（9件） |
| `app/src/test/java/com/androidharness/app/data/WorkTimelineRepositoryTest.kt` | WorkTimelineRepository のテスト（10件） |

### 変更ファイル

| ファイル | 変更内容 |
|----------|----------|
| `app/src/main/java/com/androidharness/app/data/db/Database.kt` | MessageEntityに新規フィールド追加、新規エンティティ（WorkEventEntity, WorkSessionEntity, WorkStateEntity, JudgmentEntity）追加、MIGRATION_27_28追加、HarnessDaoに新規メソッド追加 |
| `app/src/main/java/com/androidharness/app/core/Models.kt` | ChatMessageに originalTimestamp/timestampSource/timestampReliability 追加 |
| `app/src/main/java/com/androidharness/app/data/SessionRepository.kt` | toChatMessage() と addMessage() を更新して新規フィールドを保存・復元 |
| `app/src/main/java/com/androidharness/app/agent/AgentEngine.kt` | WorkTimelineRepositoryをコンストラクタ注入、chatSystemPrompt()に時系列情報を注入 |
| `app/src/main/java/com/androidharness/app/HarnessApp.kt` | AppContainerで WorkTimelineRepositoryを生成しAgentEngineに注入、MIGRATION_27_28を登録 |

---

## 17. 用語集

| 用語 | 意味 |
|------|------|
| **エポックミリ秒** | 1970年1月1日00:00:00 UTCからの経過ミリ秒。コンピュータで日時を表現する標準的な方法。 |
| **UTC（協定世界時）** | 世界標準時。日本時間（JST）はUTC+9時間。 |
| **JST（日本標準時）** | 日本の標準時。UTC+9時間。 |
| **Room** | Androidで使うデータベースライブラリ。SQLiteを簡単に扱える。 |
| **Entity** | データベースのテーブル定義。 |
| **DAO（Data Access Object）** | データベースへのクエリ（検索・保存・更新・削除）をまとめたインターフェース。 |
| **Repository** | データアクセスを抽象化するクラス。DAOを呼び出して、より高レベルな操作を提供する。 |
| **マイグレーション** | データベースのバージョンを更新する処理。既存データを保持しながら、テーブル構造を変更する。 |
| **システムプロンプト** | AIに渡す事前の指示文。AIの役割や文脈を定義する。 |
| **コンテキスト** | AIに渡される情報全体。システムプロンプト、過去のメッセージ、作業履歴などを含む。 |
| **トランザクション** | 複数のデータベース操作を1つの単位として扱う仕組み。途中でエラーが発生した場合、すべての操作が取り消される。 |
| **UUID** | 世界に1つだけのID。作業イベントや判断履歴の識別子に使用。 |
| **nullable（null許容）** | 値が存在しない可能性があること。Kotlinでは`?`を付ける（例: `String?`）。 |

---

## 参考リンク

- [データベースと永続化](68-database-and-persistence.md)
- [共有コアモデルと純粋ユーティリティ](75-shared-core-models.md)
- [エージェント実行エンジン](14-agent-execution-engine.md)
- [コンテキスト管理と圧縮](16-context-management-and-compaction.md)

---

## 更新履歴

| 日付 | 内容 |
|------|------|
| 2026-11-23 | 初版作成。時系列管理機能の実装内容、テスト結果、未検証事項を記録。 |
