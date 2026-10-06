package com.strata.app

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.strata.app.ai.ChangeSet
import com.strata.app.ai.NewSnapshot
import com.strata.app.ai.NewTransaction
import com.strata.app.ai.ProductPointer
import com.strata.app.data.db.AttachmentEntity
import com.strata.app.data.db.ChatEntity
import com.strata.app.data.db.MessageEntity
import com.strata.app.data.db.ProposalStatus
import com.strata.app.data.db.Role
import com.strata.app.domain.DashboardInput
import com.strata.app.domain.TimeRange
import com.strata.app.domain.buildDashboard
import com.strata.app.ui.StrataNavigationBar
import com.strata.app.ui.TopDestination
import com.strata.app.ui.chat.ChatListScreen
import com.strata.app.ui.chat.ConversationScreen
import com.strata.app.ui.chat.ConversationUi
import com.strata.app.ui.chat.Lookup
import com.strata.app.ui.chat.buildChatItems
import com.strata.app.ui.components.EditorBody
import com.strata.app.ui.dashboard.ChartPreviewSelection
import com.strata.app.ui.dashboard.DashboardScreen
import com.strata.app.ui.explorer.ExplorerData
import com.strata.app.ui.explorer.ExplorerScreen
import com.strata.app.ui.explorer.ExplorerTab
import com.strata.app.ui.explorer.ProductDetailScreen
import com.strata.app.ui.lock.LockScreen
import com.strata.app.ui.lock.LockUi
import com.strata.app.ui.setup.AiSettingsUi
import com.strata.app.ui.setup.SetupLists
import com.strata.app.ui.setup.SetupScreen
import com.strata.app.ui.theme.StrataTheme
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders every screen with sample data, light and dark, into docs/screenshots. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest(private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun params() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @get:Rule
    val compose = createComposeRule()

    private val theme get() = if (dark) "dark" else "light"

    private fun shoot(name: String, tall: Int? = null, content: @Composable () -> Unit) {
        if (tall != null) RuntimeEnvironment.setQualifiers("+h${tall}dp")
        RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
        compose.setContent {
            StrataTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage("../docs/screenshots/$name-$theme.png")
    }

    @Composable
    private fun WithNav(route: TopDestination, content: @Composable (PaddingValues) -> Unit) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = { StrataNavigationBar(route.route) {} },
        ) { content(it) }
    }

    private val explorerData = ExplorerData(
        sources = SampleData.sources,
        assetClasses = SampleData.assetClasses,
        categories = SampleData.categories,
        products = SampleData.products,
        snapshots = SampleData.snapshots,
        transactions = SampleData.transactions,
        imports = SampleData.imports,
        fx = SampleData.fx,
    )

    private fun dashboard(range: TimeRange) = buildDashboard(
        DashboardInput(SampleData.assetClasses, SampleData.products, SampleData.snapshots, SampleData.transactions, SampleData.categories, SampleData.fx),
        range, SampleData.today,
    )

    @Test fun lock() = shoot("01-lock") { LockScreen(LockUi(firstRun = false), {}, {}) }

    @Test fun dashboard() = shoot("02-dashboard") {
        WithNav(TopDestination.DASHBOARD) { DashboardScreen(dashboard(TimeRange.Y1), {}, {}, contentPadding = it) }
    }

    @Test fun dashboardFull() = shoot("03-dashboard-full", tall = 2280) {
        WithNav(TopDestination.DASHBOARD) {
            DashboardScreen(dashboard(TimeRange.Y1), {}, {}, contentPadding = it, previewSelection = ChartPreviewSelection(bars = 9))
        }
    }

    @Test fun dashboardAll() = shoot("04-dashboard-all-range", tall = 1500) {
        WithNav(TopDestination.DASHBOARD) {
            DashboardScreen(dashboard(TimeRange.ALL), {}, {}, contentPadding = it, previewSelection = ChartPreviewSelection(line = 20))
        }
    }

    @Test fun chatList() = shoot("05-chat-list") {
        val now = System.currentTimeMillis()
        WithNav(TopDestination.CHAT) {
            ChatListScreen(
                listOf(
                    ChatEntity(3, "September statement from Millennium", now - 3_600_000, now - 3_600_000),
                    ChatEntity(2, "Trade Republic Q3 report", now - 86_400_000 * 2, now - 86_400_000 * 2),
                    ChatEntity(1, "How much did I spend on dining since June?", now - 86_400_000 * 9, now - 86_400_000 * 9),
                ),
                emptyMap(), {}, {}, {}, contentPadding = it,
            )
        }
    }

    @Test fun conversation() = shoot("06-conversation", tall = 1400) {
        ConversationScreen(conversationUi(), emptyList(), {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun conversationApplied() = shoot("07-conversation-applied") {
        ConversationScreen(conversationUi(ProposalStatus.APPLIED), listOf("revolut_2026_09.csv"), {}, {}, {}, {}, {}, {}, {}, {}, {}, initialDraft = "And this one is my Revolut export")
    }

    @Test fun holdings() = shoot("08-data-holdings", tall = 1300) {
        WithNav(TopDestination.DATA) { ExplorerScreen(explorerData, {}, {}, {}, {}, {}, contentPadding = it) }
    }

    @Test fun transactions() = shoot("09-data-transactions") {
        WithNav(TopDestination.DATA) { ExplorerScreen(explorerData, {}, {}, {}, {}, {}, contentPadding = it, initialTab = ExplorerTab.TRANSACTIONS) }
    }

    @Test fun imports() = shoot("10-data-imports") {
        WithNav(TopDestination.DATA) { ExplorerScreen(explorerData, {}, {}, {}, {}, {}, contentPadding = it, initialTab = ExplorerTab.IMPORTS) }
    }

    @Test fun product() = shoot("11-product-detail") {
        ProductDetailScreen(3, explorerData, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun setup() = shoot("12-setup", tall = 2350) {
        WithNav(TopDestination.SETUP) {
            SetupScreen(
                SetupLists(SampleData.sources, SampleData.assetClasses, SampleData.categories),
                AiSettingsUi(keyTail = "9f3a", model = "anthropic/claude-sonnet-5.5", testResult = "Connected. Used $3.42 of $25.00.", testOk = true),
                {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, contentPadding = it,
            )
        }
    }

    @Test fun assetClassEditor() = shoot("13-asset-class-editor") {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            EditorBody("Edit asset class", actions = {
                androidx.compose.material3.TextButton({}) { androidx.compose.material3.Text("Delete", color = MaterialTheme.colorScheme.error) }
                androidx.compose.material3.Button({}) { androidx.compose.material3.Text("Save") }
            }) {
                com.strata.app.ui.components.FormField("ETFs", {}, "Name")
                com.strata.app.ui.setup.ColorPicker("saffron") {}
            }
        }
    }

    @Test fun emptyDashboard() = shoot("14-dashboard-empty") {
        WithNav(TopDestination.DASHBOARD) {
            DashboardScreen(
                buildDashboard(DashboardInput(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), com.strata.app.domain.FxTable.EMPTY), TimeRange.Y1, SampleData.today),
                {}, {}, contentPadding = it,
            )
        }
    }

    private fun conversationUi(status: ProposalStatus = ProposalStatus.PENDING): ConversationUi {
        val json = Json
        val changes = ChangeSet(
            snapshots = listOf(
                NewSnapshot(ProductPointer(id = 1), "2026-09-30", "4381.27"),
                NewSnapshot(ProductPointer(id = 10), "2026-09-30", "712.40"),
            ),
            transactions = listOf(
                NewTransaction(ProductPointer(id = 1), "2026-09-24", "3600.00", "Salary September", "Acme Labs Lda", "INCOME", 9),
                NewTransaction(ProductPointer(id = 1), "2026-09-01", "-1150.00", "Rent", "Imobiliária Luz", "EXPENSE", 1),
                NewTransaction(ProductPointer(id = 1), "2026-09-25", "-800.00", "Transfer", "Trade Republic", "TRANSFER", linkToTransactionId = 120),
                NewTransaction(ProductPointer(id = 1), "2026-09-21", "-63.18", "Groceries", "Pingo Doce", "EXPENSE", 2),
                NewTransaction(ProductPointer(id = 10), "2026-09-19", "-412.00", "Flights", "TAP Air Portugal", "EXPENSE", 8),
                NewTransaction(ProductPointer(id = 10), "2026-09-02", "-15.99", "Spotify Premium", "Spotify", "EXPENSE", 7),
            ),
            fileNames = listOf("extrato_setembro_2026.pdf"),
        )
        val calls = { names: List<String> ->
            names.mapIndexed { i, n -> """{"id":"c$i","type":"function","function":{"name":"$n","arguments":"{}"}}""" }.joinToString(",", "[", "]")
        }
        val messages = listOf(
            MessageEntity(1, 3, Role.USER, "September statement from Millennium. The Visa card is in there too."),
            MessageEntity(2, 3, Role.ASSISTANT, "", toolCallsJson = calls(listOf("list_sources", "list_products", "list_spending_categories"))),
            MessageEntity(3, 3, Role.TOOL, "[]", toolCallId = "c0"),
            MessageEntity(4, 3, Role.ASSISTANT, "", toolCallsJson = calls(listOf("get_transactions", "find_transfer_candidates", "stage_snapshots", "stage_transactions"))),
            MessageEntity(
                5, 3, Role.ASSISTANT,
                "I read the statement for 1 to 30 September and staged it for review.\n\n" +
                    "- Closing balances for the **current account** (€4,381.27) and the **Visa Gold** (€712.40 owed)\n" +
                    "- 32 transactions; the €800 to Trade Republic is linked to the deposit already recorded there\n\n" +
                    "Two card payments to “PAYPAL *STEAM” could be games or software, so I left them uncategorised.",
                proposalJson = json.encodeToString(ChangeSet.serializer(), changes),
                proposalStatus = status,
                importId = if (status == ProposalStatus.APPLIED) 3 else null,
            ),
        )
        val attachments = listOf(AttachmentEntity(1, 1, "extrato_setembro_2026.pdf", "application/pdf", "", 3))
        val items = buildChatItems(messages, attachments, Lookup(SampleData.products, SampleData.sources, SampleData.categories))
        return ConversationUi("September statement from Millennium", items, model = "anthropic/claude-sonnet-5.5")
    }

}
