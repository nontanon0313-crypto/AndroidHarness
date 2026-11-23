package com.androidharness.app.data

import com.androidharness.app.data.db.HarnessDao
import com.androidharness.app.data.db.WorkEventEntity
import com.androidharness.app.data.db.WorkSessionEntity
import com.androidharness.app.data.db.WorkStateEntity
import com.androidharness.app.data.db.JudgmentEntity
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkTimelineRepositoryTest {

    /** HarnessDao をモック化。CheckpointStoreTest と同じ proxy パターン。 */
    private fun daoProxy(clazz: Class<HarnessDao>, call: (String, Array<out Any?>) -> Any?): HarnessDao {
        val handler: java.lang.reflect.InvocationHandler = { _, method, args ->
            call(method.name, args ?: emptyArray())
        }
        return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz), handler) as HarnessDao
    }

    private fun mockDao(
        sessions: MutableMap<String, WorkSessionEntity> = mutableMapOf(),
        states: MutableMap<String, WorkStateEntity> = mutableMapOf(),
        events: MutableList<WorkEventEntity> = mutableListOf(),
        judgments: MutableList<JudgmentEntity> = mutableListOf()
    ): HarnessDao = daoProxy(HarnessDao::class.java) { name, args ->
        when (name) {
            "insertWorkSession" -> {
                val s = args[0] as WorkSessionEntity
                sessions[s.id] = s
                Unit
            }
            "getWorkSession" -> sessions[args[0] as String]
            "getWorkSessionsByChat" -> sessions.values.filter { it.chatSessionId == args[0] }.toList()
            "insertWorkState" -> {
                val st = args[0] as WorkStateEntity
                states[st.sessionId] = st
                Unit
            }
            "getWorkState" -> states[args[0] as String]
            "insertWorkEvent" -> {
                events.add(args[0] as WorkEventEntity)
                Unit
            }
            "getWorkEvents" -> {
                val sid = args[0] as String
                events.filter { it.sessionId == sid }.sortedBy { it.eventTimestamp }.toList()
            }
            "getWorkEventsByTimeRange" -> {
                val from = args[0] as Long
                val to = args[1] as Long
                val sid = args[2] as String
                events.filter { it.sessionId == sid && it.eventTimestamp in from..to }.toList()
            }
            "insertJudgment" -> {
                judgments.add(args[0] as JudgmentEntity)
                Unit
            }
            "getJudgments" -> {
                val sid = args[0] as String
                judgments.filter { it.sessionId == sid }.sortedBy { it.judgmentAt }.toList()
            }
            "getJudgmentsByTimeRange" -> {
                val from = args[0] as Long
                val to = args[1] as Long
                judgments.filter { it.judgmentAt in from..to }.toList()
            }
            "getTimelineByTimeRange" -> emptyList<Any?>()
            else -> throw UnsupportedOperationException("Unexpected DAO method: $name")
        }
    }

    private fun newRepository(
        sessions: MutableMap<String, WorkSessionEntity> = mutableMapOf(),
        states: MutableMap<String, WorkStateEntity> = mutableMapOf(),
        events: MutableList<WorkEventEntity> = mutableListOf(),
        judgments: MutableList<JudgmentEntity> = mutableListOf()
    ): WorkTimelineRepository {
        val dao = mockDao(sessions, states, events, judgments)
        return WorkTimelineRepository(dao)
    }

    @Test
    fun startWorkSession_creates_session_and_state() = runBlocking {
        val repo = newRepository()
        val id = repo.startWorkSession("chat-1", description = "テスト作業")

        assertNotNull(id)
        val session = repo.getWorkSession(id)!!
        assertEquals("chat-1", session.chatSessionId)
        assertEquals("started", session.status)
        assertTrue(session.startedAt ?: 0 > 0)
        assertNull(session.pausedAt)
        assertNull(session.resumedAt)
        assertNull(session.finishedAt)

        val state = repo.getWorkState(id)!!
        assertEquals("started", state.currentPhase)
        assertEquals("作業開始", state.currentStatus)
    }

    @Test
    fun pauseWorkSession_transitions_to_paused() = runBlocking {
        val sessions = mutableMapOf<String, WorkSessionEntity>()
        val repo = newRepository(sessions = sessions)

        val id = repo.startWorkSession("chat-1")
        repo.pauseWorkSession(id)

        val session = repo.getWorkSession(id)!!
        assertEquals("paused", session.status)
        assertTrue(session.pausedAt ?: 0 > 0)
        // 開始時刻は上書きされない
        assertEquals(sessions[id]!!.startedAt, session.startedAt)
        assertNull(session.finishedAt)

        val state = repo.getWorkState(id)!!
        assertEquals("paused", state.currentPhase)
        assertEquals("作業中断", state.currentStatus)
    }

    @Test
    fun resumeWorkSession_transitions_to_resumed_without_overwriting_startedAt() = runBlocking {
        val sessions = mutableMapOf<String, WorkSessionEntity>()
        val repo = newRepository(sessions = sessions)

        val id = repo.startWorkSession("chat-1")
        repo.pauseWorkSession(id)
        repo.resumeWorkSession(id)

        val session = repo.getWorkSession(id)!!
        assertEquals("resumed", session.status)
        assertEquals(sessions[id]!!.startedAt, session.startedAt) // 開始時刻は上書きされない
        assertTrue(session.resumedAt ?: 0 > 0)
        assertNull(session.finishedAt)
    }

    @Test
    fun finishWorkSession_transitions_to_finished() = runBlocking {
        val repo = newRepository()
        val id = repo.startWorkSession("chat-1")
        repo.finishWorkSession(id)

        val session = repo.getWorkSession(id)!!
        assertEquals("finished", session.status)
        assertTrue(session.finishedAt ?: 0 > 0)
        assertNull(session.pausedAt)
    }

    @Test
    fun pauseFinishedWorkSession_is_rejected() = runBlocking {
        val repo = newRepository()
        val id = repo.startWorkSession("chat-1")
        repo.finishWorkSession(id)
        repo.pauseWorkSession(id)

        val session = repo.getWorkSession(id)!!
        assertEquals("finished", session.status) // 中断されない
    }

    @Test
    fun resumeFinishedWorkSession_is_rejected() = runBlocking {
        val repo = newRepository()
        val id = repo.startWorkSession("chat-1")
        repo.finishWorkSession(id)
        repo.resumeWorkSession(id)

        val session = repo.getWorkSession(id)!!
        assertEquals("finished", session.status) // 再開されない
    }

    @Test
    fun recordWorkEvent_and_getWorkEvents() = runBlocking {
        val repo = newRepository()
        val sessionId = repo.startWorkSession("chat-1")

        val eventId = repo.recordWorkEvent(sessionId, "executed", "ファイルを編集した", eventTimestamp = 2000L)

        val events = repo.getWorkEvents(sessionId)
        assertEquals(1, events.size)
        assertEquals(eventId, events[0].id)
        assertEquals("executed", events[0].eventType)
        assertEquals(2000L, events[0].eventTimestamp)
        // recordTimestamp と eventTimestamp は分離して保存
        assertTrue(events[0].recordTimestamp > 0)
        assertEquals("ファイルを編集した", events[0].description)
    }

    @Test
    fun recordJudgment_and_getJudgments() = runBlocking {
        val repo = newRepository()
        val sessionId = repo.startWorkSession("chat-1")

        val judgmentId = repo.recordJudgment(sessionId, "このアプローチを採用する", judgmentAt = 3000L)

        val judgments = repo.getJudgments(sessionId)
        assertEquals(1, judgments.size)
        assertEquals(judgmentId, judgments[0].id)
        assertEquals("このアプローチを採用する", judgments[0].judgmentText)
        assertEquals(3000L, judgments[0].judgmentAt)
        // recordTimestamp と judgmentAt は分離して保存
        assertTrue(judgments[0].recordTimestamp > 0)
    }

    @Test
    fun updateWorkState_preserves_unspecified_fields() = runBlocking {
        val repo = newRepository()
        val sessionId = repo.startWorkSession("chat-1")

        repo.updateWorkState(sessionId, currentPhase = "executed", currentStatus = "作業実行中")

        val state = repo.getWorkState(sessionId)!!
        assertEquals("executed", state.currentPhase)
        assertEquals("作業実行中", state.currentStatus)
    }

    @Test
    fun getWorkSessionsByChat_returns_sessions_ordered_by_startedAt_desc() = runBlocking {
        val repo = newRepository()
        val id1 = repo.startWorkSession("chat-1", description = "first")
        val id2 = repo.startWorkSession("chat-1", description = "second")

        val sessions = repo.getWorkSessionsByChat("chat-1")
        assertEquals(2, sessions.size)
        assertTrue(sessions.any { it.id == id1 })
        assertTrue(sessions.any { it.id == id2 })
    }
}
