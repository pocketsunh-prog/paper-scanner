package com.paperscanner.processing

import android.graphics.RectF
import java.nio.ByteBuffer
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * How the detected page lies in the upright (screen) frame.
 */
enum class DocumentOrientation {
    /** Long edge runs left-to-right. */
    HORIZONTAL,

    /** Long edge runs top-to-bottom. */
    VERTICAL,

    /** Not enough information. */
    UNKNOWN
}

data class DetectionResult(
    val found: Boolean,
    val bounds: RectF? = null,
    val confidence: Float = 0f,
    /**
     * The four corners of the rectangle fitted to the page, clockwise from the
     * north-west corner, normalized 0..1 against the *analysis frame* - i.e. before
     * rotation is applied. The capture step crops to exactly this rectangle.
     */
    val corners: List<Pair<Float, Float>>? = null,
    val orientation: DocumentOrientation = DocumentOrientation.UNKNOWN,
    /** Page tilt in degrees, -45..45, relative to the upright frame. 0 = square on. */
    val skewDegrees: Float = 0f
)

object DocumentDetector {

    /** The page has to cover at least this fraction of the frame to count as found. */
    private const val MIN_COVERAGE = 0.08f

    /** Coverage that saturates the confidence score. */
    private const val FULL_CONFIDENCE_COVERAGE = 0.5f

    /**
     * How much of the fitted rectangle the page has to actually fill. This is what
     * rejects a hull built from unrelated bright objects scattered around the frame.
     */
    private const val MIN_RECT_FILL = 0.65f

    /** Bright/dark patches smaller than this share of the frame are ignored. */
    private const val MIN_COMPONENT_COVERAGE = 0.02f

    /**
     * A blob spanning this much of the frame in *both* directions is the background
     * wrapping around everything, not a page. A page that pokes slightly out of frame
     * still passes, so a nearly-framed document is not thrown away.
     */
    private const val SPAN_REJECT_FRACTION = 0.98f

    fun detectDocument(
        luminance: IntArray,
        width: Int,
        height: Int,
        rotationDegrees: Int = 0
    ): DetectionResult {
        if (width <= 0 || height <= 0 || luminance.size != width * height) {
            return DetectionResult(false)
        }

        // Light smoothing so sensor noise doesn't speckle the page/background split
        val radius = max(1, min(width, height) / 64)
        val smoothed = boxBlur(luminance, width, height, radius)
        val threshold = otsuThreshold(smoothed)

        val frameArea = width.toFloat() * height.toFloat()
        val minComponentPixels = (frameArea * MIN_COMPONENT_COVERAGE).toInt()

        // A page is usually the bright side, but a dark page on a pale desk is just as
        // valid. Both sides are measured and the better rectangle wins.
        val bright = pageCandidate(smoothed, width, height, minComponentPixels) { it > threshold }
        val dark = pageCandidate(smoothed, width, height, minComponentPixels) { it <= threshold }

        val page = listOfNotNull(bright, dark).maxByOrNull { it.score } ?: return DetectionResult(false)

        val coverage = page.rectangleArea / frameArea
        val confidence = (
                (coverage / FULL_CONFIDENCE_COVERAGE).coerceIn(0f, 1f) * page.fill
                ).coerceIn(0f, 1f)

        // Corners stay in frame space so the capture step can map them onto the photo
        val normalized = page.corners.map { Pair(it.first / width, it.second / height) }

        return DetectionResult(
            found = true,
            bounds = cornersToBounds(normalized),
            confidence = confidence,
            corners = normalized,
            orientation = orientationOf(page.longDirection, rotationDegrees),
            skewDegrees = skewOf(page.longDirection, rotationDegrees)
        )
    }

