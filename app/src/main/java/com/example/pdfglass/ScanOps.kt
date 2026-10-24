package com.example.pdfglass

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.io.IOException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ScanFilter(val label: String) { Original("Original"), Enhance("Enhance"), Gray("Gray"), BW("B&W") }

/** Corners are normalized (0..1) on the raw photo, ordered top-left, top-right, bottom-right, bottom-left. */
val FULL_CROP = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))
data class ScanPage(val id: Long, val file: File, val corners: List<Offset> = FULL_CROP, val rot: Int = 0, val filter: ScanFilter = ScanFilter.Enhance)

object Scan {
    private fun contrast(c: Float, b: Float): ColorMatrix {
        val t = (-0.5f * c + 0.5f + b) * 255f
        return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
    }

    /** Non-destructive: crop (perspective-corrected from the 4 corners) -> rotate -> filter. */
    fun render(ctx: Context, p: ScanPage, maxDim: Int): Bitmap {
        val src = PdfWriter.decode(ctx, Uri.fromFile(p.file), maxDim) ?: throw IOException("Cannot read scan")
        val pts = FloatArray(8)
        p.corners.forEachIndexed { i, c -> pts[i * 2] = c.x * src.width; pts[i * 2 + 1] = c.y * src.height }
        fun d(a: Int, b: Int) = hypot(pts[a * 2] - pts[b * 2], pts[a * 2 + 1] - pts[b * 2 + 1])
        var w = max(d(0, 1), d(3, 2)); var h = max(d(0, 3), d(1, 2))
        val k = min(1f, maxDim / max(w, h)); w = max(1f, w * k); h = max(1f, h * k)
        val out = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val m = Matrix()
        if (!m.setPolyToPoly(pts, 0, floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, 4)) throw IOException("Invalid crop area")
        Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG)) }
        src.recycle()
        var r = out
        if (p.rot % 360 != 0) r = Bitmap.createBitmap(out, 0, 0, out.width, out.height, Matrix().apply { postRotate(p.rot.toFloat()) }, true)
        val cm = when (p.filter) {
            ScanFilter.Original -> return r
            ScanFilter.Enhance -> ColorMatrix().apply { setSaturation(0.8f); postConcat(contrast(1.3f, 0.05f)) }
            ScanFilter.Gray -> ColorMatrix().apply { setSaturation(0f) }
            ScanFilter.BW -> ColorMatrix().apply { setSaturation(0f); postConcat(contrast(3f, 0f)) }
        }
        val o = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888)
        Canvas(o).drawBitmap(r, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return o
    }

    fun exportPdf(ctx: Context, pages: List<ScanPage>, out: Uri, progress: (Float) -> Unit) {
        PDDocument().use { doc ->
            pages.forEachIndexed { i, p ->
                val b = render(ctx, p, 2200)
                val w = 595f; val h = w * b.height / b.width
                val page = PDPage(PDRectangle(w, h)); doc.addPage(page)
                val img = JPEGFactory.createFromImage(doc, b, 0.8f)
                PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, w, h) }
                b.recycle(); progress((i + 1f) / pages.size)
            }
            (ctx.contentResolver.openOutputStream(out) ?: throw IOException("Cannot create output file")).use { doc.save(it) }
        }
    }

    fun exportJpeg(ctx: Context, p: ScanPage, out: Uri) {
        val b = render(ctx, p, 2400)
        (ctx.contentResolver.openOutputStream(out) ?: throw IOException("Cannot create output file")).use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        b.recycle()
    }
}
