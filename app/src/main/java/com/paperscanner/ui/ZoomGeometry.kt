package com.paperscanner.ui

import kotlin.math.max

/**
 * Geometry behind fit-and-zoom image viewing.
 *
 * Kept free of Android types so the tricky parts - keeping the point under the fingers
 * fixed while zooming, and never letting the photo be dragged off screen - can be unit
 * tested.
 *
 * An image is laid out centred, scaled by `fitScale * userScale`, then shifted by a
 * single offset from centre. Offsets are therefore symmetric: 0 is always "centred".
 */
internal object ZoomGeometry {

    /** Scale that makes the whole image fit inside the view. */
    fun fitScale(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float): Float {
        if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) return 1f
        return minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    }

    /** How far from centre the image may be dragged before an edge would come inside. */
    fun maxOffset(scaledSize: Float, viewSize: Float): Float =
        max(0f, (scaledSize - viewSize) / 2f)

    /**
     * Keep the offset within [maxOffset], so a zoomed photo can never be dragged far
     * enough to show empty space where the image should be.
     */
    fun clampOffset(offset: Float, scaledSize: Float, viewSize: Float): Float {
        val limit = maxOffset(scaledSize, viewSize)
        return offset.coerceIn(-limit, limit)
    }

    /**
     * Offset that keeps the image point under [focus] under the fingers after the scale
     * changes from [oldScale] to [newScale].
     */
    fun offsetForFocus(
        focus: Float,
        viewSize: Float,
        imageSize: Float,
        oldScale: Float,
        newScale: Float,
        oldOffset: Float
    ): Float {
        if (oldScale <= 0f || newScale <= 0f || imageSize <= 0f) return oldOffset

        val oldTranslate = (viewSize - imageSize * oldScale) / 2f + oldOffset
        val imagePoint = (focus - oldTranslate) / oldScale
        val newTranslate = focus - imagePoint * newScale

        return newTranslate - (viewSize - imageSize * newScale) / 2f
    }
}