    /**
     * Rotate a normalized point from analysis-frame space into upright (screen) space.
     * [rotationDegrees] is clockwise, matching `ImageInfo.getRotationDegrees()`.
     */
    fun toUpright(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (normalizeRotation(rotationDegrees)) {
            90 -> Pair(1f - y, x)
            180 -> Pair(1f - x, 1f - y)
            270 -> Pair(y, 1f - x)
            else -> Pair(x, y)
        }

    /** Size of the frame once [rotationDegrees] has been applied. */
    fun uprightSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> {
        val rotation = normalizeRotation(rotationDegrees)
        return if (rotation == 90 || rotation == 270) Pair(height, width) else Pair(width, height)
    }

    private fun normalizeRotation(rotationDegrees: Int): Int =
        ((rotationDegrees % 360) + 360) % 360

    // ------------------------------------------------------------- luminance

    /**
     * Pull the Y (luminance) plane out of a YUV_420_888 frame.
     *
     * The plane is usually padded: `rowStride` can exceed the image width and
     * `pixelStride` is not always 1, so indexing straight through the buffer
     * would shear the image and wreck detection.
     */
    fun yuvToLuminance(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int = width,
        pixelStride: Int = 1
    ): IntArray {
        val luminance = IntArray(width * height)
        if (width <= 0 || height <= 0) return luminance

        val rowStep = if (rowStride > 0) rowStride else width
        val pixelStep = if (pixelStride > 0) pixelStride else 1
        val start = buffer.position()
        val limit = buffer.limit()

        for (y in 0 until height) {
            val rowStart = start + y * rowStep
            val outRow = y * width
            for (x in 0 until width) {
                val index = rowStart + x * pixelStep
                luminance[outRow + x] =
                    if (index in start until limit) buffer.get(index).toInt() and 0xFF else 0
            }
        }
        return luminance
    }

    // ---------------------------------------------------------------- blur

    /**
     * Separable box blur using a sliding window, so the cost does not grow with the
     * radius. Edge pixels are clamped, never left at zero.
     */
    private fun boxBlur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius <= 0) return source.copyOf()

        val window = radius * 2 + 1
        val horizontal = IntArray(source.size)

        for (y in 0 until height) {
            val row = y * width
            var sum = 0
            for (i in -radius..radius) {
                sum += source[row + i.coerceIn(0, width - 1)]
            }
            for (x in 0 until width) {
                horizontal[row + x] = sum / window
                val leaving = (x - radius).coerceIn(0, width - 1)
                val entering = (x + radius + 1).coerceIn(0, width - 1)
                sum += source[row + entering] - source[row + leaving]
            }
        }

        val result = IntArray(source.size)
        for (x in 0 until width) {
            var sum = 0
            for (i in -radius..radius) {
                sum += horizontal[i.coerceIn(0, height - 1) * width + x]
            }
            for (y in 0 until height) {
                result[y * width + x] = sum / window
                val leaving = (y - radius).coerceIn(0, height - 1)
                val entering = (y + radius + 1).coerceIn(0, height - 1)
                sum += horizontal[entering * width + x] - horizontal[leaving * width + x]
            }
        }

