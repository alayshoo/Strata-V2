package com.strata.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
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
            mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> pdf(name, bytes)
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

    private fun pdf(name: String, bytes: ByteArray): PreparedAttachment {
        val (text, pages) = try {
            PDDocument.load(bytes).use { doc ->
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                stripper.getText(doc) to doc.numberOfPages
            }
        } catch (_: InvalidPasswordException) {
            throw DocumentException("$name is password protected. Open it and save an unprotected copy first.")
        }
        // Statements with real text average hundreds of characters per page; scans have almost none.
        if (text.trim().length >= pages * 40) return PreparedAttachment(name, "application/pdf", text.take(MAX_CHARS), pages)
        return PreparedAttachment(name, "application/pdf", "", pages, images = renderPages(bytes))
    }

    private fun renderPages(bytes: ByteArray): List<String> {
        val file = File.createTempFile("scan", ".pdf", context.cacheDir)
        try {
            file.writeBytes(bytes)
            val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
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

    private fun guessMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "csv" -> "text/csv"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "text/plain"
    }

    private companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        const val MAX_CHARS = 150_000
        const val MAX_SCANNED_PAGES = 10
    }
}
