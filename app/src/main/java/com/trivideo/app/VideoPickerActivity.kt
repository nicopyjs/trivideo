package com.trivideo.app

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trivideo.app.databinding.ActivityVideoPickerBinding
import com.trivideo.app.databinding.ItemVideoPickerFolderBinding
import com.trivideo.app.databinding.ItemVideoPickerVideoBinding
import java.io.File

class VideoPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPickerBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: PickerAdapter

    private val rootDir: File = Environment.getExternalStorageDirectory()
    private lateinit var currentDir: File

    private var maxSelection = 4
    private var minSelection = 2
    private val selectedPaths = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        maxSelection = intent.getIntExtra(EXTRA_MAX_SELECTION, 4)
        minSelection = intent.getIntExtra(EXTRA_MIN_SELECTION, 2)

        val lastPath = prefs.getString(LAST_FOLDER_KEY, null)
        currentDir = lastPath?.let { File(it) }?.takeIf { it.isDirectory } ?: rootDir

        adapter = PickerAdapter(
            onFolderClick = { folder -> navigateTo(folder) },
            onVideoClick = { file -> toggleSelection(file) }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(this, SPAN_COUNT)
        binding.recyclerView.adapter = adapter

        binding.btnUpFolder.setOnClickListener { if (canGoUp()) goUp() else finish() }
        binding.btnDone.setOnClickListener { finishWithSelection() }

        loadCurrentDir()
        updateSelectionUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (canGoUp()) goUp() else super.onBackPressed()
    }

    private fun canGoUp(): Boolean =
        currentDir.absolutePath != rootDir.absolutePath && currentDir.parentFile != null

    private fun goUp() {
        currentDir.parentFile?.let {
            currentDir = it
            loadCurrentDir()
        }
    }

    private fun navigateTo(folder: File) {
        currentDir = folder
        loadCurrentDir()
    }

    private fun loadCurrentDir() {
        binding.tvCurrentFolder.text =
            if (currentDir.absolutePath == rootDir.absolutePath) {
                getString(R.string.picker_storage_root_label)
            } else {
                currentDir.name
            }
        prefs.edit().putString(LAST_FOLDER_KEY, currentDir.absolutePath).apply()

        val entries = currentDir.listFiles()?.toList().orEmpty()
        val folders = entries.filter { it.isDirectory && !it.isHidden }
            .sortedBy { it.name.lowercase() }
        val videos = entries.filter { it.isFile && isVideoFile(it) }
            .sortedBy { it.name.lowercase() }

        val items = mutableListOf<PickerItem>()
        items += folders.map { PickerItem.Folder(it) }
        items += videos.map { PickerItem.Video(it) }

        binding.tvEmptyFolder.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(items)
        adapter.setSelected(selectedPaths)
    }

    private fun isVideoFile(file: File): Boolean = file.extension.lowercase() in VIDEO_EXTENSIONS

    private fun toggleSelection(file: File) {
        val path = file.absolutePath
        if (selectedPaths.contains(path)) {
            selectedPaths.remove(path)
        } else {
            if (selectedPaths.size >= maxSelection) {
                Toast.makeText(
                    this,
                    getString(R.string.picker_max_reached_toast, maxSelection),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            selectedPaths.add(path)
        }
        adapter.setSelected(selectedPaths)
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        binding.tvSelectedCount.text =
            getString(R.string.picker_selected_count, selectedPaths.size, maxSelection)
        binding.btnDone.isEnabled = selectedPaths.size >= minSelection
    }

    private fun finishWithSelection() {
        val result = Intent().putStringArrayListExtra(EXTRA_SELECTED_PATHS, ArrayList(selectedPaths))
        setResult(RESULT_OK, result)
        finish()
    }

    private sealed class PickerItem {
        data class Folder(val file: File) : PickerItem()
        data class Video(val file: File) : PickerItem()
    }

    private class PickerAdapter(
        private val onFolderClick: (File) -> Unit,
        private val onVideoClick: (File) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<PickerItem> = emptyList()
        private var selectedPaths: Set<String> = emptySet()

        fun submitList(newItems: List<PickerItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        fun setSelected(paths: Set<String>) {
            selectedPaths = paths.toSet()
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is PickerItem.Folder -> VIEW_TYPE_FOLDER
            is PickerItem.Video -> VIEW_TYPE_VIDEO
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_FOLDER) {
                FolderViewHolder(ItemVideoPickerFolderBinding.inflate(inflater, parent, false))
            } else {
                VideoViewHolder(ItemVideoPickerVideoBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is PickerItem.Folder -> (holder as FolderViewHolder).bind(item.file, onFolderClick)
                is PickerItem.Video -> (holder as VideoViewHolder).bind(
                    item.file,
                    item.file.absolutePath in selectedPaths,
                    onVideoClick
                )
            }
        }

        private class FolderViewHolder(
            private val binding: ItemVideoPickerFolderBinding
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(file: File, onClick: (File) -> Unit) {
                binding.tvFolderName.text = file.name
                binding.root.setOnClickListener { onClick(file) }
            }
        }

        private class VideoViewHolder(
            private val binding: ItemVideoPickerVideoBinding
        ) : RecyclerView.ViewHolder(binding.root) {
            private var boundPath: String? = null

            fun bind(file: File, isSelected: Boolean, onClick: (File) -> Unit) {
                val path = file.absolutePath
                boundPath = path
                binding.tvVideoName.text = file.name
                binding.ivSelectedCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.selectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                binding.ivThumbnail.setImageBitmap(null)
                binding.root.setOnClickListener { onClick(file) }
                ThumbnailLoader.load(path) { bitmap ->
                    if (boundPath == path) {
                        binding.ivThumbnail.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    /**
     * Cola LIFO (no FIFO): al pedir una miniatura, se va siempre al frente. Asi, si el usuario
     * scrollea rapido, lo ultimo pedido (lo que esta en pantalla ahora) se procesa antes que
     * pedidos viejos de items por los que ya paso, en vez de esperar en orden de llegada.
     */
    private object ThumbnailLoader {
        private const val THREAD_COUNT = 3

        private val cache = LruCache<String, Bitmap>(60)
        private val pending = ArrayDeque<String>()
        private val callbacks = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val lock = Any()
        private var activeThreads = 0

        fun load(path: String, onReady: (Bitmap?) -> Unit) {
            val cached = cache.get(path)
            if (cached != null) {
                onReady(cached)
                return
            }
            synchronized(lock) {
                pending.remove(path)
                pending.addFirst(path)
                callbacks.getOrPut(path) { mutableListOf() }.add(onReady)
                if (activeThreads < THREAD_COUNT) {
                    activeThreads++
                    Thread { workerLoop() }.start()
                }
            }
        }

        private fun workerLoop() {
            while (true) {
                val path = synchronized(lock) {
                    val next = pending.removeFirstOrNull()
                    if (next == null) {
                        activeThreads--
                        return
                    }
                    next
                }
                val bitmap = decode(path)
                if (bitmap != null) cache.put(path, bitmap)
                val pathCallbacks = synchronized(lock) { callbacks.remove(path) }
                pathCallbacks?.forEach { callback -> mainHandler.post { callback(bitmap) } }
            }
        }

        private fun decode(path: String): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(path)
                retriever.frameAtTime
            } catch (_: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        const val EXTRA_MAX_SELECTION = "max_selection"
        const val EXTRA_MIN_SELECTION = "min_selection"
        const val EXTRA_SELECTED_PATHS = "selected_paths"

        private const val PREFS_NAME = "trivideo_prefs"
        private const val LAST_FOLDER_KEY = "last_folder_path"
        private const val SPAN_COUNT = 3
        private const val VIEW_TYPE_FOLDER = 0
        private const val VIEW_TYPE_VIDEO = 1
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "flv", "wmv"
        )
    }
}