        return result
    }

    /** Otsu's method over luminance values. */
    private fun otsuThreshold(values: IntArray): Int {
        val histogram = IntArray(256)
        for (value in values) {
            histogram[value.coerceIn(0, 255)]++
        }

        val total = values.size
        if (total == 0) return 128

        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * histogram[i]

        var sumB = 0.0
        var weightB = 0
        var maxVariance = 0.0
        var threshold = 128

        for (i in 0..255) {
            weightB += histogram[i]
            if (weightB == 0) continue
            val weightF = total - weightB
            if (weightF == 0) break

            sumB += i.toDouble() * histogram[i]
            val meanB = sumB / weightB
            val meanF = (sum - sumB) / weightF
            val variance = weightB.toDouble() * weightF * (meanB - meanF) * (meanB - meanF)

            if (variance > maxVariance) {
                maxVariance = variance
                threshold = i
            }
        }

        return threshold
    }

    // ------------------------------------------------------------- geometry

    private class Component(
        val size: Int,
        val boundary: List<Pair<Float, Float>>,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    ) {
        /** True when this blob covers essentially the whole frame - i.e. background. */
        fun spansFrame(width: Int, height: Int): Boolean {
            val spansX = (maxX - minX + 1) >= width * SPAN_REJECT_FRACTION
            val spansY = (maxY - minY + 1) >= height * SPAN_REJECT_FRACTION
            return spansX && spansY
        }
    }

    private class PageCandidate(
        val corners: List<Pair<Float, Float>>,
        val rectangleArea: Float,
        val fill: Float,
        /** Unit vector along the rectangle's long axis, in frame pixel space. */
        val longDirection: Pair<Float, Float>
    ) {
        val score: Float get() = rectangleArea * fill
    }

    /**
     * Build one page candidate from a side of the luminance threshold.
     *
     * Every sizeable blob on that side is gathered and the rectangle is fitted to the
     * whole group. That is deliberate: a dark banner or table line running across a
     * page splits it into several blobs, and fitting each one separately finds half a
     * page. Blobs that belong to unrelated objects end up far apart, which drags the
     * fill ratio down and gets the candidate rejected.
     */
    private fun pageCandidate(
        smoothed: IntArray,
        width: Int,
        height: Int,
        minComponentPixels: Int,
        isOnPageSide: (Int) -> Boolean
    ): PageCandidate? {
        val mask = BooleanArray(smoothed.size)
        for (i in mask.indices) {
            mask[i] = isOnPageSide(smoothed[i])
        }

        val visited = BooleanArray(smoothed.size)
        val pageBoundary = mutableListOf<Pair<Float, Float>>()
        var pagePixels = 0

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (!mask[index] || visited[index]) continue

                val component = collectEnclosedComponent(mask, visited, x, y, width, height)
                if (component.size < minComponentPixels) continue
                if (component.spansFrame(width, height)) continue

                pageBoundary.addAll(component.boundary)
                pagePixels += component.size
            }
        }

        if (pageBoundary.size < 3) return null

        val hull = convexHull(pageBoundary) ?: return null
        val rect = minAreaRectangle(hull) ?: return null

        val frameArea = width.toFloat() * height.toFloat()
        if (rect.area < frameArea * MIN_COVERAGE) return null
        if (min(rect.halfWidth, rect.halfHeight) * 2f < min(width, height) * 0.15f) return null

        val fill = (pagePixels / rect.area).coerceIn(0f, 1f)
        if (fill < MIN_RECT_FILL) return null

        return PageCandidate(
            corners = orderCorners(rect.corners()),
            rectangleArea = rect.area,
            fill = fill,
            longDirection = if (rect.uIsLong) Pair(rect.ux, rect.uy) else Pair(rect.vx, rect.vy)
        )
    }

    /**
     * Flood fill one blob. Only boundary pixels are kept - they are all the convex hull
     * needs, and it keeps memory flat no matter how large the page is.
     */
    private fun collectEnclosedComponent(
        mask: BooleanArray,
        visited: BooleanArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int
    ): Component {
        val queue = ArrayDeque<Int>()
        val boundary = mutableListOf<Pair<Float, Float>>()
        var size = 0

        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY

        val startIndex = startY * width + startX
        queue.add(startIndex)
        visited[startIndex] = true

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width
            size++

            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y

            val leftInside = x > 0 && mask[index - 1]
            val rightInside = x < width - 1 && mask[index + 1]
            val upInside = y > 0 && mask[index - width]
            val downInside = y < height - 1 && mask[index + width]

            if (!leftInside || !rightInside || !upInside || !downInside) {
                boundary.add(Pair(x.toFloat(), y.toFloat()))
            }

            for (dy in -1..1) {
                val nextY = y + dy
                if (nextY < 0 || nextY >= height) continue
                val rowOffset = nextY * width
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nextX = x + dx
                    if (nextX < 0 || nextX >= width) continue
                    val neighbour = rowOffset + nextX
                    if (mask[neighbour] && !visited[neighbour]) {
                        visited[neighbour] = true
                        queue.add(neighbour)
                    }
                }
            }
        }

        return Component(size, boundary, minX, maxX, minY, maxY)
    }

    /** Andrew's monotone chain. Returns the hull, or null if degenerate. */
    private fun convexHull(points: List<Pair<Float, Float>>): List<Pair<Float, Float>>? {
        if (points.size < 3) return null

        val sorted = points.sortedWith(compareBy({ it.first }, { it.second }))

        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)

        val lower = mutableListOf<Pair<Float, Float>>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }

        val upper = mutableListOf<Pair<Float, Float>>()
        for (i in sorted.indices.reversed()) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }

        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)

        val hull = lower + upper
        return if (hull.size >= 3) hull else null
    }

    /** An axis pair plus half extents describing a rotated rectangle. */
    private class OrientedRect(
        val centerX: Float,
        val centerY: Float,
        val ux: Float,
        val uy: Float,
        val vx: Float,
        val vy: Float,
        val halfWidth: Float,
        val halfHeight: Float
    ) {
        val area: Float get() = 4f * halfWidth * halfHeight
        val uIsLong: Boolean get() = halfWidth >= halfHeight

        fun corners(): List<Pair<Float, Float>> = listOf(
            Pair(centerX - ux * halfWidth - vx * halfHeight, centerY - uy * halfWidth - vy * halfHeight),
            Pair(centerX + ux * halfWidth - vx * halfHeight, centerY + uy * halfWidth - vy * halfHeight),
            Pair(centerX + ux * halfWidth + vx * halfHeight, centerY + uy * halfWidth + vy * halfHeight),
            Pair(centerX - ux * halfWidth + vx * halfHeight, centerY - uy * halfWidth + vy * halfHeight)
        )
    }

    /**
     * Minimum-area rectangle around [hull], via rotating calipers: try each hull edge
     * as the rectangle's axis and keep the tightest fit. This is what makes a tilted
     * page report a rectangle that hugs it, instead of an axis-aligned box full of
     * background.
     */
    private fun minAreaRectangle(hull: List<Pair<Float, Float>>): OrientedRect? {
        if (hull.size < 3) return null

        var best: OrientedRect? = null
        var bestArea = Float.MAX_VALUE

        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]

            val edgeX = b.first - a.first
            val edgeY = b.second - a.second
            val length = hypot(edgeX, edgeY)
            if (length <= 0f) continue

            val ux = edgeX / length
            val uy = edgeY / length
            val vx = -uy
            val vy = ux

            var minU = Float.MAX_VALUE
            var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE

            for (p in hull) {
                val du = p.first * ux + p.second * uy
                val dv = p.first * vx + p.second * vy
                if (du < minU) minU = du
                if (du > maxU) maxU = du
                if (dv < minV) minV = dv
                if (dv > maxV) maxV = dv
            }

            val extentU = maxU - minU
            val extentV = maxV - minV
            val area = extentU * extentV
            if (area <= 0f || area >= bestArea) continue

            val centerU = (minU + maxU) / 2f
            val centerV = (minV + maxV) / 2f

            bestArea = area
            best = OrientedRect(
                centerX = centerU * ux + centerV * vx,
                centerY = centerU * uy + centerV * vy,
                ux = ux,
                uy = uy,
                vx = vx,
                vy = vy,
                halfWidth = extentU / 2f,
                halfHeight = extentV / 2f
            )
        }

        return best
    }

    /**
     * Sort clockwise starting from the north-west-most corner so callers can rely on
     * the order top-left, top-right, bottom-right, bottom-left.
     */
    private fun orderCorners(quad: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val cx = quad.sumOf { it.first.toDouble() }.toFloat() / quad.size
        val cy = quad.sumOf { it.second.toDouble() }.toFloat() / quad.size

        val byAngle = quad.sortedBy {
            atan2((it.second - cy).toDouble(), (it.first - cx).toDouble())
        }

        val startIndex = byAngle.indices.minByOrNull {
            byAngle[it].first + byAngle[it].second
        } ?: 0

        return List(byAngle.size) { byAngle[(startIndex + it) % byAngle.size] }
    }

    private fun cornersToBounds(corners: List<Pair<Float, Float>>): RectF {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (c in corners) {
            if (c.first < minX) minX = c.first
            if (c.first > maxX) maxX = c.first
            if (c.second < minY) minY = c.second
            if (c.second > maxY) maxY = c.second
        }
        return RectF(minX, minY, maxX, maxY)
    }

    // ---------------------------------------------------------- orientation

    /** Rotate a direction vector (not a point) into upright space. */
    private fun rotateDirection(dx: Float, dy: Float, rotationDegrees: Int): Pair<Float, Float> =
        when (normalizeRotation(rotationDegrees)) {
            90 -> Pair(-dy, dx)
            180 -> Pair(-dx, -dy)
            270 -> Pair(dy, -dx)
            else -> Pair(dx, dy)
        }

    /**
     * Angle of the page's long axis in the upright frame, folded into 0..180 degrees.
     *
     * [longDirection] is already a unit vector in frame *pixel* space and frame pixels
     * are square, so it must not be scaled by the frame size again - doing that sheared
     * the angle (an 18 degree page reported as 13.7 degrees).
     */
    private fun longAxisAngle(longDirection: Pair<Float, Float>, rotationDegrees: Int): Float {
        val direction = rotateDirection(longDirection.first, longDirection.second, rotationDegrees)

        val degrees = Math.toDegrees(
            atan2(direction.second.toDouble(), direction.first.toDouble())
        ).toFloat()

        return ((degrees % 180f) + 180f) % 180f
    }

    private fun orientationOf(
        longDirection: Pair<Float, Float>,
        rotationDegrees: Int
    ): DocumentOrientation {
        if (longDirection.first == 0f && longDirection.second == 0f) {
            return DocumentOrientation.UNKNOWN
        }

        val angle = longAxisAngle(longDirection, rotationDegrees)

        return if (angle < 45f || angle > 135f) {
            DocumentOrientation.HORIZONTAL
        } else {
            DocumentOrientation.VERTICAL
        }
    }

    /** Tilt of the page relative to the nearest axis, folded into -45..45 degrees. */
    private fun skewOf(longDirection: Pair<Float, Float>, rotationDegrees: Int): Float {
        val angle = longAxisAngle(longDirection, rotationDegrees)

        var tilt = if (angle <= 90f) angle else angle - 180f
        if (tilt > 45f) tilt -= 90f
        if (tilt < -45f) tilt += 90f
        return tilt
    }
}
