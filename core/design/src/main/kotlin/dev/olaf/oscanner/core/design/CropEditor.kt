package dev.olaf.oscanner.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.model.Quad
import kotlin.math.min

/**
 * The whole photo with the page outline on top. Corners drag; while one moves, a 2× loupe in the
 * opposite top corner shows exactly where it sits, because the finger hides it.
 */
@Composable
fun CropEditor(
    image: ImageBitmap,
    quad: Quad,
    onMoveCorner: (index: Int, x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.background.copy(alpha = 0.6f)
    val density = LocalDensity.current
    val grabRadius = with(density) { 48.dp.toPx() }
    val loupeRadius = with(density) { 56.dp.toPx() }
    val currentQuad by rememberUpdatedState(quad)
    // The drag handler below is set up once; it must call the latest callback, or a caller that
    // works from the outline it was given would move earlier corners back.
    val currentOnMoveCorner by rememberUpdatedState(onMoveCorner)
    var active by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val scale = min(width / image.width, height / image.height)
        val shown = Rect(
            offset = Offset((width - image.width * scale) / 2, (height - image.height * scale) / 2),
            size = androidx.compose.ui.geometry.Size(image.width * scale, image.height * scale),
        )
        fun toScreen(x: Float, y: Float) = Offset(shown.left + x * shown.width, shown.top + y * shown.height)

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(shown) {
                    detectDragGestures(
                        onDragStart = { start ->
                            active = currentQuad.corners
                                .mapIndexed { i, c -> i to (toScreen(c.x, c.y) - start).getDistance() }
                                .filter { it.second <= grabRadius }
                                .minByOrNull { it.second }
                                ?.first
                        },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                        onDrag = { change, drag ->
                            val index = active ?: return@detectDragGestures
                            change.consume()
                            val c = currentQuad.corners[index]
                            val p = toScreen(c.x, c.y) + drag
                            currentOnMoveCorner(index, (p.x - shown.left) / shown.width, (p.y - shown.top) / shown.height)
                        },
                    )
                },
        ) {
            drawImage(
                image,
                dstOffset = IntOffset(shown.left.toInt(), shown.top.toInt()),
                dstSize = IntSize(shown.width.toInt(), shown.height.toInt()),
            )
            val points = quad.corners.map { toScreen(it.x, it.y) }
            val outline = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            // Dim what will be cut away.
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(shown)
                addPath(outline)
            }
            drawPath(outside, dim)
            drawPath(outline, lineColor, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
            points.forEachIndexed { i, p ->
                drawCircle(Color.White, radius = (if (i == active) 15 else 13).dp.toPx(), center = p)
                drawCircle(handleColor, radius = (if (i == active) 12 else 10).dp.toPx(), center = p)
            }

            val index = active ?: return@Canvas
            val target = points[index]
            val onLeft = target.x < size.width / 2
            val center = Offset(
                if (onLeft) size.width - loupeRadius - 16.dp.toPx() else loupeRadius + 16.dp.toPx(),
                loupeRadius + 16.dp.toPx(),
            )
            val lens = Path().apply { addOval(Rect(center, loupeRadius)) }
            clipPath(lens, ClipOp.Intersect) {
                drawRect(Color.Black, topLeft = center - Offset(loupeRadius, loupeRadius), size = androidx.compose.ui.geometry.Size(loupeRadius * 2, loupeRadius * 2))
                // 2× the on-screen scale, centered on the corner.
                val zoom = scale * 2
                val srcHalf = loupeRadius / zoom
                val cx = quad.corners[index].x * image.width
                val cy = quad.corners[index].y * image.height
                // Keep the source inside the photo; at an edge, the lens shows black beyond it.
                val left = (cx - srcHalf).coerceAtLeast(0f)
                val top = (cy - srcHalf).coerceAtLeast(0f)
                val right = (cx + srcHalf).coerceAtMost(image.width.toFloat())
                val bottom = (cy + srcHalf).coerceAtMost(image.height.toFloat())
                if (right - left >= 1f && bottom - top >= 1f) {
                    drawImage(
                        image,
                        srcOffset = IntOffset(left.toInt(), top.toInt()),
                        srcSize = IntSize((right - left).toInt(), (bottom - top).toInt()),
                        dstOffset = IntOffset(
                            (center.x + (left - cx) * zoom).toInt(),
                            (center.y + (top - cy) * zoom).toInt(),
                        ),
                        dstSize = IntSize(((right - left) * zoom).toInt(), ((bottom - top) * zoom).toInt()),
                    )
                }
                val arm = 10.dp.toPx()
                drawLine(handleColor, center - Offset(arm, 0f), center + Offset(arm, 0f), strokeWidth = 2.dp.toPx())
                drawLine(handleColor, center - Offset(0f, arm), center + Offset(0f, arm), strokeWidth = 2.dp.toPx())
            }
            drawCircle(Color.White, radius = loupeRadius, center = center, style = Stroke(2.dp.toPx()))
        }
    }
}
