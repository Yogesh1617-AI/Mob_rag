package com.mrk.filerag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile

val IMG_EXT = setOf("jpg", "jpeg", "png", "webp")
val TEXT_EXT = setOf(
    "txt", "md", "csv", "json", "xml", "html", "htm", "log", "kt", "java", "py", "js", "ts",
    "c", "cpp", "h", "cs", "sql", "yml", "yaml", "ini", "tex"
)

fun readHead(f: File, max: Int): String = try {
    f.bufferedReader().use { r ->
        val cb = CharArray(max)
        val n = r.read(cb)
        if (n > 0) String(cb, 0, n) else ""
    }
} catch (e: Exception) {
    ""
}

fun decodeScaled(path: String, max: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var s = 1
    while (o.outWidth / s > max || o.outHeight / s > max) s *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
}

fun renderPage(r: PdfRenderer, i: Int, w: Int): Bitmap {
    val p = r.openPage(i)
    try {
        val h = (w.toFloat() / p.width * p.height).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return b
    } finally {
        p.close()
    }
}

class Indexer(private val ctx: Context, private val db: Db) {

    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build())
    }
    private val ocr by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    fun run(root: File, onProgress: (Int, String) -> Unit, alive: () -> Boolean) {
        PDFBoxResourceLoader.init(ctx)
        var added = 0
        val walker = root.walkTopDown().onEnter { d ->
            !d.name.startsWith(".") && !(d.parentFile == root && d.name == "Android")
        }
        for (f in walker) {
            if (!alive()) return
            if (!f.isFile || f.name.startsWith(".")) continue
            val size = f.length()
            if (size == 0L) continue
            val mtime = f.lastModified()
            if (db.isUpToDate(f.path, mtime, size)) continue
            val ext = f.extension.lowercase()
            val body = try { extract(f, ext, size) } catch (e: Throwable) { "" }
            val parent = f.parentFile?.name ?: ""
            db.upsert(f.path, f.name + " " + parent, body.take(60_000), mtime, size)
            added++
            onProgress(added, f.name)
        }
        if (alive()) db.removeMissing()
    }

    private fun extract(f: File, ext: String, size: Long): String = when {
        ext in IMG_EXT -> "photo picture image " + imageText(f)
        ext in TEXT_EXT -> readHead(f, 100_000)
        ext == "pdf" && size < 40_000_000 -> pdfText(f)
        ext == "docx" -> docxText(f)
        else -> ""
    }

    private fun imageText(f: File): String {
        val bm = decodeScaled(f.path, 1024) ?: return ""
        val img = InputImage.fromBitmap(bm, 0)
        val sb = StringBuilder()
        try {
            for (l in Tasks.await(labeler.process(img))) sb.append(l.text).append(' ')
        } catch (e: Exception) {
        }
        try {
            sb.append(Tasks.await(ocr.process(img)).text)
        } catch (e: Exception) {
        }
        bm.recycle()
        return sb.toString()
    }

    private fun pdfText(f: File): String {
        var text = try {
            PDDocument.load(f).use { doc ->
                val s = PDFTextStripper()
                s.startPage = 1
                s.endPage = minOf(doc.numberOfPages, 40)
                s.getText(doc)
            }
        } catch (e: Throwable) {
            ""
        }
        if (text.isBlank()) text = pdfOcr(f)
        return text
    }

    private fun pdfOcr(f: File): String {
        val sb = StringBuilder()
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            r = PdfRenderer(pfd)
            for (i in 0 until minOf(r.pageCount, 3)) {
                val b = renderPage(r, i, 1200)
                sb.append(Tasks.await(ocr.process(InputImage.fromBitmap(b, 0))).text).append('\n')
                b.recycle()
            }
        } catch (e: Throwable) {
        } finally {
            try { r?.close() } catch (e: Throwable) {}
            try { pfd?.close() } catch (e: Throwable) {}
        }
        return sb.toString()
    }

    private fun docxText(f: File): String = try {
        ZipFile(f).use { z ->
            val e = z.getEntry("word/document.xml")
            if (e == null) "" else
                z.getInputStream(e).bufferedReader().use { it.readText() }
                    .replace(Regex("<[^>]+>"), " ").take(100_000)
        }
    } catch (e: Throwable) {
        ""
    }
}
