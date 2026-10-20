package dev.olaf.oscanner.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A snowman holding a page, as if drawn in chalk: every line a little wobbly, laid down in a few
 * faint broken passes. Chalk-white on the dark theme, pencil-gray on the light one; the carrot
 * and twigs keep a muted color of their own. Decorative: it says nothing the text beside it
 * doesn't.
 */
@Composable
fun ChalkSnowman(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val chalk = if (dark) Color(0xFFEDE6DC) else Color(0xFF5E544C)
    val carrot = if (dark) Color(0xFFE59A73) else Color(0xFFD9774E)
    val twig = if (dark) Color(0xFFB89478) else Color(0xFF8A6448)
    val strokes = remember { snowmanStrokes() }
    Canvas(modifier) {
        // Drawn in a 200 × 220 box, scaled to fit and centered.
        val scale = min(size.width / BOX_W, size.height / BOX_H)
        translate((size.width - BOX_W * scale) / 2, (size.height - BOX_H * scale) / 2) {
            withTransform({ scale(scale, scale, Offset.Zero) }) {
                strokes.forEach { stroke ->
                    val color = when (stroke.ink) {
                        Ink.Chalk -> chalk
                        Ink.Carrot -> carrot
                        Ink.Twig -> twig
                    }
                    chalkLine(stroke.path, color, stroke.width, stroke.seed)
                }
            }
        }
    }
}

private const val BOX_W = 200f
private const val BOX_H = 220f

private enum class Ink { Chalk, Carrot, Twig }

private class ChalkStroke(val path: Path, val width: Float, val ink: Ink, val seed: Int)

/** One line in chalk: a broken main pass and two fainter, slightly offset ones beside it. */
private fun DrawScope.chalkLine(path: Path, color: Color, width: Float, seed: Int) {
    val passes = listOf(
        Triple(Offset.Zero, 1f, 0.8f),
        Triple(Offset(0.7f, -0.5f), 0.55f, 0.35f),
        Triple(Offset(-0.6f, 0.6f), 0.4f, 0.3f),
    )
    passes.forEachIndexed { i, (offset, widthShare, alpha) ->
        translate(offset.x, offset.y) {
            drawPath(
                path,
                color.copy(alpha = alpha),
                style = Stroke(
                    width = width * widthShare,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    // Little gaps along the line, where chalk skips over the board's grain.
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f + i * 2f, 1.6f + i * 0.6f), (seed * 3 + i * 5).toFloat()),
                ),
            )
        }
    }
}

/** A closed, slightly lumpy loop through points around a center: drawn by hand, not a compass. */
private fun wobblyLoop(cx: Float, cy: Float, rx: Float, ry: Float, seed: Int, start: Double = 0.0, sweep: Double = 2 * PI): Path {
    val random = Random(seed)
    val closed = sweep >= 2 * PI
    val n = 12
    val points = (0..n).map { i ->
        val a = start + sweep * i / n
        val wobble = 1f + (random.nextFloat() - 0.5f) * 0.06f
        Offset(cx + (rx * wobble * cos(a)).toFloat(), cy + (ry * wobble * sin(a)).toFloat())
    }.let { if (closed) it.dropLast(1) else it }
    return smooth(points, closed)
}

/** Catmull–Rom through [points], as cubic Béziers: a curve with no corners. */
private fun smooth(points: List<Offset>, closed: Boolean): Path {
    val path = Path()
    val n = points.size
    fun at(i: Int) = if (closed) points[(i + n) % n] else points[i.coerceIn(0, n - 1)]
    path.moveTo(points[0].x, points[0].y)
    val segments = if (closed) n else n - 1
    for (i in 0 until segments) {
        val p0 = at(i - 1)
        val p1 = at(i)
        val p2 = at(i + 1)
        val p3 = at(i + 2)
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6,
            p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6,
            p2.x, p2.y,
        )
    }
    if (closed) path.close()
    return path
}

private fun curve(vararg xy: Float): Path = smooth(xy.toList().chunked(2).map { Offset(it[0], it[1]) }, closed = false)

