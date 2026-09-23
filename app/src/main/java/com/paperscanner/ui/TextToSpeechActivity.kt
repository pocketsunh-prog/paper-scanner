package com.paperscanner.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.paperscanner.R
import com.paperscanner.data.AppSettings
import com.paperscanner.data.SavedText
import com.paperscanner.data.SavedTextStore
import com.paperscanner.processing.SpeechHelper
import com.paperscanner.processing.SpeakerOption
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turns text into sound: type or paste text (or arrive here from OCR with the
 * recognised text filled in), pick which speaker reads it, then play it back.
 */
class TextToSpeechActivity : AppCompatActivity() {

    companion object {
        /** Text to pre-fill the input with. */
        const val EXTRA_TEXT = "extra_text"

        /** Start reading as soon as the engine is ready (honours the auto-read setting). */
        const val EXTRA_AUTO_SPEAK = "extra_auto_speak"

        /** OCR language label ("chinese", "ja", ...) used to pre-select a matching speaker. */
        const val EXTRA_OCR_LANGUAGE = "extra_ocr_language"

        private const val MIN_RATE = 0.5f
        private const val MAX_RATE = 2.0f
    }

    private lateinit var settings: AppSettings
    private lateinit var speech: SpeechHelper
    private lateinit var savedTexts: SavedTextStore

    private lateinit var etInput: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvSpeaker: TextView
    private lateinit var tvSavedLabel: TextView
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvPitchValue: TextView
    private lateinit var seekSpeed: SeekBar
    private lateinit var seekPitch: SeekBar
    private lateinit var btnSpeak: Button
    private lateinit var btnStop: Button
    private lateinit var switchAutoRead: SwitchMaterial

    private var autoSpeakPending = false
    private var languageHint: String? = null

