package com.paperscanner.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.text.Text

class OcrOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#5B8C5A")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#305B8C5A")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var textBlocks: List<Text.TextBlock> = emptyList()
    private var sourceWidth = 1f
    private var sourceHeight = 1f

    var lastTranslatedOriginal = ""

    fun updateTextBlocks(visionText: Text) {
        textBlocks = visionText.textBlocks
        invalidate()
    }

    fun updateTranslatedText(visionText: Text, translatedText: String) {
        lastTranslatedOriginal = visionText.text
        invalidate()
    }

    fun setSourceSize(width: Int, height: Int) {
        sourceWidth = width.toFloat()
        sourceHeight = height.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (sourceWidth <= 1f || sourceHeight <= 1f) return

        val scaleX = width / sourceWidth
        val scaleY = height / sourceHeight

        for (block in textBlocks) {
            val box = block.boundingBox ?: continue

            val left = box.left * scaleX
            val top = box.top * scaleY
            val right = box.right * scaleX
            val bottom = box.bottom * scaleY

            // Draw filled background
            canvas.drawRoundRect(
                RectF(left, top, right, bottom), 6f, 6f, fillPaint
            )

            // Draw border
            canvas.drawRoundRect(
                RectF(left, top, right, bottom), 6f, 6f, boxPaint
            )
        }
    }
}
