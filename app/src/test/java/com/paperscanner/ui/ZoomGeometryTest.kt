package com.paperscanner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ZoomGeometryTest {

    private val viewWidth = 1000f
    private val viewHeight = 2000f

    // A page-shaped photo, taller than it is wide
    private val imageWidth = 1600f
    private val imageHeight = 2400f

    private fun fit() = ZoomGeometry.fitScale(viewWidth, viewHeight, imageWidth, imageHeight)

    /** Where the image-space point lands on screen for a given scale and offset. */
    private fun translate(viewSize: Float, imageSize: Float, scale: Float, offset: Float): Float =
        (viewSize - imageSize * scale) / 2f + offset

    @Test
    fun fitScaleMakesTheWholeImageVisible() {
        val scale = fit()

        assertTrue("image must fit horizontally", imageWidth * scale <= viewWidth + 0.01f)
        assertTrue("image must fit vertically", imageHeight * scale <= viewHeight + 0.01f)
        // One axis should be exactly filled, otherwise we are not fitting snugly
        val fillsWidth = abs(imageWidth * scale - viewWidth) < 0.01f
        val fillsHeight = abs(imageHeight * scale - viewHeight) < 0.01f
        assertTrue("one axis should be filled exactly", fillsWidth || fillsHeight)
    }

    @Test
    fun aFittedImageCannotBePanned() {
        val scale = fit()
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        assertEquals(0f, ZoomGeometry.maxOffset(scaledWidth, viewWidth), 0.01f)
        assertEquals(0f, ZoomGeometry.maxOffset(scaledHeight, viewHeight), 0.01f)

        // Whatever the caller tries, the image snaps back to centred
        assertEquals(0f, ZoomGeometry.clampOffset(500f, scaledWidth, viewWidth), 0.01f)
        assertEquals(0f, ZoomGeometry.clampOffset(-500f, scaledHeight, viewHeight), 0.01f)
    }

    @Test
    fun aZoomedImageCanBePannedOnlyToItsEdges() {
        val scale = fit() * 2f
        val scaledWidth = imageWidth * scale
        val limit = (scaledWidth - viewWidth) / 2f

        assertTrue("a zoomed image should be pannable", limit > 0f)

        // Dragging past the edge stops exactly at the edge
        assertEquals(limit, ZoomGeometry.clampOffset(limit + 1000f, scaledWidth, viewWidth), 0.01f)
        assertEquals(-limit, ZoomGeometry.clampOffset(-limit - 1000f, scaledWidth, viewWidth), 0.01f)
        assertEquals(0.5f * limit, ZoomGeometry.clampOffset(0.5f * limit, scaledWidth, viewWidth), 0.01f)
    }

    @Test
    fun zoomingAboutTheCentreLeavesTheImageCentred() {
        val scale = fit()
        val offset = ZoomGeometry.offsetForFocus(
            focus = viewWidth / 2f,
            viewSize = viewWidth,
            imageSize = imageWidth,
            oldScale = scale,
            newScale = scale * 2f,
            oldOffset = 0f
        )

        assertEquals(0f, offset, 0.01f)
    }

    @Test
    fun thePointUnderTheFingersStaysPutWhileZooming() {
        val oldScale = fit() * 1.5f
        val newScale = fit() * 3f
        val oldOffset = -40f
        val focus = 320f

        val newOffset = ZoomGeometry.offsetForFocus(
            focus = focus,
            viewSize = viewWidth,
            imageSize = imageWidth,
            oldScale = oldScale,
            newScale = newScale,
            oldOffset = oldOffset
        )

        // Image coordinate under the focus before and after must match
        val before = (focus - translate(viewWidth, imageWidth, oldScale, oldOffset)) / oldScale
        val after = (focus - translate(viewWidth, imageWidth, newScale, newOffset)) / newScale

        assertEquals(before, after, 0.01f)
    }

    @Test
    fun zoomingOutBackToFitReturnsToCentre() {
        val scale = fit()
        val zoomedOffset = 120f

        val offset = ZoomGeometry.offsetForFocus(
            focus = viewWidth / 2f,
            viewSize = viewWidth,
            imageSize = imageWidth,
            oldScale = scale * 2.5f,
            newScale = scale,
            oldOffset = zoomedOffset
        )

        // At fit scale everything clamps back to centred anyway
        assertEquals(0f, ZoomGeometry.clampOffset(offset, imageWidth * scale, viewWidth), 0.01f)
    }

    @Test
    fun degenerateSizesDoNotProduceNaN() {
        assertEquals(1f, ZoomGeometry.fitScale(0f, 100f, 100f, 100f), 0f)
        assertEquals(1f, ZoomGeometry.fitScale(100f, 100f, 0f, 100f), 0f)
        assertEquals(0f, ZoomGeometry.maxOffset(0f, 100f), 0f)

        // A zero old scale would divide by zero, so the offset must pass straight through
        assertEquals(7f, ZoomGeometry.offsetForFocus(10f, 100f, 100f, 0f, 2f, 7f), 0f)
    }
}