private fun snowmanStrokes(): List<ChalkStroke> {
    val s = mutableListOf<ChalkStroke>()
    var seed = 1
    fun add(path: Path, width: Float, ink: Ink = Ink.Chalk) = s.add(ChalkStroke(path, width, ink, seed++))

    // Body and head.
    add(wobblyLoop(100f, 160f, 50f, 46f, 11), 3.2f)
    add(wobblyLoop(100f, 84f, 34f, 32f, 12), 3.2f)
    // Twig hair: three, the middle one tallest, one with a little fork.
    add(curve(90f, 54f, 86f, 44f, 80f, 34f), 2.6f, Ink.Twig)
    add(curve(100f, 52f, 101f, 40f, 103f, 28f), 2.6f, Ink.Twig)
    add(curve(110f, 54f, 116f, 44f, 122f, 36f), 2.6f, Ink.Twig)
    add(curve(118f, 41f, 125f, 42f, 129f, 40f), 2f, Ink.Twig)
    // Eyes: small filled-in loops.
    add(wobblyLoop(88f, 78f, 3.2f, 3.6f, 21), 3.4f)
    add(wobblyLoop(112f, 78f, 3.2f, 3.6f, 22), 3.4f)
    // Carrot nose, pointing off to the side, with two little lines across it.
    add(curve(100f, 87f, 116f, 89f, 134f, 93f), 3f, Ink.Carrot)
    add(curve(100f, 93f, 116f, 93f, 134f, 93f), 3f, Ink.Carrot)
    add(curve(110f, 88f, 109f, 92f), 1.6f, Ink.Carrot)
    add(curve(120f, 90f, 119f, 93f), 1.6f, Ink.Carrot)
    // A smile.
    add(wobblyLoop(100f, 96f, 14f, 9f, 31, start = PI * 0.2, sweep = PI * 0.6), 2.4f)
    // Buttons.
    add(wobblyLoop(100f, 140f, 2.6f, 2.6f, 41), 3.2f)
    add(wobblyLoop(100f, 158f, 2.6f, 2.6f, 42), 3.2f)
    add(wobblyLoop(100f, 176f, 2.6f, 2.6f, 43), 3.2f)
    // Left arm, waving.
    add(curve(56f, 140f, 40f, 126f, 24f, 106f), 2.6f, Ink.Twig)
    add(curve(34f, 120f, 26f, 122f, 20f, 120f), 2f, Ink.Twig)
    // Right arm, holding a page out.
    add(curve(144f, 140f, 158f, 132f, 168f, 128f), 2.6f, Ink.Twig)
    // The page: a slightly tilted sheet with a folded corner and three lines of writing.
    add(curve(160f, 108f, 186f, 102f, 194f, 106f), 2.4f)
    add(curve(194f, 106f, 199f, 144f), 2.4f)
    add(curve(199f, 144f, 186f, 147f, 168f, 150f), 2.4f)
    add(curve(168f, 150f, 162f, 128f, 160f, 108f), 2.4f)
    add(curve(186f, 102f, 187f, 110f, 194f, 106f), 1.8f)
    add(curve(167f, 118f, 177f, 116f, 188f, 114f), 1.6f)
    add(curve(168f, 126f, 178f, 124f, 190f, 122f), 1.6f)
    add(curve(169f, 134f, 177f, 133f, 184f, 131f), 1.6f)
    // Snowflakes.
    listOf(Offset(30f, 40f), Offset(162f, 40f), Offset(20f, 180f), Offset(176f, 196f)).forEachIndexed { i, c ->
        val r = if (i % 2 == 0) 5f else 4f
        for (k in 0 until 3) {
            val a = PI / 3 * k + 0.3 * i
            add(curve(c.x - (r * cos(a)).toFloat(), c.y - (r * sin(a)).toFloat(), c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat()), 1.4f)
        }
    }
    // The ground.
    add(curve(30f, 207f, 70f, 203f, 100f, 206f, 140f, 203f, 172f, 207f), 2.2f)
    return s
}
