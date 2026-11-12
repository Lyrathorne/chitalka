package ru.chitalka.app.data

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.*
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Sequential bundled Latin OCR; no image uploads and at most one bitmap in memory. */
suspend fun recognizeScannedDocx(context:Context,source:File,info:ScannedDocxException,progress:(String)->Unit):TextBook {
    fun xml(zip:ZipFile,name:String):org.w3c.dom.Document {
        val entry=zip.getEntry(name)?:error("В DOCX нет $name")
        require(entry.size in 1..DocumentImporter.MAX_XML.toLong()){"Слишком большой XML DOCX"}
        val bytes=zip.getInputStream(entry).use{input->val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(32768);while(true){val n=input.read(buffer);if(n<0)break;require(output.size().toLong()+n<=DocumentImporter.MAX_XML){"Слишком большой XML DOCX"};output.write(buffer,0,n)};output.toByteArray()}
        val declaration=bytes.toString(Charsets.ISO_8859_1).replace("\u0000","")
        require(!declaration.contains("<!DOCTYPE",true)&&!declaration.contains("<!ENTITY",true)){"DTD запрещён"}
        return DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true;isExpandEntityReferences=false}.newDocumentBuilder().apply{setEntityResolver{_,_->throw org.xml.sax.SAXException("Внешние сущности запрещены")}}.parse(bytes.inputStream())
    }
    val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val temp=File.createTempFile("ocr-image-",".image",context.cacheDir)
    try { ZipFile(source).use { zip ->
        val doc=xml(zip,"word/document.xml");val rel=xml(zip,"word/_rels/document.xml.rels")
        val targets=mutableMapOf<String,String>()
        val relations=rel.documentElement.childNodes
        for(i in 0 until relations.length){val e=relations.item(i) as? org.w3c.dom.Element?:continue;if(e.getAttribute("TargetMode")!="External")targets[e.getAttribute("Id")]=e.getAttribute("Target")}
        val refs=doc.getElementsByTagNameNS("*","blip");require(refs.length in 1..1000){"В DOCX слишком много страниц для OCR"}
        val chapters=mutableListOf<TextChapter>();var total=0
        for(i in 0 until refs.length){
            currentCoroutineContext().ensureActive()
            progress("Распознаём скан DOCX: ${i+1} из ${refs.length}")
            val id=(refs.item(i) as org.w3c.dom.Element).getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","embed")
            val target=java.net.URI(targets[id]?:error("Изображение DOCX не найдено"))
            val path=target.path?:error("Неверный путь изображения")
            require(!target.isAbsolute && !path.startsWith('/') && !path.contains('\\') && path.split('/').none{it==".."}){"Небезопасный путь изображения"}
            val entry=zip.getEntry("word/$path")?:error("Нет изображения DOCX")
            require(entry.size in 1..64L*1024*1024){"Изображение слишком большое"}
            zip.getInputStream(entry).use{input->temp.outputStream().use{output->val buffer=ByteArray(32768);var size=0L;while(true){val n=input.read(buffer);if(n<0)break;size+=n;require(size<=64L*1024*1024){"Изображение слишком большое"};output.write(buffer,0,n)}}}
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(temp.path,bounds)
            require(bounds.outWidth>0&&bounds.outHeight>0){"Не удалось прочитать изображение"}
            var sample=1
            while(bounds.outWidth/sample>3000||bounds.outHeight/sample>3000||(bounds.outWidth.toLong()/sample)*(bounds.outHeight/sample)>6_000_000)sample*=2
            val bitmap=BitmapFactory.decodeFile(temp.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("Не удалось открыть скан")
            try {
                val result=withContext(NonCancellable){recognizer.process(InputImage.fromBitmap(bitmap,0)).await()}
                val paragraphs=result.textBlocks.flatMap{it.lines}.map{it.text.trim()}.filter{it.isNotEmpty()}
                total+=paragraphs.sumOf{it.length};require(total<=DocumentImporter.MAX_TEXT){"Слишком много текста OCR"}
                chapters+=TextChapter("Страница ${i+1}",paragraphs)
            } finally {bitmap.recycle()}
        }
        require(total>=10){"OCR не нашёл текста. Нужны чёткие сканы на английском языке."}
        return TextBook(info.bookTitle,info.bookAuthor,"DOCX OCR",chapters)
    }} finally {temp.delete();recognizer.close()}
}
