package dev.olaf.oscanner.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.room.withTransaction
import dev.olaf.oscanner.core.data.db.DocumentEntity
import dev.olaf.oscanner.core.data.db.FolderEntity
import dev.olaf.oscanner.core.data.db.OscannerDatabase
import dev.olaf.oscanner.core.data.db.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * Debug-only synthetic library so layouts can be checked before the camera exists.
 * Every title and page here is made up; page images are drawn, not scanned.
 */
class SampleData(private val context: Context, private val db: OscannerDatabase) {

    private enum class Layout { Letter, Form, Receipt, Card, Board }

    private data class Sample(
        val title: String,
        val folder: String?,
        val pages: Int,
        val daysAgo: Int,
        val layout: Layout,
        val state: String = "Idle",
    )

    private val samples = listOf(
        Sample("Electricity bill, September", "Home", 2, 0, Layout.Letter),
        Sample("Biology worksheet 4", "School", 1, 1, Layout.Form, state = "ReadingText"),
        Sample("Hardware store receipt", "Receipts", 1, 3, Layout.Receipt),
        Sample("Lease agreement", "Home", 12, 9, Layout.Letter),
        Sample("Passport", null, 1, 21, Layout.Card),
        Sample("Sprint planning whiteboard", null, 3, 30, Layout.Board),
        Sample("Pharmacy receipt", "Receipts", 1, 44, Layout.Receipt),
        Sample("Course enrolment form", "School", 4, 70, Layout.Form),
    )

    suspend fun populate() = withContext(Dispatchers.IO) {
        clear()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        db.withTransaction {
            val folderIds = samples.mapNotNull { it.folder }.distinct()
                .associateWith { db.folders().insert(FolderEntity(name = it, createdAt = now)) }
            samples.forEachIndexed { index, sample ->
                val time = now - sample.daysAgo * day - index * 3_600_000L
                val docId = db.documents().insert(
                    DocumentEntity(
                        title = sample.title,
                        folderId = sample.folder?.let(folderIds::get),
                        createdAt = time,
                        updatedAt = time,
                        state = sample.state,
                    ),
                )
                val pages = (0 until sample.pages).map { position ->
                    val dir = File(context.filesDir, "docs/$docId/$position").apply { mkdirs() }
                    val thumb = File(dir, "thumb.webp")
                    val bitmap = drawPage(sample.layout, seed = index * 31 + position)
                    thumb.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 90, it) }
                    PageEntity(
                        documentId = docId,
                        position = position,
                        originalPath = thumb.path,
                        thumbPath = thumb.path,
                        width = bitmap.width,
                        height = bitmap.height,
                        sizeBytes = 180_000L + Random(index + position).nextLong(420_000L),
                    )
                }
                db.documents().insertPages(pages)
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        db.documents().deleteAll()
        db.folders().deleteAll()
        File(context.filesDir, "docs").deleteRecursively()
    }

    private fun drawPage(layout: Layout, seed: Int): Bitmap {
        val (w, h) = when (layout) {
            Layout.Receipt -> 160 to 360
            Layout.Card -> 320 to 224
            Layout.Board -> 320 to 228
            else -> 240 to 340
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val random = Random(seed)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3530.toInt() }
        val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB9B2A8.toInt() }
        canvas.drawColor(if (layout == Layout.Board) 0xFFF1F1EE.toInt() else 0xFFF6F2EA.toInt())

        fun lines(top: Float, bottom: Float, left: Float, right: Float, gap: Float) {
            var y = top
            while (y < bottom) {
                val end = left + (right - left) * (0.55f + random.nextFloat() * 0.45f)
                canvas.drawRoundRect(RectF(left, y, end, y + gap * 0.42f), 2f, 2f, faint)
                y += gap
            }
        }

        val m = w * 0.1f
        when (layout) {
            Layout.Letter -> {
                canvas.drawRect(m, m, m + w * 0.28f, m + 14f, ink)
                lines(m + 40f, h * 0.36f, m, w - m, 12f)
                canvas.drawRect(m, h * 0.42f, w - m, h * 0.42f + 1.5f, faint)
                lines(h * 0.46f, h - m, m, w - m, 12f)
            }
            Layout.Form -> {
                val box = Paint(faint).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
                canvas.drawRect(m, m, w - m, m + 12f, ink)
                var y = m + 34f
                while (y < h - m - 24f) {
                    canvas.drawRect(m, y, m + w * 0.22f, y + 6f, faint)
                    canvas.drawRect(m + w * 0.3f, y - 6f, w - m, y + 14f, box)
                    y += 34f
                }
            }
            Layout.Receipt -> {
                canvas.drawRect(w * 0.3f, m, w * 0.7f, m + 10f, ink)
                lines(m + 30f, h * 0.72f, m, w - m, 11f)
                canvas.drawRect(m, h * 0.76f, w - m, h * 0.76f + 1.5f, ink)
                canvas.drawRect(w * 0.55f, h * 0.8f, w - m, h * 0.8f + 10f, ink)
            }
            Layout.Card -> {
                canvas.drawRoundRect(RectF(m, m * 1.4f, m + w * 0.26f, h - m * 1.4f), 6f, 6f, faint)
                canvas.drawRect(m + w * 0.32f, m * 1.6f, w - m, m * 1.6f + 12f, ink)
                lines(m * 1.6f + 30f, h - m * 1.4f, m + w * 0.32f, w - m, 14f)
            }
            Layout.Board -> {
                val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                    strokeCap = Paint.Cap.ROUND
                }
                listOf(0xFF2F5BB7, 0xFF2E8B57, 0xFFC0392B).forEachIndexed { column, color ->
                    marker.color = color.toInt()
                    val x = m + column * (w - 2 * m) / 3f
                    canvas.drawLine(x + 6f, m + 8f, x + 52f, m + 8f, marker)
                    var y = m + 36f
                    while (y < h - m) {
                        canvas.drawLine(x + 6f, y, x + 20f + random.nextFloat() * 50f, y, marker)
                        y += 24f
                    }
                }
            }
        }
        return bitmap
    }
}
