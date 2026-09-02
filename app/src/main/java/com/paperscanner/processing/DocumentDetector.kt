package com.paperscanner.processing

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DetectionResult(
    val found: Boolean,
    val bounds: RectF? = null,
    val confidence: Float = 0f
)

object DocumentDetector {

    /**
     * Analyzes luminance data from camera frame to detect document edges.
     * Uses edge density analysis to find rectangular document boundaries.
     */
    fun detectDocument(
        luminance: IntArray,
        width: Int,
        height: Int
    ): DetectionResult {
        if (luminance.size != width * height) {
            return DetectionResult(false)
        }

        // Calculate edge map using Sobel-like operator
        val edgeMap = calculateEdges(luminance, width, height)

        // Find the largest rectangular region with high edge density
        val bounds = findDocumentBounds(edgeMap, width, height)

        return if (bounds != null) {
            val area = (bounds.width() * bounds.height())
            val frameArea = (width * height).toFloat()
            val coverage = area / frameArea
            val confidence = (coverage * 2f).coerceIn(0f, 1f)
            DetectionResult(true, bounds, confidence)
        } else {
            DetectionResult(false)
        }
    }

    private fun calculateEdges(luminance: IntArray, width: Int, height: Int): FloatArray {
        val edges = FloatArray(width * height)

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x

                // Sobel X
                val gx = (-luminance[(y - 1) * width + (x - 1)] + luminance[(y - 1) * width + (x + 1)]
                        - 2 * luminance[y * width + (x - 1)] + 2 * luminance[y * width + (x + 1)]
                        - luminance[(y + 1) * width + (x - 1)] + luminance[(y + 1) * width + (x + 1)])

                // Sobel Y
                val gy = (-luminance[(y - 1) * width + (x - 1)] - 2 * luminance[(y - 1) * width + x] - luminance[(y - 1) * width + (x + 1)]
                        + luminance[(y + 1) * width + (x - 1)] + 2 * luminance[(y + 1) * width + x] + luminance[(y + 1) * width + (x + 1)])

                edges[idx] = min(255f, abs(gx) + abs(gy).toFloat())
            }
        }

        return edges
    }

    private fun findDocumentBounds(edgeMap: FloatArray, width: Int, height: Int): RectF? {
        val threshold = 40f
        val margin = (min(width, height) * 0.05f).toInt()

        var left = width
        var top = height
        var right = 0
        var bottom = 0
        var edgeCount = 0

        // Scan for high-edge-density regions
        for (y in margin until height - margin) {
            for (x in margin until width - margin) {
                if (edgeMap[y * width + x] > threshold) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                    edgeCount++
                }
            }
        }

        val minSize = (min(width, height) * 0.2f).toInt()
        if (right - left < minSize || bottom - top < minSize) {
            return null
        }

        val totalArea = (width - 2 * margin) * (height - 2 * margin)
        val edgeDensity = edgeCount.toFloat() / totalArea

        // Require reasonable edge density for a document
        if (edgeDensity < 0.01f) return null

        // Convert to normalized coordinates (0-1 range)
        return RectF(
            left.toFloat() / width,
            top.toFloat() / height,
            right.toFloat() / width,
            bottom.toFloat() / height
        )
    }

    /**
     * Converts YUV_420_888 luminance plane to grayscale int array
     */
    fun yuvToLuminance(yuvData: ByteArray, width: Int, height: Int): IntArray {
        val luminance = IntArray(width * height)
        for (i in 0 until width * height) {
            luminance[i] = yuvData[i].toInt() and 0xFF
        }
        return luminance
    }
}
