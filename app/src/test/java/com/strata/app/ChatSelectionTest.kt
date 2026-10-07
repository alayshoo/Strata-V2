package com.strata.app

import android.content.ClipboardManager
import android.content.Context
import android.widget.Magnifier
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.strata.app.ai.ChangeSet
import com.strata.app.ai.NewSnapshot
import com.strata.app.ai.ProductPointer
import com.strata.app.data.db.MessageEntity
import com.strata.app.data.db.ProposalStatus
import com.strata.app.data.db.Role
import com.strata.app.ui.chat.ConversationScreen
import com.strata.app.ui.chat.ConversationUi
import com.strata.app.ui.chat.Lookup
import com.strata.app.ui.chat.buildChatItems
import com.strata.app.ui.theme.StrataTheme
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Robolectric can't draw the magnifier shown while selecting; the test doesn't need it. */
@Implements(Magnifier::class)
class NoMagnifier {
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit
    @Implementation fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit
    @Implementation fun update() = Unit
    @Implementation fun dismiss() = Unit
}

/** Chat messages select and copy like ordinary text: long-press, Select all, Copy. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi", application = android.app.Application::class, shadows = [NoMagnifier::class])
class ChatSelectionTest {
    @get:Rule val compose = createComposeRule()

    /** Stands in for the system's floating Copy / Select all menu. */
    private class Toolbar : TextToolbar {
        var copy: (() -> Unit)? = null
        var selectAll: (() -> Unit)? = null
        override var status = TextToolbarStatus.Hidden
        override fun hide() { status = TextToolbarStatus.Hidden }
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copy = onCopyRequested
            selectAll = onSelectAllRequested
            status = TextToolbarStatus.Shown
        }
    }

    private val toolbar = Toolbar()

    // The app shows the system's floating Copy menu, which Robolectric can't tap. The older menu path
    // asks LocalTextToolbar instead; selecting and copying are the same SelectionManager either way.
    private var newContextMenu = true

    @OptIn(ExperimentalFoundationApi::class)
    @Before fun useTextToolbar() {
        newContextMenu = ComposeFoundationFlags.isNewContextMenuEnabled
        ComposeFoundationFlags.isNewContextMenuEnabled = false
    }

    @OptIn(ExperimentalFoundationApi::class)
    @After fun restoreContextMenu() { ComposeFoundationFlags.isNewContextMenuEnabled = newContextMenu }

    private val reply = "I checked September.\n\n- Rent is **€1,150.00**\n- Groceries came to €212.40, a little more than August\n\nAnything else?"

    private fun showConversation() {
        val changes = ChangeSet(snapshots = listOf(NewSnapshot(ProductPointer(id = 1), "2026-09-30", "4381.27")))
        val messages = listOf(
            MessageEntity(1, 1, Role.USER, "How much was rent?"),
            MessageEntity(
                2, 1, Role.ASSISTANT, reply,
                proposalJson = Json.encodeToString(ChangeSet.serializer(), changes), proposalStatus = ProposalStatus.PENDING,
            ),
        )
        val items = buildChatItems(messages, emptyList(), Lookup(SampleData.products, SampleData.sources, SampleData.categories))
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                StrataTheme(darkTheme = false) {
                    ConversationScreen(ConversationUi("September", items), emptyList(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
    }

    /** Long-presses [text], selects everything in its message and copies it. */
    private fun copyAllFrom(text: String): String {
        compose.onNodeWithText(text, substring = true).performTouchInput { longClick() }
        compose.waitForIdle()
        // Offered only while part of the message is unselected.
        compose.runOnIdle { toolbar.selectAll?.invoke() }
        compose.waitForIdle()
        compose.runOnIdle { toolbar.copy!!.invoke() }
        compose.waitForIdle()
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        return clipboard.primaryClip!!.getItemAt(0).text.toString()
    }

    @Test fun anAssistantReplyCopiesWithItsParagraphsAndBullets() {
        showConversation()
        val copied = copyAllFrom("I checked September.")
        assertTrue(copied, copied.startsWith("I checked September.\n• Rent is €1,150.00\n• Groceries came to €212.40, a little more than August\nAnything else?"))
        // The review card's rows copy with the reply; its buttons and the user's message don't.
        assertTrue(copied, copied.contains("Current account"))
        assertFalse(copied, copied.contains("Apply") || copied.contains("Discard"))
        assertFalse(copied, copied.contains("How much was rent?"))
    }

    @Test fun aUserMessageCopiesOnItsOwn() {
        showConversation()
        assertEquals("How much was rent?", copyAllFrom("How much was rent?"))
    }
}
