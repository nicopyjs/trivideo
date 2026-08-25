package com.trivideo.app

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.trivideo.app.databinding.ActivityMainBinding
import com.trivideo.app.databinding.PanelCellBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var panels: List<PanelCellBinding>

    private var players: Array<ExoPlayer?> = arrayOfNulls(PLAYER_COUNT)
    private var activeIndex: Int = -1
    private var isPlaying: Boolean = true
    private var replaceTargetIndex: Int = -1

    private val pickAllVideosLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                handlePickedAllUris(uris)
            }
        }

    private val pickSingleVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && replaceTargetIndex in 0 until PLAYER_COUNT) {
                handlePickedSingleUri(replaceTargetIndex, uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        panels = listOf(binding.panel1, binding.panel2, binding.panel3)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupImmersiveMode()
        setupPanels()
        setupEmptyState()
        setupControlBar()
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setupImmersiveMode()
    }

    private fun setupEmptyState() {
        binding.emptyState.btnPickVideos.setOnClickListener { launchPickerForAll() }
    }

    private fun setupControlBar() {
        binding.controlBar.btnChangeVideos.setOnClickListener { launchPickerForAll() }
        binding.controlBar.btnPlayPause.setOnClickListener { toggleMasterPlayPause() }
    }

    private fun setupPanels() {
        for (index in 0 until PLAYER_COUNT) {
            val panel = panels[index]
            val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onPanelSingleTap(index)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    replaceTargetIndex = index
                    pickSingleVideoLauncher.launch(arrayOf("video/*"))
                }
            })
            panel.playerView.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                true
            }
        }
    }

    private fun onPanelSingleTap(index: Int) {
        activeIndex = if (activeIndex == index) -1 else index
        applyVolumes()
        updatePanelHighlights()
    }

    private fun applyVolumes() {
        for (i in 0 until PLAYER_COUNT) {
            players[i]?.volume = if (i == activeIndex) 1f else 0f
        }
    }

    private fun updatePanelHighlights() {
        for (i in 0 until PLAYER_COUNT) {
            panels[i].activeBorder.visibility = if (i == activeIndex) View.VISIBLE else View.GONE
        }
    }

    private fun toggleMasterPlayPause() {
        isPlaying = !isPlaying
        for (player in players) {
            player?.playWhenReady = isPlaying
        }
        binding.controlBar.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun launchPickerForAll() {
        pickAllVideosLauncher.launch(arrayOf("video/*"))
    }

    private fun handlePickedAllUris(uris: List<Uri>) {
        val chosen = uris.take(PLAYER_COUNT)
        for (uri in chosen) {
            takePersistablePermission(uri)
        }

        val editor = prefs.edit()
        for (i in 0 until PLAYER_COUNT) {
            if (i < chosen.size) {
                editor.putString(uriKey(i), chosen[i].toString())
            } else {
                editor.remove(uriKey(i))
            }
        }
        editor.apply()

        showPlayersUi()
        loadUrisIntoPlayers(getSavedUris())
    }

    private fun handlePickedSingleUri(index: Int, uri: Uri) {
        takePersistablePermission(uri)
        prefs.edit().putString(uriKey(index), uri.toString()).apply()

        showPlayersUi()
        val player = players[index] ?: return
        panels[index].labelFilename.text = queryDisplayName(uri)
        panels[index].progressBar.visibility = View.VISIBLE
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = isPlaying
    }

    private fun takePersistablePermission(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    private fun uriKey(index: Int) = "uri_$index"

    private fun getSavedUris(): Array<Uri?> =
        Array(PLAYER_COUNT) { i -> prefs.getString(uriKey(i), null)?.let { Uri.parse(it) } }

    private fun hasAnyVideo(uris: Array<Uri?>) = uris.any { it != null }

    private fun loadUrisIntoPlayers(uris: Array<Uri?>) {
        for (i in 0 until PLAYER_COUNT) {
            val player = players[i] ?: continue
            val uri = uris[i]
            if (uri != null) {
                panels[i].labelFilename.text = queryDisplayName(uri)
                panels[i].progressBar.visibility = View.VISIBLE
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
            } else {
                panels[i].labelFilename.text = getString(R.string.hold_to_pick)
                panels[i].progressBar.visibility = View.GONE
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex) ?: uri.lastPathSegment.orEmpty()
                }
            }
        return uri.lastPathSegment.orEmpty()
    }

    private fun showPlayersUi() {
        binding.emptyState.root.visibility = View.GONE
        binding.controlBar.root.visibility = View.VISIBLE
    }

    private fun showEmptyStateUi() {
        binding.emptyState.root.visibility = View.VISIBLE
        binding.controlBar.root.visibility = View.GONE
    }

    private fun createPlayers() {
        for (i in 0 until PLAYER_COUNT) {
            val player = ExoPlayer.Builder(this).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                playWhenReady = isPlaying
            }
            val panel = panels[i]
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    panel.progressBar.visibility =
                        if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }

                override fun onPlayerError(error: PlaybackException) {
                    panel.progressBar.visibility = View.GONE
                    panel.labelFilename.text = getString(R.string.video_load_error)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.video_error_toast, i + 1),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
            players[i] = player
            panel.playerView.player = player
        }
        activeIndex = -1
        updatePanelHighlights()
        binding.controlBar.btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun releasePlayers() {
        for (i in 0 until PLAYER_COUNT) {
            panels[i].playerView.player = null
            players[i]?.release()
            players[i] = null
        }
    }

    override fun onStart() {
        super.onStart()
        createPlayers()

        val savedUris = getSavedUris()
        if (hasAnyVideo(savedUris)) {
            showPlayersUi()
            loadUrisIntoPlayers(savedUris)
        } else {
            showEmptyStateUi()
        }
    }

    override fun onStop() {
        releasePlayers()
        super.onStop()
    }

    companion object {
        private const val PLAYER_COUNT = 3
        private const val PREFS_NAME = "trivideo_prefs"
    }
}
