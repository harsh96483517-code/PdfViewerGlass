package com.example.pdfglass

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.text.font.FontFamily
import androidx.exifinterface.media.ExifInterface

enum class PdfFont(val label: String, val compose: FontFamily, val face: () -> Typeface) {
    Sans("Sans", FontFamily.SansSerif, { Typeface.SANS_SERIF }),
    Serif("Serif", FontFamily.Serif, { Typeface.SERIF }),
    Mono("Mono", FontFamily.Monospace, { Typeface.MONOSPACE }),
    Script("Script", FontFamily.Cursive, { Typeface.create("cursive", Typeface.ITALIC) })
}

object PdfWriter {
    private const val PW = 595; private const val PH = 842; private const val MARGIN = 36f

    /** Writes one A4 page per image, in list order. Returns page count. Runs on a background thread. */
    fun write(ctx: Context, uris: List<Uri>, out: Uri, font: PdfFont, pageNumbers: Boolean, caption: String): Int {
        val doc = PdfDocument()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font.face(); textSize = 12f; color = Color.DKGRAY; textAlign = Paint.Align.CENTER
        }
        val img = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            uris.forEachIndexed { i, uri ->
                val bmp = decode(ctx, uri, 1800) ?: error("Cannot read image ${i + 1}")
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, i + 1).create())
                val c = page.canvas
                c.drawColor(Color.WHITE)
                val box = RectF(MARGIN, MARGIN, PW - MARGIN, PH - MARGIN - 24f)
                val s = minOf(box.width() / bmp.width, box.height() / bmp.height)
                val w = bmp.width * s; val h = bmp.height * s
                val l = box.centerX() - w / 2; val t = box.centerY() - h / 2
                c.drawBitmap(bmp, null, RectF(l, t, l + w, t + h), img)
                val label = listOfNotNull(caption.ifBlank { null }, if (pageNumbers) "${i + 1} / ${uris.size}" else null)
                    .joinToString("  •  ")
                if (label.isNotEmpty()) c.drawText(label, PW / 2f, PH - MARGIN + 2f, text)
                doc.finishPage(page)
                bmp.recycle()
            }
            ctx.contentResolver.openOutputStream(out)?.use { doc.writeTo(it) } ?: error("Cannot open output file")
        } finally { doc.close() }
        return uris.size
    }

    internal fun decode(ctx: Context, uri: Uri, max: Int): Bitmap? {
        val r = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (bounds.outWidth / s > max || bounds.outHeight / s > max) s *= 2
        val b = r.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        } ?: return null
        val deg = when (r.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
        }
        if (deg == 0f) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
    }
}
