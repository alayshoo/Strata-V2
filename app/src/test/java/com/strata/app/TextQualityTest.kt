package com.strata.app

import com.strata.app.ai.TextQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextQualityTest {
    private val good = """
        Activity statement
        Generated on 7 October 2026, covering 31.08.2026 to 05.10.2026.
        Deposits €1,000.00
        Withdrawals -€647.41
        2026-09-04 08:00:08 XDEW IE00BLNMYC90 Buy 0.01872267 €105.22 €1.97
        Account value €5,287.95
    """.trimIndent()

    // What PdfBox 2.0.27 produces for some Type 3 fonts: digits and a few letters vanish.
    private val garbled = good.filterNot { it.isDigit() || it == 'a' || it == 'y' }

    @Test fun rejectsTextWithItsDigitsStripped() {
        assertTrue(TextQuality.readable(good, 1))
        assertFalse(TextQuality.readable(garbled, 1))
    }

    @Test fun picksTheReadableCandidate() {
        assertEquals(good, TextQuality.best(listOf(garbled to 1, good to 1))?.first)
        assertNull(TextQuality.best(listOf(garbled to 1, "" to 1)))
    }
}
