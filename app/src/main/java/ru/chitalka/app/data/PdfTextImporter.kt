package ru.chitalka.app.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

fun extractPdfText(context: Context, source: File, fallbackTitle: String): TextBook {
    PDFBoxResourceLoader.init(context.applicationContext)
    val document = try { PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly().apply { tempDir = context.cacheDir }) }
        catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) { throw IllegalArgumentException("PDF защищён паролем. Сохраните копию без пароля.", e) }
    return document.use { pdf ->
        require(!pdf.isEncrypted && pdf.currentAccessPermission.canExtractContent()) { "PDF с шифрованием или запретом копирования не поддерживается" }
        require(pdf.numberOfPages <= 1000) { "В PDF больше 1000 страниц" }
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        val chapters = mutableListOf<TextChapter>(); var total = 0
        for (page in 1..pdf.numberOfPages) {
            stripper.startPage = page; stripper.endPage = page
            val text = stripper.getText(pdf).replace(Regex("\\s+"), " ").trim()
            total += text.length; require(total <= DocumentImporter.MAX_TEXT) { "В PDF слишком много текста" }
            if (text.isNotBlank()) chapters += TextChapter("Страница $page", text.chunked(100000))
        }
        require(total >= 10) { "В PDF нет доступного текстового слоя. Это скан: сначала распознайте текст (OCR)." }
        TextBook(pdf.documentInformation.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            pdf.documentInformation.author ?: "", "PDF", chapters)
    }
}
