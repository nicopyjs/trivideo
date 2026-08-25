package com.trivideo.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.trivideo.app.databinding.ActivityMainBinding
import com.trivideo.app.databinding.PanelCellBinding
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var panels: List<PanelCellBinding>

    private var players: Array<ExoPlayer?> = arrayOfNulls(PLAYER_COUNT)
    private var activeIndex: Int = -1
    private var isPlaying: Boolean = true
    private var replaceTargetIndex: Int = -1

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlayUi() }

    private var pendingDownloadId: Long = -1

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == pendingDownloadId) {
                installDownloadedApk()
            }
        }
    }

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
        setupUpdateBanner()

        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        checkForUpdate()
    }

    override fun onDestroy() {
        unregisterReceiver(downloadReceiver)
        super.onDestroy()
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
        revealOverlayUi()
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
        revealOverlayUi()
    }

    private fun revealOverlayUi() {
        uiHandler.removeCallbacks(hideOverlayRunnable)
        binding.controlBar.root.visibility = View.VISIBLE
        for (panel in panels) {
            panel.labelFilename.visibility = View.VISIBLE
        }
        updatePanelHighlights()
        if (isPlaying) {
            uiHandler.postDelayed(hideOverlayRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun hideOverlayUi() {
        binding.controlBar.root.visibility = View.GONE
        for (panel in panels) {
            panel.labelFilename.visibility = View.GONE
            panel.activeBorder.visibility = View.GONE
        }
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
        revealOverlayUi()
    }

    private fun showEmptyStateUi() {
        uiHandler.removeCallbacks(hideOverlayRunnable)
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
        uiHandler.removeCallbacksAndMessages(null)
        releasePlayers()
        super.onStop()
    }

    private fun setupUpdateBanner() {
        binding.updateBanner.btnDismissUpdate.setOnClickListener {
            binding.updateBanner.root.visibility = View.GONE
        }
    }

    private fun checkForUpdate() {
        Thread {
            try {
                val connection = URL(
                    "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
                ).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@Thread
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)

                val tagName = json.optString("tag_name")
                val remoteVersionCode = tagName.removePrefix("v").toIntOrNull() ?: return@Thread
                if (remoteVersionCode <= BuildConfig.VERSION_CODE) return@Thread

                val assets = json.optJSONArray("assets") ?: return@Thread
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                val downloadUrl = apkUrl ?: return@Thread

                runOnUiThread { showUpdateBanner(downloadUrl) }
            } catch (_: Exception) {
                // Sin conexión o GitHub no disponible: no hacemos nada, se reintenta la próxima vez.
            }
        }.start()
    }

    private fun showUpdateBanner(apkUrl: String) {
        binding.updateBanner.root.visibility = View.VISIBLE
        binding.updateBanner.tvUpdateMessage.text = getString(R.string.update_available)
        binding.updateBanner.btnUpdate.setOnClickListener { downloadAndInstallUpdate(apkUrl) }
    }

    private fun downloadAndInstallUpdate(apkUrl: String) {
        binding.updateBanner.btnUpdate.isEnabled = false
        binding.updateBanner.tvUpdateMessage.text = getString(R.string.downloading_update)

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(getString(R.string.app_name))
            .setDestinationInExternalFilesDir(this, null, UPDATE_APK_FILENAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        pendingDownloadId = downloadManager.enqueue(request)
    }

    private fun installDownloadedApk() {
        val file = File(getExternalFilesDir(null), UPDATE_APK_FILENAME)
        if (!file.exists()) return

        val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(installIntent)
    }

    companion object {
        private const val PLAYER_COUNT = 3
        private const val PREFS_NAME = "trivideo_prefs"
        private const val AUTO_HIDE_DELAY_MS = 2000L
        private const val GITHUB_OWNER = "nicopyjs"
        private const val GITHUB_REPO = "trivideo"
        private const val UPDATE_APK_FILENAME = "update.apk"
    }
}
