package com.paperscanner.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.paperscanner.R
import com.paperscanner.data.ProjectManager
import com.paperscanner.data.ScanImage
import com.paperscanner.processing.ImageFilter

/**
 * Full-screen viewer for the photos in a project.
 *
 * Swipe between pages, pinch or double-tap to zoom, drag to pan once zoomed, and tap to
 * hide the toolbar. Built on RecyclerView + PagerSnapHelper so no extra paging library is
 * needed.
 */
class PhotoViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_INDEX = "index"

        /**
         * Longest edge kept in memory. Photos are captured up to 4K, and decoding a few
         * of those at full size would risk an out-of-memory crash while swiping.
         */
        private const val MAX_DECODE_EDGE = 1600
    }

    private lateinit var pager: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var pageIndicator: TextView
    private lateinit var adapter: PhotoPagerAdapter

    private val snapHelper = PagerSnapHelper()
    private var currentPosition = 0
    private var chromeVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_viewer)

        val project = intent.getStringExtra(EXTRA_PROJECT_ID)
            ?.let { ProjectManager(this).getProject(it) }
        val images = project?.images?.toList().orEmpty()
        if (images.isEmpty()) {
            finish()
            return
        }

        pager = findViewById(R.id.photo_pager)
        toolbar = findViewById(R.id.toolbar)
        pageIndicator = findViewById(R.id.page_indicator)

        toolbar.title = project?.name ?: getString(R.string.view_photo)
        toolbar.setNavigationOnClickListener { finish() }

        adapter = PhotoPagerAdapter(images) { showChrome(!chromeVisible) }
        pager.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        pager.adapter = adapter
        snapHelper.attachToRecyclerView(pager)
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateIndicator()
            }
        })

        currentPosition = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, images.size - 1)
        pager.scrollToPosition(currentPosition)
        updateIndicator()
    }

    private fun updateIndicator() {
        val layoutManager = pager.layoutManager as? LinearLayoutManager
        val snapped = layoutManager?.let { manager ->
            snapHelper.findSnapView(manager)?.let { manager.getPosition(it) }
        }
        if (snapped != null && snapped != RecyclerView.NO_POSITION) {
            currentPosition = snapped
        }

        pageIndicator.text = getString(
            R.string.photo_page_indicator, currentPosition + 1, adapter.itemCount
        )
    }

    /** Tap anywhere to get the toolbar out of the way. */
    private fun showChrome(visible: Boolean) {
        chromeVisible = visible
        val visibility = if (visible) View.VISIBLE else View.GONE
        toolbar.visibility = visibility
        pageIndicator.visibility = visibility
    }

    /** Decode a page at a sensible size, upright. */
    private fun loadForViewing(scanImage: ScanImage): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(scanImage.filePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
            while (longestEdge / sampleSize > MAX_DECODE_EDGE) {
                sampleSize *= 2
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(scanImage.filePath, options) ?: return null

            if (scanImage.rotation == 0) {
                decoded
            } else {
                val rotated = ImageFilter.rotateBitmap(decoded, scanImage.rotation.toFloat())
                if (rotated != decoded) decoded.recycle()
                rotated
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private inner class PhotoPagerAdapter(
        private val images: List<ScanImage>,
        private val onTap: () -> Unit
    ) : RecyclerView.Adapter<PhotoPagerAdapter.PageHolder>() {

        inner class PageHolder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ZoomImageView = view.findViewById(R.id.photo_image)
            val missing: TextView = view.findViewById(R.id.photo_missing)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_photo_page, parent, false)
            return PageHolder(view)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val bitmap = loadForViewing(images[position])

            holder.image.onSingleTap = onTap
            // setImageBitmap resets zoom, so a recycled page always starts fitted
            holder.image.setImageBitmap(bitmap)
            holder.missing.visibility = if (bitmap == null) View.VISIBLE else View.GONE
        }

        override fun onViewRecycled(holder: PageHolder) {
            super.onViewRecycled(holder)
            holder.image.setImageBitmap(null)
        }

        override fun getItemCount() = images.size
    }
}
