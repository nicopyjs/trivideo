package com.trivideo.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trivideo.app.databinding.ActivityVideoSetDetailBinding
import com.trivideo.app.databinding.ItemSetDetailVideoBinding
import java.io.File

class VideoSetDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoSetDetailBinding
    private var currentSet: VideoSet? = null

    private val changeVideosLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val paths = if (result.resultCode == RESULT_OK) {
                result.data?.getStringArrayListExtra(VideoPickerActivity.EXTRA_SELECTED_PATHS)
            } else {
                null
            }
            if (!paths.isNullOrEmpty()) {
                updateSetUris(paths.map { Uri.fromFile(File(it)).toString() })
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoSetDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val setId = intent.getLongExtra(EXTRA_SET_ID, -1L)
        currentSet = VideoSetsStore.load(this).find { it.id == setId }
        if (currentSet == null) {
            finish()
            return
        }

        binding.recyclerViewVideos.layoutManager = LinearLayoutManager(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRenameSet.setOnClickListener { promptRename() }
        binding.btnChangeVideos.setOnClickListener {
            changeVideosLauncher.launch(
                Intent(this, VideoPickerActivity::class.java)
                    .putExtra(VideoPickerActivity.EXTRA_MAX_SELECTION, MAX_PANELS)
                    .putExtra(VideoPickerActivity.EXTRA_MIN_SELECTION, MIN_PANELS)
            )
        }
        binding.btnPlaySet.setOnClickListener { playSet() }

        renderSet()
    }

    private fun renderSet() {
        val set = currentSet ?: return
        binding.tvSetTitle.text = set.name
        binding.recyclerViewVideos.adapter = VideosAdapter(set.uris)
    }

    private fun updateSetUris(uris: List<String>) {
        val set = currentSet ?: return
        val updated = set.copy(uris = uris)
        currentSet = updated
        persist(updated)
        renderSet()
    }

    private fun promptRename() {
        val set = currentSet ?: return
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.new_set_name_hint)
            setText(set.name)
            setSelection(text.length)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_set_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.default_set_name) }
                val updated = set.copy(name = name)
                currentSet = updated
                persist(updated)
                renderSet()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun persist(updated: VideoSet) {
        val sets = VideoSetsStore.load(this).map { if (it.id == updated.id) updated else it }
        VideoSetsStore.save(this, sets)
    }

    private fun playSet() {
        val set = currentSet ?: return
        val result = Intent()
            .putStringArrayListExtra(VideoSetsActivity.EXTRA_SELECTED_SET_URIS, ArrayList(set.uris))
        setResult(RESULT_OK, result)
        finish()
    }

    private class VideosAdapter(private val uris: List<String>) :
        RecyclerView.Adapter<VideosAdapter.ViewHolder>() {

        override fun getItemCount(): Int = uris.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return ViewHolder(ItemSetDetailVideoBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val parsed = Uri.parse(uris[position])
            holder.binding.tvVideoName.text = parsed.lastPathSegment.orEmpty()
            holder.binding.ivThumbnail.setImageBitmap(null)
            val path = parsed.path
            if (path != null) {
                holder.bind(path)
                VideoThumbnailLoader.load(path) { bitmap ->
                    if (holder.boundPath == path) {
                        holder.binding.ivThumbnail.setImageBitmap(bitmap)
                    }
                }
            }
        }

        class ViewHolder(val binding: ItemSetDetailVideoBinding) : RecyclerView.ViewHolder(binding.root) {
            var boundPath: String? = null
            fun bind(path: String) {
                boundPath = path
            }
        }
    }

    companion object {
        const val EXTRA_SET_ID = "set_id"
        private const val MIN_PANELS = 2
        private const val MAX_PANELS = 4
    }
}
