package com.paperscanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Image view with pinch / double-tap zoom and panning.
 *
 * Horizontal drags are handed to the parent pager while the image sits at fit scale, and
 * consumed for panning once it is zoomed in - so swiping changes page normally, and pans
 * the photo after the user zooms.
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 6f
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val ZOOMED_EPSILON = 0.01f
    }

    /** Single tap, used by the viewer to show/hide its chrome. */
    var onSingleTap: (() -> Unit)? = null

    /** Scale relative to "fit in view". 1 means the whole image is visible. */
    private var userScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    val isZoomed: Boolean get() = userScale > MIN_SCALE + ZOOMED_EPSILON

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        reset()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    /** Back to fit-in-view, centred. */
    fun reset() {
        userScale = MIN_SCALE
        offsetX = 0f
        offsetY = 0f
        updateMatrix()
    }

    // --------------------------------------------------------------- matrix

    private fun updateMatrix() {
        val drawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) return

        val scale = ZoomGeometry.fitScale(viewWidth, viewHeight, imageWidth, imageHeight) * userScale
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        // Never let the photo be dragged clear of the view
        offsetX = ZoomGeometry.clampOffset(offsetX, scaledWidth, viewWidth)
        offsetY = ZoomGeometry.clampOffset(offsetY, scaledHeight, viewHeight)

        val matrix = Matrix()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            (viewWidth - scaledWidth) / 2f + offsetX,
            (viewHeight - scaledHeight) / 2f + offsetY
        )
        imageMatrix = matrix
    }

    /**
     * Zoom to [targetScale], keeping whatever is under ([focusX], [focusY]) in place.
     */
    private fun zoomAround(focusX: Float, focusY: Float, targetScale: Float) {
        val drawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) return

        val clamped = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (clamped == userScale) return

        val fit = ZoomGeometry.fitScale(viewWidth, viewHeight, imageWidth, imageHeight)
        val oldScale = fit * userScale
        val newScale = fit * clamped

        offsetX = ZoomGeometry.offsetForFocus(
            focus = focusX,
            viewSize = viewWidth,
            imageSize = imageWidth,
            oldScale = oldScale,
            newScale = newScale,
            oldOffset = offsetX
        )
        offsetY = ZoomGeometry.offsetForFocus(
            focus = focusY,
            viewSize = viewHeight,
            imageSize = imageHeight,
            oldScale = oldScale,
            newScale = newScale,
            oldOffset = offsetY
        )

        userScale = clamped
        updateMatrix()
    }

    // ------------------------------------------------------------- gestures

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAround(detector.focusX, detector.focusY, userScale * detector.scaleFactor)
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) reset() else zoomAround(e.x, e.y, DOUBLE_TAP_SCALE)
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // At fit scale the drag belongs to the pager, not to us
            if (!isZoomed) return false

            offsetX -= distanceX
            offsetY -= distanceY
            updateMatrix()
            return true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                parent?.requestDisallowInterceptTouchEvent(isZoomed)
            MotionEvent.ACTION_POINTER_DOWN ->
                parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
            else -> if (isZoomed) parent?.requestDisallowInterceptTouchEvent(true)
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onSingleTap?.invoke()
        return true
    }
}
