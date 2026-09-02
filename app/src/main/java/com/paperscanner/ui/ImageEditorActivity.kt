package com.paperscanner.ui

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.paperscanner.R
import com.paperscanner.data.AppSettings
import com.paperscanner.data.ImageFilterMode
import com.paperscanner.data.ProjectManager
import com.paperscanner.data.ScanImage
import com.paperscanner.processing.ImageFilter
import com.paperscanner.processing.PdfExporter
import java.io.File

class ImageEditorActivity : AppCompatActivity() {

    private lateinit var projectManager: ProjectManager
    private lateinit var settings: AppSettings
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: ImageAdapter
    private lateinit var btnScan: FloatingActionButton
    private lateinit var btnImport: FloatingActionButton
    private lateinit var btnExport: FloatingActionButton

    private var projectId: String = ""
    private var project: com.paperscanner.data.Project? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_editor)

        projectId = intent.getStringExtra("project_id") ?: ""
        if (projectId.isEmpty()) {
            Toast.makeText(this, "No project selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        projectManager = ProjectManager(this)
        settings = AppSettings(this)

        recyclerView = findViewById(R.id.recycler_images)
        emptyView = findViewById(R.id.empty_view)
        btnScan = findViewById(R.id.fab_scan)
        btnImport = findViewById(R.id.fab_import)
        btnExport = findViewById(R.id.fab_export_pdf)

        adapter = ImageAdapter(
            onImageClick = { scanImage -> showImageOptions(scanImage) },
            onImageDelete = { scanImage -> deleteImage(scanImage) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Swipe to delete
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val position = vh.adapterPosition
                val scanImage = adapter.getItem(position)
                deleteImage(scanImage)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(recyclerView)

        btnScan.setOnClickListener {
            val intent = Intent(this, CameraActivity::class.java)
            intent.putExtra("project_id", projectId)
            startActivity(intent)
        }

        btnImport.setOnClickListener {
            importImages()
        }

        btnExport.setOnClickListener {
            exportToPdf()
        }
    }

    override fun onResume() {
        super.onResume()
        loadProject()
    }

    private fun loadProject() {
        project = projectManager.getProject(projectId)
        val images = project?.images ?: emptyList()
        adapter.submitList(images.toList())
        emptyView.visibility = if (images.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (images.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showImageOptions(scanImage: ScanImage) {
        val options = arrayOf(
            getString(R.string.black_white),
            getString(R.string.grayscale),
            getString(R.string.color),
            getString(R.string.rotate),
            getString(R.string.enhance),
            "Save Image",
            getString(R.string.delete)
        )

        AlertDialog.Builder(this)
            .setTitle("Image Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> applyFilter(scanImage, ImageFilterMode.BLACK_WHITE)
                    1 -> applyFilter(scanImage, ImageFilterMode.GRAYSCALE)
                    2 -> applyFilter(scanImage, ImageFilterMode.COLOR)
                    3 -> rotateImage(scanImage)
                    4 -> enhanceImage(scanImage)
                    5 -> saveImage(scanImage)
                    6 -> deleteImage(scanImage)
                }
            }
            .show()
    }

    private fun applyFilter(scanImage: ScanImage, mode: ImageFilterMode) {
        val progress = ProgressDialog(this).apply {
            setMessage("Processing...")
            setCancelable(false)
            show()
        }

        Thread {
            val success = ImageFilter.applyFilter(
                scanImage.filePath,
                scanImage.filePath,
                mode,
                settings.jpegQuality,
                scanImage.rotation
            )

            runOnUiThread {
                progress.dismiss()
                if (success) {
                    scanImage.filterMode = mode
                    project?.let { projectManager.saveProject(it) }
                    loadProject()
                    Toast.makeText(this, "Filter applied", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Processing failed", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun rotateImage(scanImage: ScanImage) {
        val progress = ProgressDialog(this).apply {
            setMessage("Rotating...")
            setCancelable(false)
            show()
        }

        Thread {
            try {
                val bitmap = ImageFilter.loadBitmap(scanImage.filePath)
                if (bitmap != null) {
                    // Apply any existing rotation first
                    val currentRotation = scanImage.rotation
                    val totalRotation = (currentRotation + 90) % 360
                    val rotated = if (totalRotation != 0) {
                        ImageFilter.rotateBitmap(bitmap, totalRotation.toFloat())
                    } else {
                        bitmap
                    }
                    ImageFilter.saveBitmap(rotated, scanImage.filePath, settings.jpegQuality)

                    if (rotated != bitmap) rotated.recycle()
                    bitmap.recycle()

                    // Reset rotation metadata since it's now baked into the image
                    scanImage.rotation = 0
                    project?.let { projectManager.saveProject(it) }
                }

                runOnUiThread {
                    progress.dismiss()
                    loadProject()
                    Toast.makeText(this, "Rotated", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(this, "Rotate failed", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun enhanceImage(scanImage: ScanImage) {
        val progress = ProgressDialog(this).apply {
            setMessage("Enhancing...")
            setCancelable(false)
            show()
        }

        Thread {
            val bitmap = ImageFilter.loadBitmap(scanImage.filePath)
            if (bitmap != null) {
                val enhanced = ImageFilter.enhance(bitmap)
                ImageFilter.saveBitmap(enhanced, scanImage.filePath, settings.jpegQuality)
                bitmap.recycle()
                enhanced.recycle()
            }

            runOnUiThread {
                progress.dismiss()
                loadProject()
                Toast.makeText(this, "Enhanced", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun saveImage(scanImage: ScanImage) {
        val progress = ProgressDialog(this).apply {
            setMessage("Saving...")
            setCancelable(false)
            show()
        }

        Thread {
            try {
                val sourceFile = File(scanImage.filePath)
                if (!sourceFile.exists()) {
                    runOnUiThread {
                        progress.dismiss()
                        Toast.makeText(this, "Source file not found", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                val fileName = "scan_${System.currentTimeMillis()}.jpg"
                val treeUriString = settings.pdfTreeUri

                if (treeUriString != null) {
                    // Save to user-selected folder via DocumentsContract
                    val treeUri = android.net.Uri.parse(treeUriString)
                    val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    val parentDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val newFileUri = android.provider.DocumentsContract.createDocument(
                        contentResolver, parentDocUri, "image/jpeg", fileName
                    )
                    if (newFileUri != null) {
                        contentResolver.openOutputStream(newFileUri)?.use { os ->
                            java.io.FileInputStream(sourceFile).use { fis ->
                                fis.copyTo(os)
                            }
                        }
                    }
                } else {
                    // Save to app external files directory
                    val saveDir = java.io.File(getExternalFilesDir(null), settings.pdfExportPath).also { it.mkdirs() }
                    val destFile = java.io.File(saveDir, fileName)
                    sourceFile.copyTo(destFile, overwrite = true)
                }

                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(this, "Image saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun deleteImage(scanImage: ScanImage) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_image)
            .setPositiveButton(R.string.confirm) { _, _ ->
                File(scanImage.filePath).delete()
                project?.images?.remove(scanImage)
                project?.let { projectManager.saveProject(it) }
                loadProject()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                loadProject()
            }
            .show()
    }

    private fun importImages() {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT && resultCode == RESULT_OK) {
            val uris = mutableListOf<Uri>()
            if (data?.clipData != null) {
                val clipData = data.clipData!!
                for (i in 0 until clipData.itemCount) {
                    uris.add(clipData.getItemAt(i).uri)
                }
            } else {
                data?.data?.let { uris.add(it) }
            }

            importFromUris(uris)
        }
    }

    private fun importFromUris(uris: List<Uri>) {
        val progress = ProgressDialog(this).apply {
            setMessage("Importing ${uris.size} images...")
            setCancelable(false)
            show()
        }

        Thread {
            for (uri in uris) {
                try {
                    val inputStream = contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (bitmap != null) {
                        val file = projectManager.generateImageFile(projectId)
                        ImageFilter.saveBitmap(bitmap, file.absolutePath, settings.jpegQuality)
                        bitmap.recycle()

                        val scanImage = ScanImage(
                            filePath = file.absolutePath,
                            filterMode = settings.imageFilterMode
                        )
                        project?.images?.add(scanImage)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            runOnUiThread {
                progress.dismiss()
                project?.let { projectManager.saveProject(it) }
                loadProject()
                Toast.makeText(this, "Imported ${uris.size} images", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun exportToPdf() {
        val proj = project ?: return
        if (proj.images.isEmpty()) {
            Toast.makeText(this, "No images to export", Toast.LENGTH_SHORT).show()
            return
        }

        val progress = ProgressDialog(this).apply {
            setMessage("Exporting PDF...")
            setCancelable(false)
            show()
        }

        PdfExporter.exportProject(this, proj, settings.jpegQuality) { success, message ->
            runOnUiThread {
                progress.dismiss()
                if (success) {
                    Toast.makeText(this, getString(R.string.pdf_exported, message), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "${getString(R.string.export_failed)}: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    inner class ImageAdapter(
        private val onImageClick: (ScanImage) -> Unit,
        private val onImageDelete: (ScanImage) -> Unit
    ) : RecyclerView.Adapter<ImageAdapter.ViewHolder>() {

        private var images: List<ScanImage> = emptyList()

        fun submitList(list: List<ScanImage>) {
            images = list
            notifyDataSetChanged()
        }

        fun getItem(position: Int): ScanImage = images[position]

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.image_thumb)
            val filterLabel: TextView = view.findViewById(R.id.filter_label)
            val pageNumber: TextView = view.findViewById(R.id.page_number)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val scanImage = images[position]

            // Clear previous image to prevent recycled bitmap issues
            holder.imageView.setImageDrawable(null)

            // Load downsampled bitmap to prevent OOM on large images
            val thumbnail = loadThumbnail(scanImage.filePath, scanImage.rotation)
            if (thumbnail != null) {
                holder.imageView.setImageBitmap(thumbnail)
            } else {
                // Show placeholder for missing/corrupted files
                holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            holder.filterLabel.text = scanImage.filterMode.name
            holder.pageNumber.text = "${position + 1}"
            holder.itemView.setOnClickListener { onImageClick(scanImage) }
        }

        private fun loadThumbnail(path: String, rotation: Int): Bitmap? {
            return try {
                // First decode bounds only to get dimensions
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)

                if (options.outWidth <= 0 || options.outHeight <= 0) return null

                // Calculate sample size to fit within max thumbnail dimensions
                val maxDim = 400
                var sampleSize = 1
                val maxEdge = maxOf(options.outWidth, options.outHeight)
                while (maxEdge / sampleSize > maxDim) {
                    sampleSize *= 2
                }

                // Decode with sample size
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeFile(path, decodeOptions) ?: return null

                // Apply rotation if needed
                val rotated = if (rotation != 0) {
                    ImageFilter.rotateBitmap(bitmap, rotation.toFloat())
                } else bitmap
                if (rotated != bitmap) bitmap.recycle()

                // Scale to thumbnail size - DO NOT recycle this, it's used by ImageView
                Bitmap.createScaledBitmap(rotated, 200, 280, true)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

        override fun getItemCount() = images.size
    }

    companion object {
        private const val REQUEST_IMPORT = 100
    }
}
