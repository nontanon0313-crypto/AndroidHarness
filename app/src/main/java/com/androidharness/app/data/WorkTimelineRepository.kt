package com.androidharness.app.data

import com.androidharness.app.data.db.HarnessDao
import com.androidharness.app.data.db.WorkEventEntity
import com.androidharness.app.data.db.WorkSessionEntity
import com.androidharness.app.data.db.WorkStateEntity
import com.androidharness.app.data.db.JudgmentEntity
import com.androidharness.app.core.JstTimeUtils

/**
 * 作業タイムラインの CRUD リポジトリ。
 *
 * 作業イベント、作業セッション、作業状態、判断履歴を保存・取得する。
 * 発生日時と記録日時を明確に区別し、取得できない日時は null または不明として扱う。
 */
class WorkTimelineRepository(private val dao: HarnessDao) {

    // ========================================================================
    // 作業イベント
    // ========================================================================

    /**
     * 作業イベントを記録する。
     *
     * @param sessionId 関連する作業セッション ID
     * @param eventType イベントタイプ（例: "started", "planned", "executed", "paused", "resumed", "finished", "error", "verified"）
     * @param description イベントの説明
     * @param eventTimestamp イベント発生時刻（UTC エポックミリ秒）。null の場合は現在時刻を使用。
     *                       取得できない場合は、recordTimestamp と同じ値を使用する。
     * @param relatedMessageId 関連するメッセージ ID（オプション）
     * @param metadata 追加メタデータ JSON（オプション）
     * @return 生成されたイベント ID
     */
    suspend fun recordWorkEvent(
        sessionId: String,
        eventType: String,
        description: String,
        eventTimestamp: Long? = null,
        relatedMessageId: String? = null,
        metadata: String? = null,
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        val now = JstTimeUtils.nowEpochMillis()
        // 発生日時が取得できない場合も、記録日時を発生日時として扱わない。
        // 両者は別々に保存する。eventTimestamp が null の場合は now を使用（不明として記録）。
        val ts = eventTimestamp ?: now

        return run {
            dao.insertWorkEvent(
                WorkEventEntity(
                    id = id,
                    sessionId = sessionId,
                    eventType = eventType,
                    eventTimestamp = ts,
                    recordTimestamp = now,
                    description = description,
                    relatedMessageId = relatedMessageId,
                    metadata = metadata,
                )
            )
            id
        }
    }

    suspend fun getWorkEvents(sessionId: String): List<WorkEventEntity> =
        dao.getWorkEvents(sessionId)

    suspend fun getWorkEventsByTimeRange(fromTs: Long, toTs: Long): List<WorkEventEntity> =
        dao.getWorkEventsByTimeRange(fromTs, toTs)

    // ========================================================================
    // 作業セッション
    // ========================================================================

    /**
     * 作業セッションを開始する。
     * 中断・完了済みのセッションを再開する場合は、[resumeWorkSession] を使用すること。
     */
    suspend fun startWorkSession(chatSessionId: String?, description: String? = null): String {
        val id = java.util.UUID.randomUUID().toString()
        val now = JstTimeUtils.nowEpochMillis()
        return run {
            dao.insertWorkSession(
                WorkSessionEntity(
                    id = id,
                    chatSessionId = chatSessionId,
                    status = "started",
                    startedAt = now,
                    pausedAt = null,
                    resumedAt = null,
                    finishedAt = null,
                    description = description,
                )
            )
            dao.insertWorkState(
                WorkStateEntity(
                    sessionId = id,
                    currentPhase = "started",
                    lastJudgment = null,
                    currentStatus = "作業開始",
                    latestResult = null,
                    updatedAt = now,
                )
            )
            id
        }
    }

    /**
     * 作業セッションを中断する。
     * 完了済みのセッションは中断できない。
     */
    suspend fun pauseWorkSession(sessionId: String) {
        val now = JstTimeUtils.nowEpochMillis()
        run {
            val current = dao.getWorkSession(sessionId) ?: return
            // 完了済みは中断不可
            if (current.status == "finished") return
            dao.insertWorkSession(
                current.copy(
                    status = "paused",
                    pausedAt = now,
                    // 開始時刻は上書きしない
                )
            )
            dao.insertWorkState(
                WorkStateEntity(
                    sessionId = sessionId,
                    currentPhase = "paused",
                    currentStatus = "作業中断",
                    updatedAt = now,
                )
            )
        }
    }