    /** Row in the library the input currently belongs to, if it was saved or loaded. */
    private var loadedId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_to_speech)

        settings = AppSettings(this)
        speech = SpeechHelper(this)
        savedTexts = SavedTextStore(this)

        etInput = findViewById(R.id.et_speech_text)
        tvStatus = findViewById(R.id.tv_speech_status)
        tvSpeaker = findViewById(R.id.tv_current_speaker)
        tvSavedLabel = findViewById(R.id.tv_saved_label)
        tvSpeedValue = findViewById(R.id.tv_speed_value)
        tvPitchValue = findViewById(R.id.tv_pitch_value)
        seekSpeed = findViewById(R.id.seek_speed)
        seekPitch = findViewById(R.id.seek_pitch)
        btnSpeak = findViewById(R.id.btn_speak)
        btnStop = findViewById(R.id.btn_stop_speaking)
        switchAutoRead = findViewById(R.id.switch_auto_read)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        // Incoming text from the OCR flow
        intent.getStringExtra(EXTRA_TEXT)?.let { incoming ->
            etInput.setText(incoming)
            etInput.setSelection(etInput.text.length)
        }
        languageHint = intent.getStringExtra(EXTRA_OCR_LANGUAGE)
        autoSpeakPending = intent.getBooleanExtra(EXTRA_AUTO_SPEAK, false) && settings.ttsAutoSpeak

        setUpControls()
        setUpSpeakerPicker()
        setUpSliders()

        switchAutoRead.isChecked = settings.ttsAutoSpeak

        tvStatus.text = getString(R.string.tts_status_initializing)
        tvSpeaker.text = getString(R.string.tts_status_initializing)
        btnStop.isEnabled = false

        speech.onReady = { success -> onEngineReady(success) }
        speech.onSpeakingChanged = { speaking ->
            tvStatus.text = getString(
                if (speaking) R.string.tts_status_speaking else R.string.tts_status_ready
            )
            btnStop.isEnabled = speaking
        }
        speech.onError = { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUpControls() {
        btnSpeak.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, R.string.tts_empty_text, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            speech.speak(text)
        }

        btnStop.setOnClickListener { speech.stop() }

        findViewById<Button>(R.id.btn_paste_text).setOnClickListener { pasteFromClipboard() }

        findViewById<Button>(R.id.btn_clear_text).setOnClickListener {
            speech.stop()
            etInput.setText("")
            loadedId = null
            updateSavedLabel()
        }

        findViewById<Button>(R.id.btn_save_text).setOnClickListener { saveCurrentText() }
        findViewById<Button>(R.id.btn_load_text).setOnClickListener { showSavedTextLibrary() }

        switchAutoRead.setOnCheckedChangeListener { _, checked ->
            settings.ttsAutoSpeak = checked
        }
    }

    // --------------------------------------------------------- saved library

    /** Save the input, overwriting the loaded record when there is one. */
    private fun saveCurrentText() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.tts_empty_text, Toast.LENGTH_SHORT).show()
            return
        }

        val existingId = loadedId
        val id = try {
            savedTexts.save(existingId, text)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.tts_save_failed, Toast.LENGTH_SHORT).show()
            return
        }

        loadedId = id
        updateSavedLabel()
        Toast.makeText(
            this,
            if (existingId == null) R.string.tts_save_new else R.string.tts_save_updated,
            Toast.LENGTH_SHORT
        ).show()
    }

    /** List everything in the library; tap to load, long-press to delete. */
    private fun showSavedTextLibrary() {
        val saved = try {
            savedTexts.list()
        } catch (e: Exception) {
            emptyList()
        }

        if (saved.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.tts_library_title)
                .setMessage(R.string.tts_library_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val adapter = object : ArrayAdapter<SavedText>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, saved
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val item = saved[position]
                view.findViewById<TextView>(android.R.id.text1).text = item.title
                view.findViewById<TextView>(android.R.id.text2).text = getString(
                    R.string.tts_library_entry_detail,
                    formatter.format(Date(item.updatedAt)),
                    item.body.length
                )
                return view
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tts_library_title)
            .setAdapter(adapter) { _, which -> loadSavedText(saved[which]) }
            .setNeutralButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
                confirmDelete(saved[position])
                true
            }
        }
        dialog.show()
    }

    private fun loadSavedText(item: SavedText) {
        speech.stop()
        etInput.setText(item.body)
        etInput.setSelection(etInput.text.length)
        loadedId = item.id
        updateSavedLabel()
        Toast.makeText(
            this, getString(R.string.tts_loaded, item.title), Toast.LENGTH_SHORT
        ).show()
    }

    private fun confirmDelete(item: SavedText) {
        AlertDialog.Builder(this)
            .setTitle(R.string.tts_delete_title)
            .setMessage(getString(R.string.tts_delete_message, item.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                val removed = try {
                    savedTexts.delete(item.id)
                } catch (e: Exception) {
                    false
                }
                if (removed) {
                    if (loadedId == item.id) loadedId = null
                    updateSavedLabel()
                    Toast.makeText(this, R.string.tts_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateSavedLabel() {
        val id = loadedId
        if (id == null) {
            tvSavedLabel.text = getString(R.string.tts_not_saved)
            return
        }

        val record = try {
            savedTexts.get(id)
        } catch (e: Exception) {
            null
        }

        tvSavedLabel.text = if (record == null) {
            getString(R.string.tts_not_saved)
        } else {
            getString(R.string.tts_saved_as, record.title)
        }
    }

    private fun setUpSpeakerPicker() {
        tvSpeaker.setOnClickListener { showSpeakerPicker() }
        findViewById<Button>(R.id.btn_change_speaker).setOnClickListener { showSpeakerPicker() }
    }

    private fun setUpSliders() {
        seekSpeed.progress = rateToProgress(settings.ttsSpeechRate)
        tvSpeedValue.text = formatMultiplier(progressToRate(seekSpeed.progress))
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = progressToRate(progress)
                tvSpeedValue.text = formatMultiplier(rate)
                if (fromUser) speech.speechRate = rate
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        seekPitch.progress = rateToProgress(settings.ttsPitch)
        tvPitchValue.text = formatMultiplier(progressToRate(seekPitch.progress))
        seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = progressToRate(progress)
                tvPitchValue.text = formatMultiplier(pitch)
                if (fromUser) speech.pitch = pitch
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    // ---------------------------------------------------------------- engine

    private fun onEngineReady(success: Boolean) {
        if (success) {
            // Speak in the language the text was recognised in, when we know it
            languageHint?.let { label ->
                SpeechHelper.localeForOcrLanguage(label)?.let { locale ->
                    speech.useLanguage(locale)
                }
            }
            updateSpeakerLabel()
            tvStatus.text = getString(R.string.tts_status_ready)

            if (autoSpeakPending) {
                autoSpeakPending = false
                val text = etInput.text.toString().trim()
                if (text.isNotEmpty()) speech.speak(text)
            }
        } else {
            tvStatus.text = getString(R.string.tts_status_unavailable)
            tvSpeaker.text = getString(R.string.tts_status_unavailable)
            btnSpeak.isEnabled = false
        }
    }

    private fun updateSpeakerLabel() {
        tvSpeaker.text = speech.currentSpeakerLabel()
    }

    // --------------------------------------------------------------- picker

    private fun showSpeakerPicker() {
        if (!speech.isReady) {
            Toast.makeText(this, R.string.tts_status_initializing, Toast.LENGTH_SHORT).show()
            return
        }

        val speakers = speech.speakers()
        if (speakers.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.tts_select_speaker)
                .setMessage(R.string.tts_no_speakers)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val adapter = object : ArrayAdapter<SpeakerOption>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, speakers
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                view.findViewById<TextView>(android.R.id.text1).text = speakers[position].label
                view.findViewById<TextView>(android.R.id.text2).text = speakers[position].detail
                return view
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.tts_select_speaker)
            .setAdapter(adapter) { _, which -> applySpeaker(speakers[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applySpeaker(option: SpeakerOption) {
        if (!speech.selectSpeaker(option)) {
            Toast.makeText(this, R.string.tts_voice_failed, Toast.LENGTH_SHORT).show()
            return
        }
        updateSpeakerLabel()
        Toast.makeText(this, option.label, Toast.LENGTH_SHORT).show()
        // Let the user hear the new speaker straight away
        val text = etInput.text.toString().trim()
        speech.speak(text.ifEmpty { getString(R.string.tts_voice_sample) })
    }

    // -------------------------------------------------------------- clipboard

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this).toString()
        } else {
            ""
        }

        if (text.isBlank()) {
            Toast.makeText(this, R.string.tts_nothing_to_paste, Toast.LENGTH_SHORT).show()
            return
        }

        val existing = etInput.text.toString()
        etInput.setText(if (existing.isBlank()) text else "$existing\n$text")
        etInput.setSelection(etInput.text.length)
    }

    // ----------------------------------------------------------------- helpers

    private fun progressToRate(progress: Int): Float =
        MIN_RATE + (MAX_RATE - MIN_RATE) * (progress.coerceIn(0, 100) / 100f)

    private fun rateToProgress(rate: Float): Int =
        (((rate - MIN_RATE) / (MAX_RATE - MIN_RATE)) * 100f).roundToInt().coerceIn(0, 100)

    private fun formatMultiplier(value: Float): String =
        String.format(Locale.US, "%.2fx", value)

    override fun onPause() {
        super.onPause()
        // Don't keep talking once the screen is no longer in front
        speech.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        speech.shutdown()
        savedTexts.close()
    }
}
