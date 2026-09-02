package com.paperscanner.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.paperscanner.R
import com.paperscanner.data.AppSettings
import com.paperscanner.data.ImageFilterMode

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var seekQuality: SeekBar
    private lateinit var tvQuality: TextView
    private lateinit var spinnerFilter: Spinner
    private lateinit var switchAutoCapture: SwitchMaterial
    private lateinit var switchFlash: SwitchMaterial
    private lateinit var switchFocusArea: SwitchMaterial
    private lateinit var tvPdfPath: TextView
    private lateinit var btnBrowsePdfPath: android.widget.Button
    private lateinit var btnEditPdfPath: android.widget.Button
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerSize: Spinner
    private lateinit var spinnerColor: Spinner

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleTreeUri(uri)
                updatePdfPathDisplay()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)

        seekQuality = findViewById(R.id.seek_quality)
        tvQuality = findViewById(R.id.tv_quality_value)
        spinnerFilter = findViewById(R.id.spinner_filter_mode)
        switchAutoCapture = findViewById(R.id.switch_auto_capture)
        switchFlash = findViewById(R.id.switch_flash)
        switchFocusArea = findViewById(R.id.switch_focus_area)
        tvPdfPath = findViewById(R.id.tv_pdf_path)
        btnBrowsePdfPath = findViewById(R.id.btn_browse_pdf_path)
        btnEditPdfPath = findViewById(R.id.btn_edit_pdf_path)
        spinnerResolution = findViewById(R.id.spinner_resolution)
        spinnerSize = findViewById(R.id.spinner_size)
        spinnerColor = findViewById(R.id.spinner_color)

        setupQualitySlider()
        setupFilterSpinner()
        setupSwitches()
        setupPdfPath()
        setupCameraSpinners()

        updatePdfPathDisplay()
    }

    private fun setupQualitySlider() {
        seekQuality.progress = settings.jpegQuality
        tvQuality.text = "${settings.jpegQuality}%"

        seekQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val quality = progress.coerceIn(10, 100)
                tvQuality.text = "$quality%"
                settings.jpegQuality = quality
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupFilterSpinner() {
        val modes = ImageFilterMode.values().map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFilter.adapter = adapter

        spinnerFilter.setSelection(settings.imageFilterMode.ordinal)
        spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                settings.imageFilterMode = ImageFilterMode.values()[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSwitches() {
        switchAutoCapture.isChecked = settings.autoCapture
        switchAutoCapture.setOnCheckedChangeListener { _, isChecked ->
            settings.autoCapture = isChecked
        }

        switchFlash.isChecked = settings.flashEnabled
        switchFlash.setOnCheckedChangeListener { _, isChecked ->
            settings.flashEnabled = isChecked
        }

        switchFocusArea.isChecked = settings.showFocusArea
        switchFocusArea.setOnCheckedChangeListener { _, isChecked ->
            settings.showFocusArea = isChecked
        }
    }

    private fun setupCameraSpinners() {
        // Resolution spinner
        val resolutions = listOf("LOW", "MEDIUM", "HIGH")
        val resolutionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutions)
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerResolution.adapter = resolutionAdapter
        spinnerResolution.setSelection(resolutions.indexOf(settings.cameraResolution).coerceAtLeast(0))
        spinnerResolution.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                settings.cameraResolution = resolutions[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Aspect ratio spinner
        val sizes = listOf("4:3", "16:9", "1:1")
        val sizeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sizes)
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSize.adapter = sizeAdapter
        spinnerSize.setSelection(sizes.indexOf(settings.pictureSize).coerceAtLeast(0))
        spinnerSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                settings.pictureSize = sizes[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Color mode spinner
        val colors = listOf("COLOR", "GRAYSCALE", "BLACK_WHITE")
        val colorAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, colors)
        colorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerColor.adapter = colorAdapter
        spinnerColor.setSelection(colors.indexOf(settings.cameraColorMode).coerceAtLeast(0))
        spinnerColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                settings.cameraColorMode = colors[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupPdfPath() {
        btnBrowsePdfPath.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            }
            folderPickerLauncher.launch(intent)
        }

        btnEditPdfPath.setOnClickListener {
            showEditPathDialog()
        }

        tvPdfPath.setOnClickListener {
            showEditPathDialog()
        }
    }

    private fun showEditPathDialog() {
        val input = EditText(this).apply {
            setText(settings.pdfExportPath)
            hint = "e.g. Documents/PDFs"
            setSingleLine()
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("PDF Export Folder")
            .setMessage("Enter folder path relative to app storage.\nExample: Documents/PDFs")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) {
                    settings.pdfExportPath = path
                    updatePdfPathDisplay()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleTreeUri(uri: Uri) {
        try {
            // Take persistent permission for future access
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Some providers don't support persistable permissions
        }

        // Store the tree URI for DocumentsContract file creation
        settings.pdfTreeUri = uri.toString()

        // Also store a display-friendly path
        val treeDocumentId = DocumentsContract.getTreeDocumentId(uri)
        val parts = treeDocumentId.split(":")
        val type = parts.getOrNull(0) ?: "primary"
        val path = parts.getOrNull(1) ?: ""

        settings.pdfExportPath = if (type == "primary") {
            path.ifEmpty { "Documents" }
        } else {
            "$type/$path"
        }
    }

    private fun updatePdfPathDisplay() {
        val treeUri = settings.pdfTreeUri
        val displayPath = if (treeUri != null) {
            // Show the actual URI path
            val uri = android.net.Uri.parse(treeUri)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            "/storage/$docId"
        } else {
            "${getExternalFilesDir(null)?.absolutePath}/${settings.pdfExportPath}"
        }
        tvPdfPath.text = displayPath
    }
}