    /**
     * 作業セッションを再開する。
     * 開始時刻は上書きしない。完了済みのセッションは再開できない。
     */
    suspend fun resumeWorkSession(sessionId: String) {
        val now = JstTimeUtils.nowEpochMillis()
        run {
            val current = dao.getWorkSession(sessionId) ?: return
            // 完了済みは再開不可
            if (current.status == "finished") return
            dao.insertWorkSession(
                current.copy(
                    status = "resumed",
                    resumedAt = now,
                    // 開始時刻は上書きしない
                )
            )
            dao.insertWorkState(
                WorkStateEntity(
                    sessionId = sessionId,
                    currentPhase = "resumed",
                    currentStatus = "作業再開",
                    updatedAt = now,
                )
            )
        }
    }

    /**
     * 作業セッションを完了する。
     * 既に完了済みの場合は何もしない。
     */
    suspend fun finishWorkSession(sessionId: String) {
        val now = JstTimeUtils.nowEpochMillis()
        run {
            val current = dao.getWorkSession(sessionId) ?: return
            if (current.status == "finished") return
            dao.insertWorkSession(
                current.copy(
                    status = "finished",
                    finishedAt = now,
                )
            )
            dao.insertWorkState(
                WorkStateEntity(
                    sessionId = sessionId,
                    currentPhase = "finished",
                    currentStatus = "作業完了",
                    updatedAt = now,
                )
            )
        }
    }

    suspend fun getWorkSession(id: String): WorkSessionEntity? =
        dao.getWorkSession(id)

    suspend fun getWorkSessionsByChat(chatSessionId: String): List<WorkSessionEntity> =
        dao.getWorkSessionsByChat(chatSessionId)

    // ========================================================================
    // 作業状態
    // ========================================================================

    /**
     * 作業状態を更新する。指定されていないフィールドは既存値を保持する。
     */
    suspend fun updateWorkState(
        sessionId: String,
        currentPhase: String? = null,
        lastJudgment: String? = null,
        currentStatus: String? = null,
        latestResult: String? = null,
    ) {
        val now = JstTimeUtils.nowEpochMillis()
        run {
            val current = dao.getWorkState(sessionId)
            dao.insertWorkState(
                WorkStateEntity(
                    sessionId = sessionId,
                    currentPhase = currentPhase ?: current?.currentPhase,
                    lastJudgment = lastJudgment ?: current?.lastJudgment,
                    currentStatus = currentStatus ?: current?.currentStatus,
                    latestResult = latestResult ?: current?.latestResult,
                    updatedAt = now,
                )
            )
        }
    }

    suspend fun getWorkState(sessionId: String): WorkStateEntity? =
        dao.getWorkState(sessionId)

    // ========================================================================
    // 判断履歴
    // ========================================================================

    /**
     * 判断履歴を記録する。
     *
     * @param sessionId 関連する作業セッション ID
     * @param judgmentText 判断内容
     * @param judgmentAt 判断時刻（UTC エポックミリ秒）。null の場合は現在時刻を使用。
     * @return 生成された判断 ID
     */
    suspend fun recordJudgment(
        sessionId: String,
        judgmentText: String,
        judgmentAt: Long? = null,
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        val now = JstTimeUtils.nowEpochMillis()
        val ts = judgmentAt ?: now

        return run {
            dao.insertJudgment(
                JudgmentEntity(
                    id = id,
                    sessionId = sessionId,
                    judgmentText = judgmentText,
                    judgmentAt = ts,
                    recordTimestamp = now,
                )
            )
            id
        }
    }

    suspend fun getJudgments(sessionId: String): List<JudgmentEntity> =
        dao.getJudgments(sessionId)

    suspend fun getJudgmentsByTimeRange(fromTs: Long, toTs: Long): List<JudgmentEntity> =
        dao.getJudgmentsByTimeRange(fromTs, toTs)

    // ========================================================================
    // 時系列統合検索
    // ========================================================================

    /**
     * 指定時間範囲内のメッセージ・作業イベント・判断を時刻順に統合して取得する。
     */
    suspend fun getTimeline(fromTs: Long, toTs: Long): List<HarnessDao.TimelineEntry> =
        dao.getTimelineByTimeRange(fromTs, toTs)
}
