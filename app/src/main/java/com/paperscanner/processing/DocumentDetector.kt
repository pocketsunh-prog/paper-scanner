package com.paperscanner.processing

import android.graphics.RectF
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

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

    /** A page has to cover at least this fraction of the frame to count as detected. */
    private const val MIN_COVERAGE = 0.08f

    /** Coverage that saturates the confidence score. */
    private const val FULL_CONFIDENCE_COVERAGE = 0.5f

    /** How much of the fitted rectangle the detected shape must actually fill. */
    private const val MIN_RECT_FILL = 0.70f

    private const val MIN_COMPONENT_PIXELS = 40

    fun detectDocument(
        luminance: IntArray,
        width: Int,
        height: Int,
        rotationDegrees: Int = 0
    ): DetectionResult {
        if (width <= 0 || height <= 0 || luminance.size != width * height) {
            return DetectionResult(false)
        }

        // Step 1: smooth out sensor noise
        val blurred = gaussianBlur(luminance, width, height)

        // Step 2: gradient magnitude (Sobel)
        val edges = sobelEdges(blurred, width, height)

        // Step 3: split edges from flat areas (Otsu)
        val binary = binarize(edges)

        // Step 4: fit a rectangle to the most page-like shape in frame
        val page = findBestRectangle(binary, width, height) ?: return DetectionResult(false)

        val coverage = page.area / (width.toFloat() * height.toFloat())
        val confidence = (
                (coverage / FULL_CONFIDENCE_COVERAGE).coerceIn(0f, 1f) * page.fit
                ).coerceIn(0f, 1f)

        // Corners stay in frame space so the capture step can map them onto the photo
        val normalized = page.corners.map { Pair(it.first / width, it.second / height) }

        return DetectionResult(
            found = true,
            bounds = cornersToBounds(normalized),
            confidence = confidence,
            corners = normalized,
            orientation = orientationOf(page.longDirection, width, height, rotationDegrees),
            skewDegrees = skewOf(page.longDirection, width, height, rotationDegrees)
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
     * would shear the image and wreck edge detection.
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

    // ------------------------------------------------------------ edge stages

    private fun gaussianBlur(luminance: IntArray, width: Int, height: Int): IntArray {
        val result = IntArray(width * height)
        val kernel = intArrayOf(1, 2, 1, 2, 4, 2, 1, 2, 1)
        val kernelSum = 16

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var sum = 0
                var ki = 0
                for (dy in -1..1) {
                    val row = (y + dy) * width
                    for (dx in -1..1) {
                        sum += luminance[row + x + dx] * kernel[ki]
                        ki++
                    }
                }
                result[y * width + x] = sum / kernelSum
            }
        }
        return result
    }

    private fun sobelEdges(luminance: IntArray, width: Int, height: Int): FloatArray {
        val edges = FloatArray(width * height)

        for (y in 1 until height - 1) {
            val rowAbove = (y - 1) * width
            val rowHere = y * width
            val rowBelow = (y + 1) * width

            for (x in 1 until width - 1) {
                val tl = luminance[rowAbove + x - 1]
                val tc = luminance[rowAbove + x]
                val tr = luminance[rowAbove + x + 1]
                val ml = luminance[rowHere + x - 1]
                val mr = luminance[rowHere + x + 1]
                val bl = luminance[rowBelow + x - 1]
                val bc = luminance[rowBelow + x]
                val br = luminance[rowBelow + x + 1]

                val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
                val gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr)

                // Sobel peaks at 4 * 255, so divide down into the 0..255 range
                edges[rowHere + x] = min(255f, sqrt((gx * gx + gy * gy).toFloat()) / 4f)
            }
        }
        return edges
    }

    private fun binarize(edgeMap: FloatArray): BooleanArray {
        val threshold = otsuThreshold(edgeMap)
        val binary = BooleanArray(edgeMap.size)
        for (i in edgeMap.indices) {
            binary[i] = edgeMap[i] > threshold
        }
        return binary
    }

    private fun otsuThreshold(edgeMap: FloatArray): Float {
        val histogram = IntArray(256)
        for (value in edgeMap) {
            histogram[value.coerceIn(0f, 255f).toInt()]++
        }

        val total = edgeMap.size
        if (total == 0) return 20f

        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * histogram[i]

        var sumB = 0.0
        var wB = 0
        var maxVariance = 0.0
        var threshold = 30

        for (i in 0..255) {
            wB += histogram[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += i.toDouble() * histogram[i]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val variance = wB.toDouble() * wF * (mB - mF) * (mB - mF)

            if (variance > maxVariance) {
                maxVariance = variance
                threshold = i
            }
        }

        return threshold.coerceAtLeast(20).toFloat()
    }

    // ------------------------------------------------------------- geometry

    private class Component(
        val points: List<Pair<Float, Float>>,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    ) {
        val width get() = maxX - minX
        val height get() = maxY - minY
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

    private class RectangleCandidate(
        val corners: List<Pair<Float, Float>>,
        val area: Float,
        /** Fraction of the fitted rectangle actually filled by the detected shape. */
        val fit: Float,
        /** Unit vector along the rectangle's long axis, in frame space. */
        val longDirection: Pair<Float, Float>
    )

    private fun findBestRectangle(
        binary: BooleanArray,
        width: Int,
        height: Int
    ): RectangleCandidate? {
        val visited = BooleanArray(width * height)
        val frameArea = width.toFloat() * height.toFloat()
        val minArea = frameArea * MIN_COVERAGE
        val minSide = min(width, height) * 0.15f

        // Collect plausible candidates first; fitting a rectangle is the expensive part
        val components = mutableListOf<Component>()

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (!binary[index] || visited[index]) continue

                val component = collectComponent(binary, visited, x, y, width, height)
                if (component.points.size < MIN_COMPONENT_PIXELS) continue
                if (component.width * component.height < minArea) continue

                components.add(component)
            }
        }

        if (components.isEmpty()) return null

        var best: RectangleCandidate? = null
        var bestScore = 0f

        // Biggest first - the page is normally the largest thing in frame
        for (component in components.sortedByDescending { it.width * it.height }.take(5)) {
            val hull = convexHull(component.points) ?: continue
            if (hull.size < 3) continue

            val rect = minAreaRectangle(hull) ?: continue
            if (rect.area < minArea) continue
            if (rect.halfWidth < minSide / 2f || rect.halfHeight < minSide / 2f) continue

            // Reject shapes that only loosely resemble a rectangle (blobs, hands, shadows)
            val fit = (polygonArea(hull) / rect.area).coerceIn(0f, 1f)
            if (fit < MIN_RECT_FILL) continue

            val score = rect.area * fit
            if (score > bestScore) {
                bestScore = score
                best = RectangleCandidate(
                    corners = orderCorners(rect.corners()),
                    area = rect.area,
                    fit = fit,
                    longDirection = if (rect.uIsLong) {
                        Pair(rect.ux, rect.uy)
                    } else {
                        Pair(rect.vx, rect.vy)
                    }
                )
            }
        }

        return best
    }

    private fun collectComponent(
        binary: BooleanArray,
        visited: BooleanArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int
    ): Component {
        val points = mutableListOf<Pair<Float, Float>>()
        val queue = ArrayDeque<Int>()
        val startIndex = startY * width + startX

        queue.add(startIndex)
        visited[startIndex] = true

        var minX = startX.toFloat()
        var maxX = startX.toFloat()
        var minY = startY.toFloat()
        var maxY = startY.toFloat()

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val x = index % width
            val y = index / width

            points.add(Pair(x.toFloat(), y.toFloat()))
            if (x < minX) minX = x.toFloat()
            if (x > maxX) maxX = x.toFloat()
            if (y < minY) minY = y.toFloat()
            if (y > maxY) maxY = y.toFloat()

            if (x > 0) {
                val n = index - 1
                if (binary[n] && !visited[n]) { visited[n] = true; queue.add(n) }
            }
            if (x < width - 1) {
                val n = index + 1
                if (binary[n] && !visited[n]) { visited[n] = true; queue.add(n) }
            }
            if (y > 0) {
                val n = index - width
                if (binary[n] && !visited[n]) { visited[n] = true; queue.add(n) }
            }
            if (y < height - 1) {
                val n = index + width
                if (binary[n] && !visited[n]) { visited[n] = true; queue.add(n) }
            }
        }

        return Component(points, minX, maxX, minY, maxY)
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

    private fun polygonArea(quad: List<Pair<Float, Float>>): Float {
        if (quad.size < 3) return 0f
        var sum = 0f
        for (i in quad.indices) {
            val a = quad[i]
            val b = quad[(i + 1) % quad.size]
            sum += a.first * b.second - b.first * a.second
        }
        return abs(sum) / 2f
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
     * Measured on the page rather than the frame, so a tilted page reports correctly.
     */
    private fun longAxisAngle(
        longDirection: Pair<Float, Float>,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int
    ): Float {
        val direction = rotateDirection(longDirection.first, longDirection.second, rotationDegrees)
        val (uprightWidth, uprightHeight) = uprightSize(frameWidth, frameHeight, rotationDegrees)

        // Scale by the frame size so the angle reflects real proportions, not normalized ones
        val dx = direction.first * uprightWidth
        val dy = direction.second * uprightHeight

        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        return ((degrees % 180f) + 180f) % 180f
    }

    private fun orientationOf(
        longDirection: Pair<Float, Float>,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int
    ): DocumentOrientation {
        if (longDirection.first == 0f && longDirection.second == 0f) {
            return DocumentOrientation.UNKNOWN
        }

        val angle = longAxisAngle(longDirection, frameWidth, frameHeight, rotationDegrees)

        return if (angle < 45f || angle > 135f) {
            DocumentOrientation.HORIZONTAL
        } else {
            DocumentOrientation.VERTICAL
        }
    }

    /** Tilt of the page relative to the nearest axis, folded into -45..45 degrees. */
    private fun skewOf(
        longDirection: Pair<Float, Float>,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int
    ): Float {
        val angle = longAxisAngle(longDirection, frameWidth, frameHeight, rotationDegrees)

        var tilt = if (angle <= 90f) angle else angle - 180f
        if (tilt > 45f) tilt -= 90f
        if (tilt < -45f) tilt += 90f
        return tilt
    }
}
