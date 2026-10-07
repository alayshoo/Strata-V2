package com.strata.app

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.strata.app.ai.Agent
import com.strata.app.ai.ChangeSet
import com.strata.app.ai.NewSnapshot
import com.strata.app.ai.OpenRouterClient
import com.strata.app.ai.ProductPointer
import com.strata.app.ai.ToolExecutor
import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.ChatEntity
import com.strata.app.data.db.FlowKind
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.ProposalStatus
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SourceType
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.data.db.StrataDatabase
import com.strata.app.data.repo.SettingsRepository
import com.strata.app.domain.FxTable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue

/** Correcting single staged items, within a turn and across turns, instead of starting over. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StagingEditTest {
    private lateinit var db: StrataDatabase
    private lateinit var tools: ToolExecutor

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StrataDatabase::class.java)
            .allowMainThreadQueries().build()
        tools = ToolExecutor(db, { FxTable.EMPTY }, today = LocalDate.of(2026, 10, 6))
        val setup = db.setupDao()
        setup.insertSource(SourceEntity(1, "Millennium BCP", SourceType.BANK))
        setup.insertAssetClass(AssetClassEntity(1, "Cash", "cobalt"))
        setup.insertSpendingCategory(SpendingCategoryEntity(1, "Groceries", FlowKind.EXPENSE, "saffron"))
        setup.insertSpendingCategory(SpendingCategoryEntity(2, "Salary", FlowKind.INCOME, "jade"))
        db.ledgerDao().insertProduct(ProductEntity(1, 1, 1, "Current account", "EUR"))
        Unit
    }

    @After fun tearDown() = db.close()

    private val threeRows = """{"items":[
        {"product_id":1,"date":"2026-09-21","amount":"-63.18","description":"Groceries","kind":"expense","spending_category_id":1},
        {"product_id":1,"date":"2026-09-22","amount":"-12.00","description":"Cafe","kind":"expense"},
        {"product_id":1,"date":"2026-09-25","amount":"-800","description":"To broker","kind":"transfer","transfer_key":"m"}
    ]}"""

    @Test fun correctsSingleItemsAndKeepsTheRest() = runBlocking {
        val staged = tools.execute("stage_transactions", threeRows)
        assertTrue(staged, staged.contains("\"staged_ids\":[1,2,3]"))

        val fix = tools.execute(
            "update_staged",
            """{"items":[{"staged_id":2,"set":{"amount":"-21.00","spending_category_id":1}},{"staged_id":1,"set":{"kind":"transfer"}}]}""",
        )
        assertTrue(fix, fix.contains("\"updated\":2"))
        val (first, second, third) = tools.staged.transactions
        assertEquals("-21.00", second.amount)
        assertEquals(1L, second.spendingCategoryId)
        // A new kind drops a category that no longer fits; untouched fields stay.
        assertEquals("TRANSFER", first.kind)
        assertNull(first.spendingCategoryId)
        assertEquals("Groceries", first.description)
        assertEquals("-800", third.amount)
    }

    @Test fun anInvalidCorrectionChangesNothing() = runBlocking {
        tools.execute("stage_transactions", threeRows)
        val before = tools.staged
        val bad = tools.execute(
            "update_staged",
            """{"items":[{"staged_id":2,"set":{"amount":"-30"}},{"staged_id":3,"set":{"spending_category_id":2}}]}""",
        )
        assertTrue(bad, bad.contains("only expense and income"))
        assertEquals(before, tools.staged)

        val unknown = tools.execute("update_staged", """{"items":[{"staged_id":99,"set":{"amount":"-1"}}]}""")
        assertTrue(unknown, unknown.contains("get_staged_changes"))
    }

    @Test fun removesItemsAndRowsOnARemovedProduct() = runBlocking {
        tools.execute("stage_product", """{"ref":"visa","source_id":1,"asset_class_id":1,"name":"Visa Gold","currency":"EUR"}""")
        tools.execute(
            "stage_snapshots",
            """{"items":[{"new_product_ref":"visa","date":"2026-09-30","value":"712.40"},{"product_id":1,"date":"2026-09-30","value":"4381.27"}]}""",
        )
        val filtered = tools.execute("get_staged_changes", """{"new_product_ref":"visa"}""")
        assertTrue(filtered, filtered.contains("\"value\":\"712.40\""))
        assertFalse(filtered.contains("4381.27"))

        val out = tools.execute("remove_staged", """{"staged_ids":[1]}""")
        assertTrue(out, out.contains("\"also_removed_with_their_product\":[2]"))
        assertTrue(tools.staged.products.isEmpty())
        assertEquals(listOf(3), tools.staged.snapshots.map { it.id })

        val again = tools.execute("remove_staged", """{"staged_ids":[1]}""")
        assertTrue(again, again.contains("Nothing staged has id 1"))
    }

    @Test fun movingABalanceOntoAnotherDayChecksForClashes() = runBlocking {
        tools.execute(
            "stage_snapshots",
            """{"items":[{"product_id":1,"date":"2026-09-30","value":"10"},{"product_id":1,"date":"2026-08-31","value":"5"}]}""",
        )
        val clash = tools.execute("update_staged", """{"items":[{"staged_id":2,"set":{"date":"2026-09-30"}}]}""")
        assertTrue(clash, clash.contains("already exists"))
        val ok = tools.execute("update_staged", """{"items":[{"staged_id":2,"set":{"value":"6.50","note":null}}]}""")
        assertTrue(ok, ok.contains("\"updated\":1"))
        assertEquals("6.50", tools.staged.snapshots[1].value)
    }

    @Test fun cardsStagedBeforeIdsGetThem() {
        tools.reset(ChangeSet(snapshots = listOf(NewSnapshot(ProductPointer(id = 1), "2026-09-30", "10"))))
        assertEquals(1, tools.staged.snapshots.single().id)
        assertEquals(2, tools.staged.nextId)
    }

    // ---------- Across turns ----------

    private val replies = ConcurrentLinkedQueue<String>()
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(replies.remove().toResponseBody("application/json".toMediaType())).build()
    }.build()

    private fun toolReply(name: String, arguments: String) = buildJsonObject {
        putJsonObject("usage") { put("cost", 0.0125) }
        putJsonArray("choices") {
            addJsonObject {
                putJsonObject("message") {
                    put("role", "assistant"); put("content", JsonNull)
                    putJsonArray("tool_calls") {
                        addJsonObject {
                            put("id", "call-${replies.size}-$name"); put("type", "function")
                            putJsonObject("function") { put("name", name); put("arguments", arguments) }
                        }
                    }
                }
            }
        }
    }.toString()

    private fun textReply(text: String) = buildJsonObject {
        putJsonObject("usage") { put("cost", 0.0125) }
        putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("role", "assistant"); put("content", text) } } }
    }.toString()

    @Test fun aPendingCardIsCorrectedInTheNextTurn() = runBlocking {
        val settings = SettingsRepository(db.settingsDao()).apply { setApiKey("test") }
        val agent = Agent(db, OpenRouterClient(http), settings) { FxTable.EMPTY }
        val chatId = db.chatDao().insertChat(ChatEntity(title = "New chat"))
        val json = Json { ignoreUnknownKeys = true }
        suspend fun cards() = db.chatDao().messages(chatId).filter { it.proposalJson != null }

        replies += toolReply("stage_transactions", threeRows)
        replies += textReply("Staged three transactions.")
        agent.send(chatId, "Record this", emptyList()) {}
        assertEquals(listOf(ProposalStatus.PENDING), cards().map { it.proposalStatus })

        // The user asks for one fix: only that item changes and the card is replaced.
        replies += toolReply("update_staged", """{"items":[{"staged_id":2,"set":{"amount":"-5.00"}}]}""")
        replies += textReply("Fixed the cafe amount.")
        agent.send(chatId, "The cafe was 5 euros", emptyList()) {}
        val (old, new) = cards()
        assertEquals(ProposalStatus.SUPERSEDED, old.proposalStatus)
        assertEquals(ProposalStatus.PENDING, new.proposalStatus)
        val changes = json.decodeFromString(ChangeSet.serializer(), new.proposalJson!!)
        assertEquals(listOf("-63.18", "-5.00", "-800"), changes.transactions.map { it.amount })
        assertEquals(listOf(1, 2, 3), changes.transactions.map { it.id })

        // A turn that leaves the card alone doesn't repeat it.
        replies += textReply("Your groceries were 63.18.")
        agent.send(chatId, "How much were groceries?", emptyList()) {}
        assertEquals(listOf(ProposalStatus.SUPERSEDED, ProposalStatus.PENDING), cards().map { it.proposalStatus })

        // Every request's reported cost adds up on the chat.
        assertEquals(5 * 0.0125, db.chatDao().chat(chatId)!!.costUsd, 1e-9)
    }
}
