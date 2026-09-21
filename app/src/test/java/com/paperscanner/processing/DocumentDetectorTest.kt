package com.paperscanner.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Renders synthetic camera frames containing a page rectangle and checks that the
 * detector finds a rectangle that hugs it - including when the page is tilted the way
 * a hand-held scan usually is.
 */
class DocumentDetectorTest {

    private val width = 320
    private val height = 240

    /**
     * Dull wood-like background with a bright page painted on top, rotated by
     * [rotationDegrees] around its centre.
     */
    private fun renderScene(
        pageWidth: Int,
        pageHeight: Int,
        centerX: Float,
        centerY: Float,
        rotationDegrees: Float,
        pageLuminance: Int = 235,
        headerBandLuminance: Int? = null
    ): IntArray {
        val luminance = IntArray(width * height)

        // Vertical gradient so the background is not perfectly flat
        for (y in 0 until height) {
            val value = 70 + (y * 40 / height)
            for (x in 0 until width) {
                luminance[y * width + x] = value
            }
        }

        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cosA = cos(radians).toFloat()
        val sinA = sin(radians).toFloat()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY
                // Rotate the sample point back into the page's own frame
                val localX = dx * cosA + dy * sinA
                val localY = -dx * sinA + dy * cosA

                val insidePage = abs(localX) <= pageWidth / 2f && abs(localY) <= pageHeight / 2f
                if (!insidePage) continue

                luminance[y * width + x] = pageLuminance

                // A dark banner across the top of the page, like a printed heading
                if (headerBandLuminance != null && abs(localY) <= pageHeight / 8f) {
                    luminance[y * width + x] = headerBandLuminance
                }
            }
        }

        return luminance
    }

    private fun cornersInPixels(result: DetectionResult): List<Pair<Float, Float>> {
        val corners = result.corners
        assertNotNull("detector should return corners", corners)
        return corners!!.map { Pair(it.first * width, it.second * height) }
    }

    private fun centerOf(points: List<Pair<Float, Float>>): Pair<Float, Float> = Pair(
        points.sumOf { it.first.toDouble() }.toFloat() / points.size,
        points.sumOf { it.second.toDouble() }.toFloat() / points.size
    )

    private fun sideLengths(points: List<Pair<Float, Float>>): List<Float> = List(points.size) { i ->
        val a = points[i]
        val b = points[(i + 1) % points.size]
        hypot(b.first - a.first, b.second - a.second)
    }

    @Test
    fun detectsUprightLandscapePage() {
        val luminance = renderScene(200, 140, 160f, 120f, 0f)

        val result = DocumentDetector.detectDocument(luminance, width, height, 0)

        assertTrue("page should be found", result.found)
        val corners = cornersInPixels(result)
        assertEquals(4, corners.size)

        val center = centerOf(corners)
        assertEquals(160.0, center.first.toDouble(), 4.0)
        assertEquals(120.0, center.second.toDouble(), 4.0)

        val sides = sideLengths(corners).sorted()
        assertEquals("short side", 140.0, sides[0].toDouble(), 6.0)
        assertEquals("long side", 200.0, sides[3].toDouble(), 6.0)

        assertEquals(DocumentOrientation.HORIZONTAL, result.orientation)
    }

    /** The case in the reference screenshot: a page held at an angle. */
    @Test
    fun detectsTiltedPageAndReportsItsSkew() {
        val tilt = 18f
        val luminance = renderScene(200, 140, 160f, 120f, tilt)

        val result = DocumentDetector.detectDocument(luminance, width, height, 0)

        assertTrue("tilted page should still be found", result.found)
        val corners = cornersInPixels(result)

        val center = centerOf(corners)
        assertEquals(160.0, center.first.toDouble(), 5.0)
        assertEquals(120.0, center.second.toDouble(), 5.0)

        // The fitted rectangle should still match the page, not the frame
        val sides = sideLengths(corners).sorted()
        assertEquals("short side", 140.0, sides[0].toDouble(), 8.0)
        assertEquals("long side", 200.0, sides[3].toDouble(), 8.0)

        assertEquals("reported skew", tilt.toDouble(), result.skewDegrees.toDouble(), 4.0)
        assertEquals(DocumentOrientation.HORIZONTAL, result.orientation)
    }

    @Test
    fun detectsPortraitPageAsVertical() {
        val luminance = renderScene(120, 190, 160f, 120f, 0f)

        val result = DocumentDetector.detectDocument(luminance, width, height, 0)

        assertTrue(result.found)
        assertEquals(DocumentOrientation.VERTICAL, result.orientation)

        val sides = sideLengths(cornersInPixels(result)).sorted()
        assertEquals("short side", 120.0, sides[0].toDouble(), 6.0)
        assertEquals("long side", 190.0, sides[3].toDouble(), 6.0)
    }

    /**
     * Orientation is reported in the upright frame, so the same frame analysed with a
     * 90 degree rotation must come back the other way up.
     */
    @Test
    fun orientationFollowsFrameRotation() {
        val luminance = renderScene(200, 140, 160f, 120f, 0f)

        val upright = DocumentDetector.detectDocument(luminance, width, height, 0)
        val rotated = DocumentDetector.detectDocument(luminance, width, height, 90)

        assertEquals(DocumentOrientation.HORIZONTAL, upright.orientation)
        assertEquals(DocumentOrientation.VERTICAL, rotated.orientation)
    }

    @Test
    fun aPrintedHeadingDoesNotBreakTheFit() {
        val luminance = renderScene(200, 140, 160f, 120f, 0f, headerBandLuminance = 120)

        val result = DocumentDetector.detectDocument(luminance, width, height, 0)

        assertTrue(result.found)
        val sides = sideLengths(cornersInPixels(result)).sorted()
        assertEquals("short side", 140.0, sides[0].toDouble(), 8.0)
        assertEquals("long side", 200.0, sides[3].toDouble(), 8.0)
    }

    @Test
    fun findsNothingInAFlatFrame() {
        val luminance = IntArray(width * height) { 128 }

        val result = DocumentDetector.detectDocument(luminance, width, height, 0)

        assertFalse("a blank frame is not a page", result.found)
    }

    @Test
    fun ignoresATinyBlob() {
        // A small square well under the minimum page size
        val luminance = renderScene(30, 30, 160f, 120f, 0f)

        val result = DocumentDetector.detectDocument(luminance, width, height, 0)

        assertFalse("too small to be a page", result.found)
    }

    @Test
    fun rejectsFramesWithMismatchedBufferSize() {
        val result = DocumentDetector.detectDocument(IntArray(10), width, height, 0)

        assertFalse(result.found)
    }

    @Test
    fun fitsRectangleToAPagePushedOffCentre() {
        val luminance = renderScene(160, 120, 90f, 80f, -12f)

        val result = DocumentDetector.detectDocument(luminance, width, height, 0)

        assertTrue(result.found)
        val center = centerOf(cornersInPixels(result))
        assertEquals(90.0, center.first.toDouble(), 6.0)
        assertEquals(80.0, center.second.toDouble(), 6.0)
        assertEquals(-12.0, result.skewDegrees.toDouble(), 5.0)
    }

    /** Guards the corner ordering the capture step relies on. */
    @Test
    fun cornersComeBackInClockwiseOrderFromTopLeft() {
        val luminance = renderScene(200, 140, 160f, 120f, 0f)

        val corners = cornersInPixels(DocumentDetector.detectDocument(luminance, width, height, 0))

        val topLeft = corners[0]
        val topRight = corners[1]
        val bottomRight = corners[2]
        val bottomLeft = corners[3]

        assertTrue("top edge should run rightwards", topRight.first > topLeft.first)
        assertTrue("bottom edge should run rightwards", bottomRight.first > bottomLeft.first)
        assertTrue("left edge should run downwards", bottomLeft.second > topLeft.second)
        assertTrue("right edge should run downwards", bottomRight.second > topRight.second)

        // Sum of cross products has one consistent sign for a simple convex quad
        var sum = 0f
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            sum += a.first * b.second - b.first * a.second
        }
        assertTrue("corners should form a simple polygon", abs(sum) > 1f)

        val angle = Math.toDegrees(
            atan2(
                (topRight.second - topLeft.second).toDouble(),
                (topRight.first - topLeft.first).toDouble()
            )
        )
        assertEquals("upright page has no tilt", 0.0, angle, 4.0)
    }
}
