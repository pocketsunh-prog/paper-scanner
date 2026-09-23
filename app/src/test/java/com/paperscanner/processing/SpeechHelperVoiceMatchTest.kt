package com.paperscanner.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default speaker is Hong Kong Cantonese. Engines disagree on how that is tagged -
 * Google reports Cantonese as `yue`, others as `zh` - so the matching has to accept
 * either without letting a Mandarin voice win.
 */
class SpeechHelperVoiceMatchTest {

    private val hkLanguage = "zh"
    private val hkCountry = "HK"

    private fun score(language: String, country: String, needsNetwork: Boolean = false): Int =
        voiceMatchScore(
            voiceLanguage = language,
            voiceCountry = country,
            needsNetwork = needsNetwork,
            targetLanguage = hkLanguage,
            targetCountry = hkCountry
        )

    @Test
    fun hongKongVoiceBeatsEveryOtherChineseVoice() {
        val hongKong = score("zh", "HK")
        val cantoneseAlias = score("yue", "HK")
        val taiwan = score("zh", "TW")
        val mainland = score("zh", "CN")

        assertTrue("zh-HK should win", hongKong > cantoneseAlias)
        assertTrue("yue-HK should beat zh-TW", cantoneseAlias > taiwan)
        // Both are the same language but neither matches Hong Kong, so they tie
        assertEquals(taiwan, mainland)
        assertTrue("a Chinese voice is still better than nothing", mainland > 0)
    }

    @Test
    fun cantoneseTaggedAsYueStillMatchesHongKong() {
        assertTrue("yue-HK must be recognised as Chinese", score("yue", "HK") > 0)
    }

    @Test
    fun unrelatedLanguagesDoNotMatch() {
        assertEquals(0, score("en", "US"))
        assertEquals(0, score("ja", "JP"))
        assertEquals(0, score("ko", "KR"))
    }

    @Test
    fun offlineVoiceBeatsAnIdenticalOnlineOne() {
        val offline = score("zh", "HK", needsNetwork = false)
        val online = score("zh", "HK", needsNetwork = true)
        assertTrue(offline > online)
    }

    @Test
    fun languageMatchOutweighsBeingOfflineElsewhere() {
        // An offline Mandarin voice must not beat an online Cantonese one
        val onlineCantonese = score("yue", "HK", needsNetwork = true)
        val offlineMandarin = score("zh", "CN", needsNetwork = false)
        assertTrue(onlineCantonese > offlineMandarin)
    }

    @Test
    fun exactLocaleStillWinsForOtherLanguages() {
        val enUs = voiceMatchScore("en", "US", false, "en", "US")
        val enGb = voiceMatchScore("en", "GB", false, "en", "US")
        assertEquals(0, voiceMatchScore("zh", "HK", false, "en", "US"))

        assertTrue(enUs > enGb)
        assertTrue(enGb > 0)
    }

    @Test
    fun defaultLocaleIsHongKong() {
        assertEquals("zh-HK", SpeechHelper.DEFAULT_LOCALE.toLanguageTag())
        assertEquals("HK", SpeechHelper.DEFAULT_LOCALE.country)
        assertEquals(SpeechHelper.HK_LANGUAGE_TAG, SpeechHelper.DEFAULT_LOCALE.toLanguageTag())
    }
}
