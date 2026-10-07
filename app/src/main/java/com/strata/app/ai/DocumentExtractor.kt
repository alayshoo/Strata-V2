package com.strata.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import androidx.annotation.RequiresApi
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** A file prepared for the model: text when we can read it, page images when it is a scan. */
data class PreparedAttachment(
    val fileName: String,
    val mimeType: String,
    val text: String,
    val pageCount: Int,
    /** data: URLs, only kept in memory for the current turn. */
    val images: List<String> = emptyList(),
)

class DocumentException(message: String) : Exception(message)

/** Judges whether extracted text is usable. Financial statements are full of digits. */
object TextQuality {
    fun readable(text: String, pages: Int): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < maxOf(1, pages) * 40) return false
        val digits = trimmed.count { it.isDigit() }
        val letters = trimmed.count { it.isLetter() }
        return digits >= maxOf(10, pages * 5) && letters >= 20
    }

    /** The readable candidate with the most digits, or null when none is readable. */
    fun best(candidates: List<Pair<String, Int>>): Pair<String, Int>? =
        candidates.filter { readable(it.first, it.second) }.maxByOrNull { (text, _) -> text.count { it.isDigit() } }
}

/** Reads statements on the device, so only extracted text (or page images for scans) is sent out. */
class DocumentExtractor(private val context: Context) {
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    suspend fun prepare(uri: Uri): PreparedAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "document"
        val mime = resolver.getType(uri) ?: guessMime(name)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw DocumentException("Could not open $name.")
        if (bytes.size > MAX_BYTES) throw DocumentException("$name is larger than 20 MB.")

        when {
            // Some apps share PDFs without a .pdf name or with a generic type, so check the bytes too.
            mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) || isPdf(bytes) -> pdf(name, bytes)
            mime.startsWith("image/") -> PreparedAttachment(
                name, mime, "", 1,
                images = listOf("data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)),
            )
            else -> {
                val text = bytes.toString(Charsets.UTF_8)
                PreparedAttachment(name, mime, text.take(MAX_CHARS), 0)
            }
        }
    }

    /**
     * Text extraction is tried with Android's own PDF engine (PDFium, Android 15+) first and
     * PdfBox second. Each result is checked, because some fonts make an extractor silently drop
     * characters (PdfBox 2.0.27 loses every digit in Trading 212 statements). If neither result
     * reads like a statement, the pages go to the model as images instead.
     */
    private fun pdf(name: String, bytes: ByteArray): PreparedAttachment {
        val candidates = mutableListOf<Pair<String, Int>>()
        var passwordProtected = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            try {
                candidates += platformText(bytes)
            } catch (_: SecurityException) {
                passwordProtected = true
            } catch (_: Exception) {
                // Fall through to PdfBox.
            }
        }
        if (candidates.none { TextQuality.readable(it.first, it.second) }) {
            try {
                PDDocument.load(bytes).use { doc ->
                    val stripper = PDFTextStripper().apply { sortByPosition = true }
                    candidates += stripper.getText(doc) to doc.numberOfPages
                }
            } catch (_: InvalidPasswordException) {
                passwordProtected = true
            } catch (_: Exception) {
                // Leave it to the images.
            }
        }
        if (passwordProtected && candidates.isEmpty()) {
            throw DocumentException("$name is password protected. Open it and save an unprotected copy first.")
        }
        val best = TextQuality.best(candidates)
        val pages = candidates.maxOfOrNull { it.second } ?: 0
        if (best != null) return PreparedAttachment(name, "application/pdf", best.first.take(MAX_CHARS), best.second)
        return PreparedAttachment(name, "application/pdf", "", pages, images = renderPages(bytes))
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun platformText(bytes: ByteArray): Pair<String, Int> {
        val file = File.createTempFile("text", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val text = StringBuilder()
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            page.textContents.forEach { text.append(it.text).append('\n') }
                        }
                        text.append('\n')
                    }
                    return text.toString() to renderer.pageCount
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun renderPages(bytes: ByteArray): List<String> {
        val file = File.createTempFile("scan", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return PdfRenderer(descriptor).use { renderer ->
                (0 until minOf(renderer.pageCount, MAX_SCANNED_PAGES)).map { index ->
                    renderer.openPage(index).use { page ->
                        val scale = 1600f / page.width
                        val bitmap = Bitmap.createBitmap(1600, (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        bitmap.recycle()
                        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    }
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun isPdf(bytes: ByteArray) =
        bytes.size > 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()

    private fun guessMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "csv" -> "text/csv"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "text/plain"
    }

    private companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        const val MAX_CHARS = 150_000
        const val MAX_SCANNED_PAGES = 10
    }
}
