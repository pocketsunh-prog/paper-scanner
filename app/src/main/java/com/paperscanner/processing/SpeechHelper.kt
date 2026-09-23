package com.paperscanner.processing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.paperscanner.data.AppSettings
import java.util.Locale
import java.util.UUID

/**
 * A speaker ("agent") the user can pick. Wraps one voice of the system TTS engine.
 */
data class SpeakerOption(
    val name: String,
    val locale: Locale,
    val quality: Int,
    val latency: Int,
    val needsNetwork: Boolean
) {
    /** One-line friendly label, e.g. "English (United States) · Female · offline" */
    val label: String
        get() = buildString {
            append(locale.displayName.replaceFirstChar { it.uppercase() })
            gender()?.let { append(" · ").append(it) }
            append(" · ").append(if (needsNetwork) "online" else "offline")
        }

    /** Secondary line shown under [label] in the picker. */
    val detail: String get() = name

    fun gender(): String? {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.contains("female") -> "Female"
            n.contains("male") -> "Male"
            else -> null
        }
    }
}

/**
 * Ranks how well a voice matches the locale we want to speak.
 *
 * Higher is better and 0 means no relation at all, so callers can fall back to the
 * device default when nothing matches.
 *
 * Matching the *locale* outranks preferring an offline voice on purpose. Cantonese and
 * Mandarin are not mutually intelligible, so for a Hong Kong default a Cantonese voice
 * that needs a download must still beat an installed Mandarin one.
 *
 * Cantonese is tagged `yue` by some engines and `zh` by others, so the two are treated
 * as one family - that is what lets the Hong Kong default pick whichever the device
 * actually has.
 */
internal fun voiceMatchScore(
    voiceLanguage: String,
    voiceCountry: String,
    needsNetwork: Boolean,
    targetLanguage: String,
    targetCountry: String
): Int {
    val sameLanguage = voiceLanguage.equals(targetLanguage, ignoreCase = true)
    val sameCountry = voiceCountry.isNotEmpty() &&
        voiceCountry.equals(targetCountry, ignoreCase = true)
    val relatedLanguage = isChineseFamily(voiceLanguage, targetLanguage)

    var score = when {
        sameLanguage && sameCountry -> 8
        relatedLanguage && sameCountry -> 7
        sameLanguage -> 4
        relatedLanguage -> 3
        else -> return 0
    }

    if (!needsNetwork) score += 1

    return score
}

/** True when both tags name a Chinese variety (Mandarin, Cantonese, ...). */
private fun isChineseFamily(first: String, second: String): Boolean {
    val chineseVarieties = setOf("zh", "yue", "cmn", "wuu", "hak", "nan")
    return first.lowercase(Locale.ROOT) in chineseVarieties &&
        second.lowercase(Locale.ROOT) in chineseVarieties
}

/**
 * Text-to-speech wrapper around the platform TTS engine.
 *
 * Talks through [onReady], [onSpeakingChanged] and [onError]; all callbacks are
 * delivered on the main thread. Create one per screen and call [shutdown] in
 * `onDestroy`.
 */
class SpeechHelper(context: Context) {

    companion object {
        private const val TAG = "SpeechHelper"
        private const val FALLBACK_MAX_LENGTH = 3800

        /** The tag stored for the default speaker. */
        const val HK_LANGUAGE_TAG = "zh-HK"

        /** Default speaker: Cantonese as used in Hong Kong. */
        val DEFAULT_LOCALE: Locale = Locale.forLanguageTag(HK_LANGUAGE_TAG)

        /** Map an OCR language label ("chinese", "ja", ...) to a speaking locale. */
        fun localeForOcrLanguage(language: String): Locale? =
            when (language.lowercase(Locale.ROOT)) {
                "chinese", "ch", "simplified", "zh" -> Locale.SIMPLIFIED_CHINESE
                "traditional", "cht", "tchinese", "zh-tw" -> Locale.TRADITIONAL_CHINESE
                "japanese", "ja", "jp" -> Locale.JAPANESE
                "korean", "ko", "kr" -> Locale.KOREAN
                "english", "en" -> Locale.US
                else -> null
            }
    }

