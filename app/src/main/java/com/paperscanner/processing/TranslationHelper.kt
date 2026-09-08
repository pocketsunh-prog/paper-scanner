package com.paperscanner.processing

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String
)

object TranslationHelper {

    private const val TAG = "TranslationHelper"

    private val translatorCache = mutableMapOf<String, Translator>()

    /**
     * Get language code from display name
     */
    fun getLanguageCode(languageName: String): String {
        return when (languageName.lowercase()) {
            "english", "en" -> TranslateLanguage.ENGLISH
            "chinese", "simplified chinese", "zh", "zh-cn" -> TranslateLanguage.CHINESE
            "traditional chinese", "zh-tw", "zh-hk" -> TranslateLanguage.CHINESE
            "japanese", "ja", "jp" -> TranslateLanguage.JAPANESE
            "korean", "ko", "kr" -> TranslateLanguage.KOREAN
            "french", "fr" -> TranslateLanguage.FRENCH
            "german", "de" -> TranslateLanguage.GERMAN
            "spanish", "es" -> TranslateLanguage.SPANISH
            "russian", "ru" -> TranslateLanguage.RUSSIAN
            "arabic", "ar" -> TranslateLanguage.ARABIC
            "hindi", "hi" -> TranslateLanguage.HINDI
            "vietnamese", "vi" -> TranslateLanguage.VIETNAMESE
            "thai", "th" -> TranslateLanguage.THAI
            "italian", "it" -> TranslateLanguage.ITALIAN
            "portuguese", "pt" -> TranslateLanguage.PORTUGUESE
            else -> TranslateLanguage.ENGLISH
        }
    }

    /**
     * Translate text from source language to target language
     */
    suspend fun translate(
        text: String,
        targetLanguage: String,
        sourceLanguage: String = TranslateLanguage.ENGLISH
    ): TranslationResult {
        if (text.isBlank()) {
            return TranslationResult(text, text, sourceLanguage, targetLanguage)
        }

        val targetLangCode = getLanguageCode(targetLanguage)
        val sourceLangCode = getLanguageCode(sourceLanguage)

        // Don't translate if same language
        if (sourceLangCode == targetLangCode) {
            return TranslationResult(text, text, sourceLanguage, targetLanguage)
        }

        val translatorKey = "${sourceLangCode}_${targetLangCode}"

        val translator = translatorCache.getOrPut(translatorKey) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLangCode)
                .setTargetLanguage(targetLangCode)
                .build()
            Translation.getClient(options)
        }

        // Download model if needed
        try {
            downloadModel(translator)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download translation model", e)
            throw e
        }

        // Translate
        val translatedText = translateText(translator, text)

        return TranslationResult(
            originalText = text,
            translatedText = translatedText,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage
        )
    }

    private suspend fun downloadModel(translator: Translator) {
        return suspendCancellableCoroutine { continuation ->
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                .addOnFailureListener { e ->
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
        }
    }

    private suspend fun translateText(translator: Translator, text: String): String {
        return suspendCancellableCoroutine { continuation ->
            translator.translate(text)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Translation failed", e)
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
        }
    }

    /**
     * Release all cached translators
     */
    fun releaseAll() {
        translatorCache.values.forEach { it.close() }
        translatorCache.clear()
    }

    /**
     * Get list of supported languages
     */
    fun getSupportedLanguages(): List<Pair<String, String>> {
        return listOf(
            "English" to TranslateLanguage.ENGLISH,
            "简体中文" to TranslateLanguage.CHINESE,
            "繁體中文" to TranslateLanguage.CHINESE,
            "日本語" to TranslateLanguage.JAPANESE,
            "한국어" to TranslateLanguage.KOREAN,
            "Français" to TranslateLanguage.FRENCH,
            "Deutsch" to TranslateLanguage.GERMAN,
            "Español" to TranslateLanguage.SPANISH,
            "Русский" to TranslateLanguage.RUSSIAN,
            "العربية" to TranslateLanguage.ARABIC,
            "हिन्दी" to TranslateLanguage.HINDI,
            "Tiếng Việt" to TranslateLanguage.VIETNAMESE,
            "ไทย" to TranslateLanguage.THAI,
            "Italiano" to TranslateLanguage.ITALIAN,
            "Português" to TranslateLanguage.PORTUGUESE
        )
    }
}
