package com.taboia872.androidassist.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of PDF extraction. */
sealed class PdfResult {
    data class Success(val text: String, val summary: String) : PdfResult()
    data class Error(val message: String) : PdfResult()
}

/**
 * PDF text extraction via PDFBox Android (same library the base app uses).
 * Caps at 50 pages / 50k chars so a huge PDF cannot blow the context.
 */
object PdfExtractor {

    private const val TAG = "PdfExtractor"
    private const val MAX_TEXT_LENGTH = 50_000
    private const val MAX_PAGES = 50

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    suspend fun extractText(context: Context, uri: Uri): PdfResult =
        withContext(Dispatchers.IO) {
            try {
                initialize(context)
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext PdfResult.Error("Cannot open PDF file")

                inputStream.use { stream ->
                    PDDocument.load(stream).use { doc ->
                        val pageCount = doc.numberOfPages
                        if (pageCount == 0) {
                            return@withContext PdfResult.Error("PDF has no pages")
                        }

                        val stripper = PDFTextStripper()
                        val pagesToProcess = minOf(pageCount, MAX_PAGES)
                        if (pagesToProcess < pageCount) stripper.endPage = pagesToProcess

                        val text = stripper.getText(doc)
                        if (text.isBlank()) {
                            return@withContext PdfResult.Error(
                                "PDF contains no extractable text (might be scanned images)"
                            )
                        }

                        val finalText = if (text.length > MAX_TEXT_LENGTH) {
                            text.take(MAX_TEXT_LENGTH) +
                                "\n\n[... truncated, showing first $MAX_TEXT_LENGTH of ${text.length} chars ...]"
                        } else text

                        val summary = "📄 PDF • " + (
                            if (pagesToProcess < pageCount)
                                "$pagesToProcess of $pageCount pages"
                            else "$pageCount pages"
                        )

                        Log.d(TAG, "Extracted ${finalText.length} chars from PDF")
                        PdfResult.Success(text = finalText, summary = summary)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PDF extraction failed", e)
                PdfResult.Error("Failed to read PDF: ${e.message}")
            }
        }
}
