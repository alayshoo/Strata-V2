package com.strata.app

import com.strata.app.ai.RoundTrace
import com.strata.app.data.db.MessageEntity
import com.strata.app.data.db.Role
import com.strata.app.ui.chat.ChatItem
import com.strata.app.ui.chat.Lookup
import com.strata.app.ui.chat.buildChatItems
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceTest {
    private fun trace(round: Int) = Json.encodeToString(RoundTrace.serializer(), RoundTrace(round, "m", 1_000L * round, reasoning = "r$round"))

    @Test fun groupsRoundsAndMatchesResultsById() {
        val messages = listOf(
            MessageEntity(1, 1, Role.USER, "go"),
            MessageEntity(
                2, 1, Role.ASSISTANT, "",
                toolCallsJson = """[{"id":"x","type":"function","function":{"name":"list_sources","arguments":"{}"}},{"id":"y","type":"function","function":{"name":"stage_snapshots","arguments":{"items":[]}}}]""",
                traceJson = trace(1),
            ),
            MessageEntity(3, 1, Role.TOOL, "[]", toolCallId = "x"),
            MessageEntity(4, 1, Role.TOOL, """{"error":"items is required"}""", toolCallId = "y"),
            MessageEntity(5, 1, Role.ASSISTANT, "Done", traceJson = trace(2)),
        )
        val items = buildChatItems(messages, emptyList(), Lookup(emptyList(), emptyList(), emptyList()))
        assertEquals(3, items.size)
        val activity = items[1] as ChatItem.Activity
        assertEquals(2, activity.rounds.size)
        assertEquals(2, activity.callCount)
        assertEquals(1, activity.errorCount)
        assertEquals(3_000L, activity.totalMs)
        // Arguments sent as an object rather than a string are still shown.
        assertTrue(activity.rounds[0].calls[1].arguments.contains("items"))
        assertEquals("r2", activity.rounds[1].reasoning)
        assertTrue(items[2] is ChatItem.Assistant)
    }
}