    private val settings = AppSettings(context.applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var released = false

    private var pendingText: String? = null
    private var speaking = false
    private var selectedVoice: Voice? = null

    /** Utterance ids handed to the engine; used to ignore callbacks from speech we cut off. */
    private val activeUtterances = mutableSetOf<String>()
    private val utteranceLock = Any()

    /** Called once the engine finished initialising. `success` is false when unusable. */
    var onReady: ((success: Boolean) -> Unit)? = null

    /** Called whenever playback starts or stops. */
    var onSpeakingChanged: ((isSpeaking: Boolean) -> Unit)? = null

    /** Called with a user-presentable message when something goes wrong. */
    var onError: ((message: String) -> Unit)? = null

    val isReady: Boolean get() = ready
    val isFailed: Boolean get() = failed
    val isSpeaking: Boolean get() = speaking

    /** Speech rate multiplier (0.5x..2.0x), persisted across sessions. */
    var speechRate: Float
        get() = settings.ttsSpeechRate
        set(value) {
            settings.ttsSpeechRate = value
            tts?.setSpeechRate(settings.ttsSpeechRate)
        }

    /** Pitch multiplier (0.5x..2.0x), persisted across sessions. */
    var pitch: Float
        get() = settings.ttsPitch
        set(value) {
            settings.ttsPitch = value
            tts?.setPitch(settings.ttsPitch)
        }

    init {
        tts = TextToSpeech(context.applicationContext) { status -> onEngineInit(status) }
    }

    // ---------------------------------------------------------------- engine

    private fun onEngineInit(status: Int) {
        if (released) return

        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            ready = false
            failed = true
            post { onReady?.invoke(false) }
            post { onError?.invoke("Text-to-speech is not available on this device") }
            return
        }

        ready = true
        failed = false
        engine.setOnUtteranceProgressListener(progressListener)

        // Restore the speaker the user picked last time, else pick a sensible default
        val stored = settings.ttsVoiceName
        val voice = stored?.let { findVoice(it) } ?: defaultVoice()
        if (voice != null) {
            if (!applyVoice(voice)) {
                post { onError?.invoke("Selected voice is not usable, using device default") }
                applyLanguageFallback(engine)
            }
        } else {
            applyLanguageFallback(engine)
        }

        engine.setSpeechRate(settings.ttsSpeechRate)
        engine.setPitch(settings.ttsPitch)

        post { onReady?.invoke(true) }

        pendingText?.let { text ->
            pendingText = null
            speak(text)
        }
    }

    private fun applyLanguageFallback(engine: TextToSpeech) {
        val locale = targetLocale()
        val result = try {
            engine.setLanguage(locale)
        } catch (e: Exception) {
            Log.e(TAG, "setLanguage failed", e)
            TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            post { onError?.invoke("No voice installed for ${locale.displayName}") }
        }
    }

    private fun applyVoice(voice: Voice): Boolean {
        val engine = tts ?: return false
        return try {
            var result = engine.setVoice(voice)
            if (result != TextToSpeech.SUCCESS) {
                // Some engines need the language selected before the voice sticks
                engine.setLanguage(voice.locale)
                result = engine.setVoice(voice)
            }
            if (result == TextToSpeech.SUCCESS) {
                selectedVoice = voice
                true
            } else {
                Log.w(TAG, "setVoice(${voice.name}) returned $result")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not apply voice ${voice.name}", e)
            false
        }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceFinished(utteranceId)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceFinished(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceFinished(utteranceId)) {
                post { onError?.invoke("Playback failed (code $errorCode)") }
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceFinished(utteranceId)
        }
    }

    /**
     * Retire an utterance. Returns true only when it belonged to the current
     * playback, so late callbacks from interrupted speech are ignored.
     */
    private fun utteranceFinished(utteranceId: String?): Boolean {
        if (utteranceId == null) return false
        val result = synchronized(utteranceLock) {
            val wasActive = activeUtterances.remove(utteranceId)
            wasActive to (wasActive && activeUtterances.isEmpty())
        }
        if (result.second) setSpeaking(false)
        return result.first
    }

    // -------------------------------------------------------------- speakers

    /** Every installed voice of the current engine, sorted by language. */
    fun availableVoices(): List<Voice> {
        val engine = tts ?: return emptyList()
        return try {
            engine.voices
                ?.filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ?.sortedWith(compareBy<Voice>({ it.locale.displayName }, { it.name }))
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Could not list voices", e)
            emptyList()
        }
    }

    /** Selectable speakers for the voice picker UI. */
    fun speakers(): List<SpeakerOption> = availableVoices().map {
        SpeakerOption(
            name = it.name,
            locale = it.locale,
            quality = it.quality,
            latency = it.latency,
            needsNetwork = it.isNetworkConnectionRequired
        )
    }

    fun findVoice(name: String): Voice? = try {
        tts?.voices?.firstOrNull { it.name == name }
    } catch (e: Exception) {
        null
    }

    /** Pick a speaker and remember it for next time. */
    fun selectSpeaker(option: SpeakerOption): Boolean {
        val voice = findVoice(option.name) ?: return false
        val applied = applyVoice(voice)
        if (applied) {
            settings.ttsVoiceName = voice.name
            settings.ttsLanguageTag = voice.locale.toLanguageTag()
        }
        return applied
    }

    /**
     * Use a voice matching [locale] for this session only, without changing what
     * the user picked (used when reading text that was recognised in a language).
     */
    fun useLanguage(locale: Locale): Boolean {
        val voice = voiceForLocale(locale) ?: return false
        return applyVoice(voice)
    }

    /** Human readable description of the current speaker. */
    fun currentSpeakerLabel(): String {
        val voice = selectedVoice ?: return "Device default (${targetLocale().displayName})"
        return SpeakerOption(
            name = voice.name,
            locale = voice.locale,
            quality = voice.quality,
            latency = voice.latency,
            needsNetwork = voice.isNetworkConnectionRequired
        ).label
    }

    private fun voiceForLocale(locale: Locale): Voice? {
        val voices = availableVoices()
        if (voices.isEmpty()) return null

        val best = voices
            .map { voice ->
                voice to voiceMatchScore(
                    voiceLanguage = voice.locale.language,
                    voiceCountry = voice.locale.country,
                    needsNetwork = voice.isNetworkConnectionRequired,
                    targetLanguage = locale.language,
                    targetCountry = locale.country
                )
            }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Voice, Int>> { it.second }
                    .thenByDescending { it.first.quality }
                    .thenBy { it.first.name }
            )
            .firstOrNull()

        return best?.first
    }

