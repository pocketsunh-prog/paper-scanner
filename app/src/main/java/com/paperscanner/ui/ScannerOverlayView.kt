package com.paperscanner.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.paperscanner.processing.DetectionResult
import com.paperscanner.processing.DocumentDetector
import kotlin.math.max
import kotlin.math.sqrt

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

    // Clean, straight rectangle for the detected page (matches the usual document-scan look)
    private val pageOutlinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#FFD24D")
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val pageCornerPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFFDF7")
        isAntiAlias = true
    }

    private val pageCornerRingPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#FFD24D")
        isAntiAlias = true
    }

    /** Light dim outside the page - enough to lift it, not enough to hide the scene. */
    private val pageDimPaint = Paint().apply {
        color = Color.parseColor("#26000000")
        style = Paint.Style.FILL
    }

    private var detectionResult: DetectionResult? = null
    private var showFocusArea: Boolean = true
    private var statusText: String = "Point camera at a document"
    private var detected: Boolean = false
    private var ropePhase = 0f

    // How the analysis frame maps onto this view
    private var frameWidth = 0
    private var frameHeight = 0
    private var rotationDegrees = 0
    private var showDetectionOutline = true
    private var detectionActive = true

    // Mode: true = auto, false = manual
    var autoMode: Boolean = true

    // Manual mode rectangle (normalized 0-1 coordinates)
    private var manualRectLeft = 0.15f
    private var manualRectTop = 0.2f
    private var manualRectRight = 0.85f
    private var manualRectBottom = 0.7f

    // Tap-to-capture callback
    var onTapToCapture: (() -> Unit)? = null

    // Manual mode: get the rectangle bounds in normalized coordinates
    fun getManualRect(): RectF {
        return RectF(manualRectLeft, manualRectTop, manualRectRight, manualRectBottom)
    }

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
        // Handle touch events for tap-to-capture
        setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                handleTap(event.x, event.y)
            }
            true
        }
    }

    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private enum class DragMode {
        NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR
    }

    private fun handleTap(x: Float, y: Float) {
        if (!autoMode) {
            // In manual mode, tap inside rectangle to capture
            val w = width.toFloat()
            val h = height.toFloat()
            val left = manualRectLeft * w
            val top = manualRectTop * h
            val right = manualRectRight * w
            val bottom = manualRectBottom * h
            if (x >= left && x <= right && y >= top && y <= bottom) {
                onTapToCapture?.invoke()
            }
            return
        }

        if (!detected) return

        val bounds = detectionResult?.bounds ?: return
        val w = width.toFloat()
        val h = height.toFloat()

        val left = bounds.left * w
        val top = bounds.top * h
        val right = bounds.right * w
        val bottom = bounds.bottom * h

        // Check if tap is inside the detected rectangle
        if (x >= left && x <= right && y >= top && y <= bottom) {
            onTapToCapture?.invoke()
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!autoMode) {
            return handleManualTouch(event)
        }
        return super.onTouchEvent(event)
    }

    private fun handleManualTouch(event: android.view.MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val x = event.x
        val y = event.y

        val left = manualRectLeft * w
        val top = manualRectTop * h
        val right = manualRectRight * w
        val bottom = manualRectBottom * h
        val handleSize = 60f

        when (event.action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                // Check if touching a corner handle
                dragMode = when {
                    nearPoint(x, y, left, top, handleSize) -> DragMode.RESIZE_TL
                    nearPoint(x, y, right, top, handleSize) -> DragMode.RESIZE_TR
                    nearPoint(x, y, left, bottom, handleSize) -> DragMode.RESIZE_BL
                    nearPoint(x, y, right, bottom, handleSize) -> DragMode.RESIZE_BR
                    x >= left && x <= right && y >= top && y <= bottom -> DragMode.MOVE
                    else -> DragMode.NONE
                }
                lastTouchX = x
                lastTouchY = y
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = (x - lastTouchX) / w
                val dy = (y - lastTouchY) / h

                when (dragMode) {
                    DragMode.MOVE -> {
                        manualRectLeft += dx
                        manualRectRight += dx
                        manualRectTop += dy
                        manualRectBottom += dy
                    }
                    DragMode.RESIZE_TL -> {
                        manualRectLeft += dx
                        manualRectTop += dy
                    }
                    DragMode.RESIZE_TR -> {
                        manualRectRight += dx
                        manualRectTop += dy
                    }
                    DragMode.RESIZE_BL -> {
                        manualRectLeft += dx
                        manualRectBottom += dy
                    }
                    DragMode.RESIZE_BR -> {
                        manualRectRight += dx
                        manualRectBottom += dy
                    }
                    DragMode.NONE -> {}
                }

                // Clamp to valid range
                manualRectLeft = manualRectLeft.coerceIn(0f, manualRectRight - 0.1f)
                manualRectTop = manualRectTop.coerceIn(0f, manualRectBottom - 0.1f)
                manualRectRight = manualRectRight.coerceIn(manualRectLeft + 0.1f, 1f)
                manualRectBottom = manualRectBottom.coerceIn(manualRectTop + 0.1f, 1f)

                lastTouchX = x
                lastTouchY = y
                invalidate()
            }
            android.view.MotionEvent.ACTION_UP -> {
                dragMode = DragMode.NONE
            }
        }
        return true
    }

    private fun nearPoint(x: Float, y: Float, px: Float, py: Float, threshold: Float): Boolean {
        return kotlin.math.abs(x - px) < threshold && kotlin.math.abs(y - py) < threshold
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

    fun setShowDetectionOutline(show: Boolean) {
        showDetectionOutline = show
        invalidate()
    }

    /**
     * Turn the auto-detection visuals on or off. Manual mode keeps drawing its
     * rectangle either way; with detection off the preview is left clean.
     */
    fun setDetectionActive(active: Boolean) {
        detectionActive = active
        invalidate()
    }

    /**
     * Describe the analysis frame so detection coordinates can be placed correctly.
     * [rotationDegrees] is the clockwise rotation that brings the frame upright.
     */
    fun setFrameInfo(width: Int, height: Int, rotationDegrees: Int) {
        if (frameWidth == width && frameHeight == height && this.rotationDegrees == rotationDegrees) return
        frameWidth = width
        frameHeight = height
        this.rotationDegrees = rotationDegrees
        invalidate()
    }

    /**
     * Map a point normalized against the analysis frame onto this view.
     *
     * The frame is first rotated upright, then scaled like the preview does
     * (FILL_CENTER: uniform scale that covers the view, centred), so the outline
     * lands on the page instead of being stretched across the screen.
     */
    private fun toViewPoint(nx: Float, ny: Float): Pair<Float, Float> {
        val upright = DocumentDetector.toUpright(nx, ny, rotationDegrees)
        val (uprightWidth, uprightHeight) =
            DocumentDetector.uprightSize(frameWidth, frameHeight, rotationDegrees)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        if (uprightWidth <= 0 || uprightHeight <= 0) {
            return Pair(upright.first * viewWidth, upright.second * viewHeight)
        }

        val scale = max(viewWidth / uprightWidth, viewHeight / uprightHeight)
        val scaledWidth = uprightWidth * scale
        val scaledHeight = uprightHeight * scale
        val offsetX = (viewWidth - scaledWidth) / 2f
        val offsetY = (viewHeight - scaledHeight) / 2f

        return Pair(offsetX + upright.first * scaledWidth, offsetY + upright.second * scaledHeight)
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
        val corners = detectionResult?.corners

        if (!autoMode) {
            // MANUAL MODE: Show draggable rectangle
            drawManualMode(canvas, w, h)
        } else if (!detectionActive) {
            // Detection switched off: leave the preview untouched
            return
        } else if (showDetectionOutline && bounds != null && detected) {
            // AUTO MODE: Show detected document
            if (corners != null && corners.size == 4) {
                val points = corners.map { toViewPoint(it.first, it.second) }
                dimOutsidePolygon(canvas, points, w, h)
                drawPageRectangle(canvas, points)
            } else {
                val topLeft = toViewPoint(bounds.left, bounds.top)
                val bottomRight = toViewPoint(bounds.right, bounds.bottom)
                val left = topLeft.first
                val top = topLeft.second
                val right = bottomRight.first
                val bottom = bottomRight.second
                val path = Path().apply {
                    addRect(0f, 0f, w, top, Path.Direction.CW)
                    addRect(0f, top, left, bottom, Path.Direction.CW)
                    addRect(right, top, w, bottom, Path.Direction.CW)
                    addRect(0f, bottom, w, h, Path.Direction.CW)
                }
                canvas.drawPath(path, overlayPaint)
                val pad = 12f
                drawRopeLoop(canvas, left - pad, top - pad, right + pad, bottom + pad)
            }
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

    private fun drawManualMode(canvas: Canvas, w: Float, h: Float) {
        val left = manualRectLeft * w
        val top = manualRectTop * h
        val right = manualRectRight * w
        val bottom = manualRectBottom * h

        // Draw dark overlay outside the manual rectangle
        val path = Path().apply {
            addRect(0f, 0f, w, top, Path.Direction.CW)
            addRect(0f, top, left, bottom, Path.Direction.CW)
            addRect(right, top, w, bottom, Path.Direction.CW)
            addRect(0f, bottom, w, h, Path.Direction.CW)
        }
        canvas.drawPath(path, overlayPaint)

        // Draw rope loop around manual rectangle
        val pad = 8f
        drawRopeLoop(canvas, left - pad, top - pad, right + pad, bottom + pad)

        // Draw corner handles
        val handleRadius = 12f
        canvas.drawCircle(left, top, handleRadius, cornerKnotPaint)
        canvas.drawCircle(right, top, handleRadius, cornerKnotPaint)
        canvas.drawCircle(left, bottom, handleRadius, cornerKnotPaint)
        canvas.drawCircle(right, bottom, handleRadius, cornerKnotPaint)
    }

    /** Dim everything outside the detected page, leaving the page itself clear. */
    private fun dimOutsidePolygon(canvas: Canvas, points: List<Pair<Float, Float>>, w: Float, h: Float) {
        val outside = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }

        val page = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) {
                lineTo(points[i].first, points[i].second)
            }
            close()
        }

        outside.op(page, Path.Op.XOR)
        canvas.drawPath(outside, pageDimPaint)
    }

    /** Straight outline plus corner handles, the way a document scanner marks the page. */
    private fun drawPageRectangle(canvas: Canvas, points: List<Pair<Float, Float>>) {
        val outline = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) {
                lineTo(points[i].first, points[i].second)
            }
            close()
        }
        canvas.drawPath(outline, pageOutlinePaint)

        val radius = 6f
        for (point in points) {
            canvas.drawCircle(point.first, point.second, radius, pageCornerPaint)
            canvas.drawCircle(point.first, point.second, radius, pageCornerRingPaint)
        }
    }

    private fun drawOverlayAroundPolygon(canvas: Canvas, points: List<Pair<Float, Float>>, w: Float, h: Float) {
        val path = Path()
        // Start from top-left corner
        path.moveTo(0f, 0f)
        path.lineTo(w, 0f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()

        // Cut out the document polygon (points are already in view coordinates)
        val docPath = Path()
        docPath.moveTo(points[0].first, points[0].second)
        for (i in 1 until points.size) {
            docPath.lineTo(points[i].first, points[i].second)
        }
        docPath.close()

        // Draw overlay with hole using even-odd rule
        path.op(docPath, Path.Op.XOR)
        canvas.drawPath(path, overlayPaint)
    }

    private fun drawWavyQuad(
        canvas: Canvas,
        tl: Pair<Float, Float>,
        tr: Pair<Float, Float>,
        br: Pair<Float, Float>,
        bl: Pair<Float, Float>,
        paint: Paint,
        amplitude: Float,
        segments: Int,
        pad: Float
    ) {
        val path = Path()
        val totalPerimeter = distance(tl, tr) + distance(tr, br) + distance(br, bl) + distance(bl, tl)
        val segLen = totalPerimeter / segments

        for (i in 0..segments) {
            val dist = i * segLen
            val (bx, by) = getPointOnQuadPerimeter(tl, tr, br, bl, dist)
            val (nx, ny) = getNormalOnQuadPerimeter(tl, tr, br, bl, dist)

            val angle = ((dist / totalPerimeter * 6.28f * 3f) + ropePhase * 6.28f).toDouble()
            val wave = Math.sin(angle).toFloat() * amplitude
            val px = bx + nx * (wave + pad)
            val py = by + ny * (wave + pad)

            if (i == 0) path.moveTo(px, py)
            else path.lineTo(px, py)
        }

        canvas.drawPath(path, paint)
    }

    private fun getPointOnQuadPerimeter(tl: Pair<Float, Float>, tr: Pair<Float, Float>, br: Pair<Float, Float>, bl: Pair<Float, Float>, dist: Float): Pair<Float, Float> {
        val edges = listOf(
            Pair(tl, tr), Pair(tr, br), Pair(br, bl), Pair(bl, tl)
        )
        var remaining = dist
        for ((start, end) in edges) {
            val edgeLen = distance(start, end)
            if (remaining <= edgeLen) {
                val t = remaining / edgeLen
                return Pair(
                    start.first + (end.first - start.first) * t,
                    start.second + (end.second - start.second) * t
                )
            }
            remaining -= edgeLen
        }
        return tl
    }

    private fun getNormalOnQuadPerimeter(tl: Pair<Float, Float>, tr: Pair<Float, Float>, br: Pair<Float, Float>, bl: Pair<Float, Float>, dist: Float): Pair<Float, Float> {
        val edges = listOf(
            Pair(tl, tr), Pair(tr, br), Pair(br, bl), Pair(bl, tl)
        )
        var remaining = dist
        for ((start, end) in edges) {
            val edgeLen = distance(start, end)
            if (remaining <= edgeLen) {
                val dx = end.first - start.first
                val dy = end.second - start.second
                val len = distance(start, end)
                if (len == 0f) return Pair(0f, -1f)
                // Normal pointing outward (away from center)
                val cx = (tl.first + tr.first + br.first + bl.first) / 4
                val cy = (tl.second + tr.second + br.second + bl.second) / 4
                val mx = (start.first + end.first) / 2
                val my = (start.second + end.second) / 2
                val nx = mx - cx
                val ny = my - cy
                val nLen = sqrt(nx * nx + ny * ny)
                if (nLen == 0f) return Pair(0f, -1f)
                return Pair(nx / nLen, ny / nLen)
            }
            remaining -= edgeLen
        }
        return Pair(0f, -1f)
    }

    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
        return sqrt((a.first - b.first) * (a.first - b.first) + (a.second - b.second) * (a.second - b.second))
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
