package com.paperscanner.processing

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class DetectionResult(
    val found: Boolean,
    val bounds: RectF? = null,
    val confidence: Float = 0f,
    val corners: List<Pair<Float, Float>>? = null
)

data class DocumentCorners(
    val topLeft: Pair<Float, Float>,
    val topRight: Pair<Float, Float>,
    val bottomRight: Pair<Float, Float>,
    val bottomLeft: Pair<Float, Float>
)

object DocumentDetector {

    fun detectDocument(
        luminance: IntArray,
        width: Int,
        height: Int
    ): DetectionResult {
        if (luminance.size != width * height) {
            return DetectionResult(false)
        }

        // Step 1: Apply Gaussian blur to reduce noise
        val blurred = gaussianBlur(luminance, width, height)

        // Step 2: Calculate edge map using Sobel operator
        val edgeMap = calculateEdges(blurred, width, height)

        // Step 3: Apply adaptive threshold using Otsu's method
        val binaryEdges = thresholdEdges(edgeMap, width, height)

        // Step 4: Find the largest quadrilateral contour
        val documentCorners = findDocumentCorners(binaryEdges, width, height)

        if (documentCorners != null) {
            val bounds = cornersToBounds(documentCorners)
            val area = (bounds.width() * bounds.height())
            val frameArea = (width * height).toFloat()
            val coverage = area / frameArea
            val confidence = (coverage * 2f).coerceIn(0f, 1f)

            val cornerList = listOf(
                documentCorners.topLeft,
                documentCorners.topRight,
                documentCorners.bottomRight,
                documentCorners.bottomLeft
            )

            return DetectionResult(true, bounds, confidence, cornerList)
        }

        return DetectionResult(false)
    }

