package com.paperscanner.processing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(
    val fullText: String,
    val blocks: List<TextBlock>,
    val language: String
)

data class TextBlock(
    val text: String,
    val confidence: Float,
    val boundingBox: android.graphics.Rect?
)

object OcrHelper {

    private const val TAG = "OcrHelper"

    /**
     * Run OCR on an image file
     */
    suspend fun recognizeText(imagePath: String, language: String = "en"): OcrResult {
        val bitmap = ImageFilter.loadBitmap(imagePath)
            ?: throw IllegalArgumentException("Could not load image")

        return recognizeText(bitmap, language)
    }

    /**
     * Run OCR on a bitmap with specified language
     * Supports: en (English/Latin), ch (Simplified Chinese), cht (Traditional Chinese),
     *          ja (Japanese), ko (Korean), auto (auto-detect)
     */
    suspend fun recognizeText(bitmap: Bitmap, language: String = "en"): OcrResult {
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val options = when (language) {
            "ch", "chinese", "simplified" -> ChineseTextRecognizerOptions.Builder().build()
            "cht", "traditional", "tChinese" -> ChineseTextRecognizerOptions.Builder().build()
            "ja", "japanese" -> JapaneseTextRecognizerOptions.Builder().build()
            "ko", "korean" -> KoreanTextRecognizerOptions.Builder().build()
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        }

        val recognizer = TextRecognition.getClient(options)

        return suspendCancellableCoroutine { continuation ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val result = processResult(visionText, language)
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed", e)
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
        }
    }

    /**
     * Run OCR on a bitmap with auto language detection (tries Latin first, then CJK)
     */
    suspend fun recognizeTextAuto(bitmap: Bitmap): OcrResult {
        // Try Latin first (most common)
        val latinResult = recognizeText(bitmap, "en")

        // If low confidence or very little text, try CJK languages
        if (latinResult.fullText.length < 10) {
            try {
                val chineseResult = recognizeText(bitmap, "chinese")
                if (chineseResult.blocks.size > latinResult.blocks.size) {
                    return chineseResult
                }
            } catch (_: Exception) {}
        }

        return latinResult
    }

    private fun processResult(visionText: Text, language: String): OcrResult {
        val blocks = mutableListOf<TextBlock>()

        for (block in visionText.textBlocks) {
            val confidence = block.lines.mapNotNull { it.confidence }.average().toFloat()
            blocks.add(
                TextBlock(
                    text = block.text,
                    confidence = confidence,
                    boundingBox = block.boundingBox
                )
            )
        }

        return OcrResult(
            fullText = visionText.text,
            blocks = blocks,
            language = language
        )
    }

}
