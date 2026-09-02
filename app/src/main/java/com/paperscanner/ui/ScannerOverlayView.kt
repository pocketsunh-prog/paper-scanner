package com.paperscanner.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.paperscanner.processing.DetectionResult

class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Rope loop effect paints
    private val overlayPaint = Paint().apply {
        color = Color.parseColor("#7A3E3A35")
        style = Paint.Style.FILL
    }

    private val ropePaint = Paint().apply {
        color = Color.parseColor("#E8A87C")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val ropeGlowPaint = Paint().apply {
        color = Color.parseColor("#60E8A87C")
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val knotPaint = Paint().apply {
        color = Color.parseColor("#D4956B")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val cornerKnotPaint = Paint().apply {
        color = Color.parseColor("#5B8C5A")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val focusPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#CCFFFFFF")
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        isAntiAlias = true
    }

    private val detectedBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#5B8C5A")
        isAntiAlias = true
    }

    private var detectionResult: DetectionResult? = null
    private var showFocusArea: Boolean = true
    private var statusText: String = "Point camera at a document"
    private var detected: Boolean = false
    private var ropePhase = 0f

    private val ropeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            ropePhase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        ropeAnimator.start()
    }

    fun updateDetection(result: DetectionResult) {
        detectionResult = result
        detected = result.found
        statusText = if (result.found) "Document detected" else "Point camera at a document"
        invalidate()
    }

    fun setShowFocusArea(show: Boolean) {
        showFocusArea = show
        invalidate()
    }

    fun reset() {
        detectionResult = null
        detected = false
        statusText = "Point camera at a document"
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        ropeAnimator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        val bounds = detectionResult?.bounds

        if (bounds != null && detected) {
            val pad = 12f
            val left = bounds.left * w
            val top = bounds.top * h
            val right = bounds.right * w
            val bottom = bounds.bottom * h

            // Draw overlay outside document area
            val path = Path().apply {
                addRect(0f, 0f, w, top, Path.Direction.CW)
                addRect(0f, top, left, bottom, Path.Direction.CW)
                addRect(right, top, w, bottom, Path.Direction.CW)
                addRect(0f, bottom, w, h, Path.Direction.CW)
            }
            canvas.drawPath(path, overlayPaint)

            // Draw rope loop around document
            drawRopeLoop(canvas, left - pad, top - pad, right + pad, bottom + pad)

        } else {
            // Full overlay when no document detected
            canvas.drawRect(0f, 0f, w, h, overlayPaint)

            // Draw focus area guide
            if (showFocusArea) {
                val focusW = w * 0.78f
                val focusH = focusW * 1.4f
                val focusLeft = (w - focusW) / 2f
                val focusTop = (h - focusH) / 2f
                val focusRight = focusLeft + focusW
                val focusBottom = focusTop + focusH

                canvas.drawRoundRect(
                    focusLeft, focusTop, focusRight, focusBottom, 12f, 12f, focusPaint
                )

                // Corner guides
                drawCornerGuides(canvas, focusLeft, focusTop, focusRight, focusBottom)
            }
        }
    }

    private fun drawRopeLoop(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val ropeWave = 3f
        val ropeSegments = 60

        // Draw glow first
        drawWavyRect(canvas, left, top, right, bottom, ropeGlowPaint, ropeWave, ropeSegments)

        // Draw main rope
        drawWavyRect(canvas, left, top, right, bottom, ropePaint, ropeWave, ropeSegments)

        // Draw border inside rope
        canvas.drawRoundRect(
            RectF(left + 8f, top + 8f, right - 8f, bottom - 8f),
            6f, 6f, detectedBorderPaint
        )

        // Draw corner knots
        val knotRadius = 8f
        canvas.drawCircle(left, top, knotRadius, cornerKnotPaint)
        canvas.drawCircle(right, top, knotRadius, cornerKnotPaint)
        canvas.drawCircle(left, bottom, knotRadius, cornerKnotPaint)
        canvas.drawCircle(right, bottom, knotRadius, cornerKnotPaint)

        // Draw small decorative knots along the rope
        val totalPerimeter = 2f * ((right - left) + (bottom - top))
        val knotSpacing = totalPerimeter / 8f
        for (i in 0 until 8) {
            val dist = (i * knotSpacing + ropePhase * knotSpacing) % totalPerimeter
            val (kx, ky) = getPointOnPerimeter(left, top, right, bottom, dist)
            canvas.drawCircle(kx, ky, 3.5f, knotPaint)
        }
    }

    private fun drawWavyRect(
        canvas: Canvas,
        left: Float, top: Float, right: Float, bottom: Float,
        paint: Paint, amplitude: Float, segments: Int
    ) {
        val path = Path()
        val totalPerimeter = 2f * ((right - left) + (bottom - top))
        val segLen = totalPerimeter / segments

        for (i in 0..segments) {
            val dist = i * segLen
            val (bx, by) = getPointOnPerimeter(left, top, right, bottom, dist)

            // Calculate normal direction for wave offset
            val (nx, ny) = getNormalOnPerimeter(left, top, right, bottom, dist)

            // Wave offset with animation
            val angle = (dist / totalPerimeter * 6.28f * 3f) + ropePhase * 6.28f
            val wave = Math.sin(angle.toDouble()).toFloat() * amplitude

            val px = bx + nx * wave
            val py = by + ny * wave

            if (i == 0) path.moveTo(px, py)
            else path.lineTo(px, py)
        }

        canvas.drawPath(path, paint)
    }

    private fun getPointOnPerimeter(left: Float, top: Float, right: Float, bottom: Float, dist: Float): Pair<Float, Float> {
        val w = right - left
        val h = bottom - top
        val perimeter = 2f * (w + h)
        val d = dist % perimeter

        return when {
            d < w -> Pair(left + d, top) // top edge
            d < w + h -> Pair(right, top + (d - w)) // right edge
            d < 2 * w + h -> Pair(right - (d - w - h), bottom) // bottom edge
            else -> Pair(left, bottom - (d - 2 * w - h)) // left edge
        }
    }

    private fun getNormalOnPerimeter(left: Float, top: Float, right: Float, bottom: Float, dist: Float): Pair<Float, Float> {
        val w = right - left
        val h = bottom - top
        val perimeter = 2f * (w + h)
        val d = dist % perimeter

        return when {
            d < w -> Pair(0f, -1f) // top edge: normal points up
            d < w + h -> Pair(1f, 0f) // right edge: normal points right
            d < 2 * w + h -> Pair(0f, 1f) // bottom edge: normal points down
            else -> Pair(-1f, 0f) // left edge: normal points left
        }
    }

    private fun drawCornerGuides(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val guideLen = 44f
        knotPaint.color = Color.WHITE

        // Top-left
        canvas.drawLine(left, top + guideLen, left, top, knotPaint)
        canvas.drawLine(left, top, left + guideLen, top, knotPaint)

        // Top-right
        canvas.drawLine(right, top + guideLen, right, top, knotPaint)
        canvas.drawLine(right, top, right - guideLen, top, knotPaint)

        // Bottom-left
        canvas.drawLine(left, bottom - guideLen, left, bottom, knotPaint)
        canvas.drawLine(left, bottom, left + guideLen, bottom, knotPaint)

        // Bottom-right
        canvas.drawLine(right, bottom - guideLen, right, bottom, knotPaint)
        canvas.drawLine(right, bottom, right - guideLen, bottom, knotPaint)
    }

    /**
     * Get the detected document bounds in view coordinates (normalized 0-1)
     */
    fun getDetectedBounds(): RectF? {
        return detectionResult?.bounds
    }

    /**
     * Get the detected document bounds in pixel coordinates
     */
    fun getDetectedBoundsPixels(): RectF? {
        val bounds = detectionResult?.bounds ?: return null
        val w = width.toFloat()
        val h = height.toFloat()
        return RectF(bounds.left * w, bounds.top * h, bounds.right * w, bounds.bottom * h)
    }
}
