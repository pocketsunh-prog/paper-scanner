package com.paperscanner.processing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.paperscanner.data.AppSettings
import com.paperscanner.data.Project
import com.paperscanner.data.ScanImage
import java.io.File
import java.io.FileOutputStream

object PdfExporter {

    fun exportProject(
        context: Context,
        project: Project,
        quality: Int = 85,
        onComplete: (Boolean, String) -> Unit
    ): Thread {
        val thread = Thread {
            try {
                val settings = AppSettings(context)
                val pdfFileName = "${project.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.pdf"

                val treeUriString = settings.pdfTreeUri

                // Build the PDF document in memory first
                val document = PdfDocument()
                val margin = 20f

                for (scanImage in project.images) {
                    val imageFile = File(scanImage.filePath)
                    if (!imageFile.exists()) continue

                    val bitmap = processImageForPdf(scanImage, quality) ?: continue

                    val pageWidth = bitmap.width + (margin * 2).toInt()
                    val pageHeight = bitmap.height + (margin * 2).toInt()
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
                    val page = document.startPage(pageInfo)

                    page.canvas.drawBitmap(bitmap, margin, margin, null)
                    document.finishPage(page)

                    bitmap.recycle()
                }

                if (project.images.isEmpty()) {
                    document.close()
                    onComplete(false, "No images to export")
                    return@Thread
                }

                if (treeUriString != null) {
                    // Use DocumentsContract to create and write to file in selected tree
                    val createdUri = createFileInTree(context, treeUriString, pdfFileName)
                    if (createdUri == null) {
                        document.close()
                        onComplete(false, "Could not create file in selected folder")
                        return@Thread
                    }
                    context.contentResolver.openOutputStream(createdUri)?.use { os ->
                        document.writeTo(os)
                    } ?: run {
                        document.close()
                        onComplete(false, "Could not write to selected folder")
                        return@Thread
                    }
                    document.close()
                    openPdf(context, createdUri, true)
                    onComplete(true, createdUri.toString())
                } else {
                    // Fall back to File API with app external storage
                    val pdfDir = File(context.getExternalFilesDir(null), settings.pdfExportPath).also { it.mkdirs() }
                    val pdfFile = File(pdfDir, pdfFileName)
                    FileOutputStream(pdfFile).use { fos ->
                        document.writeTo(fos)
                    }
                    document.close()
                    val fileUri = Uri.fromFile(pdfFile)
                    openPdf(context, fileUri, false)
                    onComplete(true, pdfFile.absolutePath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onComplete(false, e.message ?: "Unknown error")
            }
        }
        thread.start()
        return thread
    }

    private fun createFileInTree(context: Context, treeUriString: String, fileName: String): Uri? {
        return try {
            val treeUri = Uri.parse(treeUriString)
            // Get the document ID from the tree URI
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            // Build a document URI for the parent directory
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            // Create the PDF file in the selected tree directory
            DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                "application/pdf",
                fileName
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun openPdf(context: Context, pdfUri: Uri, isTreeUri: Boolean) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                if (isTreeUri) {
                    setDataAndType(pdfUri, "application/pdf")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                } else {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        File(pdfUri.path!!)
                    )
                    setDataAndType(uri, "application/pdf")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processImageForPdf(scanImage: ScanImage, quality: Int): Bitmap? {
        return try {
            val bitmap = ImageFilter.loadBitmap(scanImage.filePath) ?: return null
            val rotated = if (scanImage.rotation != 0) {
                ImageFilter.rotateBitmap(bitmap, scanImage.rotation.toFloat())
            } else bitmap

            val filtered = when (scanImage.filterMode) {
                com.paperscanner.data.ImageFilterMode.COLOR -> rotated
                com.paperscanner.data.ImageFilterMode.GRAYSCALE -> ImageFilter.toGrayscale(rotated)
                com.paperscanner.data.ImageFilterMode.BLACK_WHITE -> ImageFilter.toBlackWhite(rotated)
            }

            if (filtered != rotated) rotated.recycle()
            if (rotated != bitmap) bitmap.recycle()

            // Scale down if very large to keep PDF size reasonable
            val maxDim = 3000
            val width = filtered.width
            val height = filtered.height
            if (width > maxDim || height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(width, height)
                val newWidth = (width * scale).toInt()
                val newHeight = (height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(filtered, newWidth, newHeight, true)
                if (scaled != filtered) filtered.recycle()
                scaled
            } else {
                filtered
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
