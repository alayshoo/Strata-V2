package com.strata.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.strata.app.ai.Agent
import com.strata.app.share.ShareInbox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareAndNoteTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun sharedFileIsCopiedAndReleasedAfterReading() = runBlocking {
        val source = File(context.filesDir, "statement.csv").apply { writeText("date,amount\n2026-09-01,10.00\n") }
        val inbox = ShareInbox(context)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(source))
        }
        assertTrue(inbox.accept(intent))
        val bundle = inbox.take()!!
        assertNull(inbox.pending.value)
        val copy = bundle.files.single()
        assertEquals("statement.csv", copy.name)
        assertTrue(inbox.isOurs(copy.uri))
        assertEquals(source.readText(), File(copy.uri.path!!).readText())

        inbox.release(copy.uri)
        assertFalse(File(copy.uri.path!!).exists())
        assertTrue("the original is never touched", source.exists())
    }

    @Test fun ignoresOtherIntents() = runBlocking {
        assertFalse(ShareInbox(context).accept(Intent(Intent.ACTION_VIEW)))
    }

    @Test fun userNoteIsAddedAfterTheBuiltInPrompt() {
        val today = LocalDate.of(2026, 10, 7)
        val plain = Agent.systemPrompt(today)
        assertFalse(plain.contains("Notes from the user"))
        val withNote = Agent.systemPrompt(today, "Transfers in my name are internal.")
        assertTrue(withNote.startsWith(plain))
        assertTrue(withNote.trimEnd().endsWith("Transfers in my name are internal."))
        assertEquals(plain, Agent.systemPrompt(today, "   "))
    }
}