    private fun gaussianBlur(luminance: IntArray, width: Int, height: Int): IntArray {
        val result = IntArray(width * height)
        val kernel = intArrayOf(1, 2, 1, 2, 4, 2, 1, 2, 1)
        val kernelSum = 16

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var sum = 0
                var ki = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        sum += luminance[(y + dy) * width + (x + dx)] * kernel[ki]
                        ki++
                    }
                }
                result[y * width + x] = sum / kernelSum
            }
        }
        return result
    }

    private fun calculateEdges(luminance: IntArray, width: Int, height: Int): FloatArray {
        val edges = FloatArray(width * height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x

                val gx = (-luminance[(y - 1) * width + (x - 1)] + luminance[(y - 1) * width + (x + 1)]
                        - 2 * luminance[y * width + (x - 1)] + 2 * luminance[y * width + (x + 1)]
                        - luminance[(y + 1) * width + (x - 1)] + luminance[(y + 1) * width + (x + 1)])

                val gy = (-luminance[(y - 1) * width + (x - 1)] - 2 * luminance[(y - 1) * width + x] - luminance[(y - 1) * width + (x + 1)]
                        + luminance[(y + 1) * width + (x - 1)] + 2 * luminance[(y + 1) * width + x] + luminance[(y + 1) * width + (x + 1)])

                edges[idx] = min(255f, abs(gx) + abs(gy).toFloat())
            }
        }

        return edges
    }

    private fun thresholdEdges(edgeMap: FloatArray, width: Int, height: Int): BooleanArray {
        val binary = BooleanArray(width * height)
        val threshold = calculateOtsuThreshold(edgeMap, width, height)

        for (i in edgeMap.indices) {
            binary[i] = edgeMap[i] > threshold
        }

        return binary
    }

    private fun calculateOtsuThreshold(edgeMap: FloatArray, width: Int, height: Int): Float {
        val histogram = IntArray(256)
        for (value in edgeMap) {
            histogram[value.coerceIn(0f, 255f).toInt()]++
        }

        val total = width * height
        var sum = 0
        for (i in 0..255) {
            sum += i * histogram[i]
        }

        var sumB = 0
        var wB = 0
        var wF: Int
        var maxVariance = 0f
        var threshold = 30f

        for (i in 0..255) {
            wB += histogram[i]
            if (wB == 0) continue

            wF = total - wB
            if (wF == 0) break

            sumB += i * histogram[i]
            val mB = sumB.toFloat() / wB
            val mF = (sum - sumB).toFloat() / wF

            val variance = wB.toFloat() * wF.toFloat() * (mB - mF) * (mB - mF)
            if (variance > maxVariance) {
                maxVariance = variance
                threshold = i.toFloat()
            }
        }

        return threshold.coerceAtLeast(20f)
    }

    private fun findDocumentCorners(binaryEdges: BooleanArray, width: Int, height: Int): DocumentCorners? {
        val margin = (min(width, height) * 0.03f).toInt()
        val minSize = (min(width, height) * 0.15f).toInt()

        val visited = BooleanArray(width * height)
        var bestCorners: DocumentCorners? = null
        var bestScore = 0f

        for (y in margin until height - margin step 2) {
            for (x in margin until width - margin step 2) {
                val idx = y * width + x
                if (binaryEdges[idx] && !visited[idx]) {
                    val contour = traceContour(binaryEdges, visited, x, y, width, height) ?: continue

                    if (contour.size < 20) continue

                    // Find 4 corners of the contour
                    val corners = findFourCorners(contour, width, height) ?: continue

                    val w = distance(corners.topLeft, corners.topRight) * width
                    val h = distance(corners.topLeft, corners.bottomLeft) * height

                    if (w < minSize || h < minSize) continue

                    val area = w * h
                    val rectangularity = calculateRectangularity(contour, corners, width, height)
                    val score = area * rectangularity

                    if (score > bestScore) {
                        bestScore = score
                        bestCorners = corners
                    }
                }
            }
        }

        return bestCorners
    }

    private fun traceContour(
        binaryEdges: BooleanArray,
        visited: BooleanArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int
    ): List<Pair<Int, Int>>? {
        val contour = mutableListOf<Pair<Int, Int>>()
        val queue = ArrayDeque<Int>()
        queue.add(startY * width + startX)
        visited[startY * width + startX] = true

        var minX = startX
        var minY = startY
        var maxX = startX
        var maxY = startY

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % width
            val y = idx / width

            contour.add(Pair(x, y))
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)

            val neighborCoords = arrayOf(
                Pair(x - 1, y), Pair(x + 1, y),
                Pair(x, y - 1), Pair(x, y + 1)
            )

            for ((nx, ny) in neighborCoords) {
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                val nIdx = ny * width + nx
                if (binaryEdges[nIdx] && !visited[nIdx]) {
                    visited[nIdx] = true
                    queue.add(nIdx)
                }
            }
        }

        // Filter: check if contour is reasonably rectangular
        val boundingArea = (maxX - minX) * (maxY - minY)
        if (boundingArea == 0) return null

        val fillRatio = contour.size.toFloat() / boundingArea
        if (fillRatio < 0.3f) return null

        return contour
    }

    private fun findFourCorners(
        contour: List<Pair<Int, Int>>,
        width: Int,
        height: Int
    ): DocumentCorners? {
        if (contour.isEmpty()) return null

        // Find center of mass
        var cx = 0f
        var cy = 0f
        for ((x, y) in contour) {
            cx += x
            cy += y
        }
        cx /= contour.size
        cy /= contour.size

        // Find 4 extreme points in each quadrant
        var topLeft = Pair(Int.MAX_VALUE, Int.MAX_VALUE)
        var topRight = Pair(Int.MIN_VALUE, Int.MAX_VALUE)
        var bottomLeft = Pair(Int.MAX_VALUE, Int.MIN_VALUE)
        var bottomRight = Pair(Int.MIN_VALUE, Int.MIN_VALUE)

        var maxTL = -1f
        var maxTR = -1f
        var maxBL = -1f
        var maxBR = -1f

        for ((x, y) in contour) {
            val nx = x / width.toFloat()
            val ny = y / height.toFloat()
            val ncx = cx / width.toFloat()
            val ncy = cy / height.toFloat()

            val dist = (x - cx) * (x - cx) + (y - cy) * (y - cy)

            when {
                nx <= ncx && ny <= ncy -> {
                    if (dist > maxTL) {
                        maxTL = dist
                        topLeft = Pair(x, y)
                    }
                }
                nx > ncx && ny <= ncy -> {
                    if (dist > maxTR) {
                        maxTR = dist
                        topRight = Pair(x, y)
                    }
                }
                nx <= ncx && ny > ncy -> {
                    if (dist > maxBL) {
                        maxBL = dist
                        bottomLeft = Pair(x, y)
                    }
                }
                nx > ncx && ny > ncy -> {
                    if (dist > maxBR) {
                        maxBR = dist
                        bottomRight = Pair(x, y)
                    }
                }
            }
        }

        // Validate corners
        if (maxTL < 0f || maxTR < 0f || maxBL < 0f || maxBR < 0f) {
            return null
        }

        return DocumentCorners(
            topLeft = Pair(topLeft.first.toFloat() / width, topLeft.second.toFloat() / height),
            topRight = Pair(topRight.first.toFloat() / width, topRight.second.toFloat() / height),
            bottomRight = Pair(bottomRight.first.toFloat() / width, bottomRight.second.toFloat() / height),
            bottomLeft = Pair(bottomLeft.first.toFloat() / width, bottomLeft.second.toFloat() / height)
        )
    }

    private fun calculateRectangularity(
        contour: List<Pair<Int, Int>>,
        corners: DocumentCorners,
        width: Int,
        height: Int
    ): Float {
        val tl = corners.topLeft
        val tr = corners.topRight
        val br = corners.bottomRight
        val bl = corners.bottomLeft

        // Calculate expected rectangle area
        val rectArea = abs(
            (tl.first * (tr.second - br.second) +
                    tr.first * (br.second - tl.second) +
                    br.first * (tl.second - tr.second)) * width * height
        )

        if (rectArea <= 0) return 0f

        // Calculate contour area (approximate)
        val contourArea = contour.size.toFloat()

        return min(1f, contourArea / rectArea)
    }

    private fun cornersToBounds(corners: DocumentCorners): RectF {
        val minX = minOf(corners.topLeft.first, corners.bottomLeft.first)
        val minY = minOf(corners.topLeft.second, corners.topRight.second)
        val maxX = maxOf(corners.topRight.first, corners.bottomRight.first)
        val maxY = maxOf(corners.bottomLeft.second, corners.bottomRight.second)

        return RectF(minX, minY, maxX, maxY)
    }

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        return sqrt((a.first - b.first) * (a.first - b.first) + (a.second - b.second) * (a.second - b.second))
    }

    fun yuvToLuminance(yuvData: ByteArray, width: Int, height: Int): IntArray {
        val luminance = IntArray(width * height)
        for (i in 0 until width * height) {
            luminance[i] = yuvData[i].toInt() and 0xFF
        }
        return luminance
    }
}
