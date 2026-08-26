package com.trivideo.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trivideo.app.databinding.ActivityVideoSetsBinding
import com.trivideo.app.databinding.ItemVideoSetBinding

class VideoSetsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoSetsBinding
    private lateinit var adapter: SetsAdapter

    private val createSetLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val paths = if (result.resultCode == RESULT_OK) {
                result.data?.getStringArrayListExtra(VideoPickerActivity.EXTRA_SELECTED_PATHS)
            } else {
                null
            }
            if (!paths.isNullOrEmpty()) {
                val uris = paths.map { Uri.fromFile(java.io.File(it)).toString() }
                promptNewSetName(uris)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoSetsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCreateSet.setOnClickListener {
            createSetLauncher.launch(
                Intent(this, VideoPickerActivity::class.java)
                    .putExtra(VideoPickerActivity.EXTRA_MAX_SELECTION, MAX_PANELS)
                    .putExtra(VideoPickerActivity.EXTRA_MIN_SELECTION, MIN_PANELS)
            )
        }

        adapter = SetsAdapter(
            onUseClick = { set -> useSet(set) },
            onRenameClick = { set -> promptRenameSet(set) },
            onDeleteClick = { set -> confirmDeleteSet(set) }
        )
        binding.recyclerViewSets.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewSets.adapter = adapter

        refreshList()
    }

    private fun refreshList() {
        val sets = VideoSetsStore.load(this)
        adapter.submitList(sets)
        binding.tvEmptySets.visibility = if (sets.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerViewSets.visibility = if (sets.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun useSet(set: VideoSet) {
        val result = Intent().putStringArrayListExtra(EXTRA_SELECTED_SET_URIS, ArrayList(set.uris))
        setResult(RESULT_OK, result)
        finish()
    }

    private fun promptNewSetName(uris: List<String>) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.new_set_name_hint)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_set_name_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.default_set_name) }
                val sets = VideoSetsStore.load(this)
                sets.add(VideoSet(System.currentTimeMillis(), name, uris))
                VideoSetsStore.save(this, sets)
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.set_saved_toast, name),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptRenameSet(set: VideoSet) {
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
                val sets = VideoSetsStore.load(this).map {
                    if (it.id == set.id) it.copy(name = name) else it
                }
                VideoSetsStore.save(this, sets)
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSet(set: VideoSet) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_set_title)
            .setMessage(getString(R.string.delete_set_message, set.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                VideoSetsStore.save(this, VideoSetsStore.load(this).filterNot { it.id == set.id })
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private class SetsAdapter(
        private val onUseClick: (VideoSet) -> Unit,
        private val onRenameClick: (VideoSet) -> Unit,
        private val onDeleteClick: (VideoSet) -> Unit
    ) : RecyclerView.Adapter<SetsAdapter.ViewHolder>() {

        private var items: List<VideoSet> = emptyList()

        fun submitList(newItems: List<VideoSet>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return ViewHolder(ItemVideoSetBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val set = items[position]
            holder.binding.tvSetName.text =
                holder.binding.root.context.getString(R.string.set_name_with_count, set.name, set.uris.size)
            holder.binding.root.setOnClickListener { onUseClick(set) }
            holder.binding.btnRenameSet.setOnClickListener { onRenameClick(set) }
            holder.binding.btnDeleteSet.setOnClickListener { onDeleteClick(set) }
        }

        class ViewHolder(val binding: ItemVideoSetBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val EXTRA_SELECTED_SET_URIS = "selected_set_uris"
        private const val MIN_PANELS = 2
        private const val MAX_PANELS = 4
    }
}