    private fun defaultVoice(): Voice? = voiceForLocale(targetLocale())

    /**
     * The speaker to use when the user has not chosen one. Hong Kong (Cantonese) is the
     * default; [HK_LANGUAGE_TAG] is used unless something else has been stored.
     */
    private fun targetLocale(): Locale {
        val tag = settings.ttsLanguageTag
        if (tag.isNotEmpty()) {
            val fromTag = Locale.forLanguageTag(tag)
            if (fromTag.language.isNotEmpty()) return fromTag
        }
        return DEFAULT_LOCALE
    }

    // ------------------------------------------------------------- playback

    /**
     * Speak [text]. If the engine is still initialising the request is queued and
     * spoken as soon as it is ready. Long text is chunked so nothing is truncated.
     */
    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        if (!ready) {
            pendingText = clean
            return
        }

        val engine = tts ?: return
        engine.stop()
        engine.setSpeechRate(settings.ttsSpeechRate)
        engine.setPitch(settings.ttsPitch)

        val maxLength = try {
            TextToSpeech.getMaxSpeechInputLength() - 1
        } catch (e: Exception) {
            FALLBACK_MAX_LENGTH
        }
        val chunks = splitForSpeech(clean, maxLength)
        if (chunks.isEmpty()) return

        val utteranceIds = chunks.map { UUID.randomUUID().toString() }

        // Register before speaking so a very fast callback isn't taken for a stale one
        synchronized(utteranceLock) {
            activeUtterances.clear()
            activeUtterances.addAll(utteranceIds)
        }
        setSpeaking(true)

        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = engine.speak(chunk, mode, null, utteranceIds[index])
            if (result == TextToSpeech.ERROR) {
                synchronized(utteranceLock) { activeUtterances.clear() }
                setSpeaking(false)
                post { onError?.invoke("Text-to-speech could not start") }
                return
            }
        }
    }

    /** Stop playback immediately. */
    fun stop() {
        synchronized(utteranceLock) { activeUtterances.clear() }
        pendingText = null
        setSpeaking(false)
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
        }
    }

    /** Release the engine. The helper cannot be used afterwards. */
    fun shutdown() {
        released = true
        synchronized(utteranceLock) { activeUtterances.clear() }
        speaking = false
        pendingText = null
        onReady = null
        onSpeakingChanged = null
        onError = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "shutdown failed", e)
        }
        tts = null
        ready = false
    }

    private fun setSpeaking(value: Boolean) {
        if (speaking == value) return
        speaking = value
        post { onSpeakingChanged?.invoke(value) }
    }

    private fun post(block: () -> Unit) {
        if (released) return
        mainHandler.post {
            if (!released) block()
        }
    }

    /**
     * Split text into engine-sized chunks, preferring sentence then word boundaries.
     */
    private fun splitForSpeech(text: String, maxLength: Int): List<String> {
        if (maxLength <= 0) return emptyList()
        if (text.length <= maxLength) return listOf(text)

        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()
        val sentences = text.split(Regex("(?<=[.!?。！？\\n])"))

        fun flush() {
            val value = buffer.toString().trim()
            if (value.isNotEmpty()) chunks.add(value)
            buffer.setLength(0)
        }

        for (sentence in sentences) {
            if (sentence.isEmpty()) continue

            if (sentence.length > maxLength) {
                flush()
                var rest = sentence
                while (rest.length > maxLength) {
                    var cut = rest.lastIndexOf(' ', maxLength)
                    if (cut <= 0) cut = maxLength
                    val piece = rest.substring(0, cut).trim()
                    if (piece.isNotEmpty()) chunks.add(piece)
                    rest = rest.substring(cut)
                }
                buffer.append(rest)
            } else if (buffer.length + sentence.length > maxLength) {
                flush()
                buffer.append(sentence)
            } else {
                buffer.append(sentence)
            }
        }
        flush()

        return chunks
    }
}
