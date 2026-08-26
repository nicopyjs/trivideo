package com.trivideo.app

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.DragEvent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trivideo.app.databinding.ActivityMainBinding
import com.trivideo.app.databinding.PanelCellBinding
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var panels: List<PanelCellBinding>

    private var players: Array<ExoPlayer?> = arrayOfNulls(MAX_PANELS)
    private var videoAspects: Array<Float?> = arrayOfNulls(MAX_PANELS)
    private var currentGridShape: Pair<Int, Int>? = null
    private var activePanelCount: Int = DEFAULT_PANEL_COUNT

    private var activeIndex: Int = -1
    private var isPlaying: Boolean = true
    private var replaceTargetIndex: Int = -1
    private var volumeLevel: Int = 100
    private var draggingView: View? = null

    private var appMode: String = MODE_FIXED
    private var poolFolder: String? = null
    private var poolClips: List<String> = emptyList()
    private var speedIndex: Int = DEFAULT_SPEED_INDEX
    private var layoutMode: Int = LAYOUT_AUTO
    private var autoRotateEnabled: Boolean = false
    private var autoRotateIntervalSec: Int = DEFAULT_ROTATE_INTERVAL
    private var isLocked: Boolean = false
    private var sessionStartMs: Long = 0L
    private var sessionRunning: Boolean = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlayUi() }

    private val autoRotateRunnable = object : Runnable {
        override fun run() {
            if (autoRotateEnabled && appMode == MODE_POOL && activePanelCount > 0) {
                swapPanelToRandomClip((0 until activePanelCount).random())
            }
            uiHandler.postDelayed(this, autoRotateIntervalSec * 1000L)
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimerText()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private var pendingDownloadId: Long = -1

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == pendingDownloadId) {
                installDownloadedApk()
            }
        }
    }

    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val folder = if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(VideoPickerActivity.EXTRA_SELECTED_FOLDER)
            } else {
                null
            }
            if (folder != null) {
                replaceTargetIndex = -1
                enterPoolMode(folder)
                return@registerForActivityResult
            }

            val paths = if (result.resultCode == RESULT_OK) {
                result.data?.getStringArrayListExtra(VideoPickerActivity.EXTRA_SELECTED_PATHS)
            } else {
                null
            }
            if (!paths.isNullOrEmpty()) {
                val uris = paths.map { Uri.fromFile(File(it)) }
                if (replaceTargetIndex in 0 until activePanelCount) {
                    handlePickedSingleUri(replaceTargetIndex, uris.first())
                } else {
                    handlePickedAllUris(uris)
                }
            }
            replaceTargetIndex = -1
        }

    private val videoSetsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uris = result.data
                    ?.getStringArrayListExtra(VideoSetsActivity.EXTRA_SELECTED_SET_URIS)
                    ?.map { Uri.parse(it) }
                if (!uris.isNullOrEmpty()) {
                    applyVideoSelection(uris)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        panels = List(MAX_PANELS) { PanelCellBinding.inflate(layoutInflater) }
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        volumeLevel = prefs.getInt(VOLUME_KEY, 100)
        appMode = prefs.getString(MODE_KEY, MODE_FIXED) ?: MODE_FIXED
        poolFolder = prefs.getString(POOL_FOLDER_KEY, null)
        speedIndex = prefs.getInt(SPEED_INDEX_KEY, DEFAULT_SPEED_INDEX)
            .coerceIn(0, SPEED_VALUES.size - 1)
        layoutMode = prefs.getInt(LAYOUT_MODE_KEY, LAYOUT_AUTO).coerceIn(LAYOUT_AUTO, LAYOUT_GRID_2X2)
        autoRotateEnabled = prefs.getBoolean(AUTO_ROTATE_KEY, false)
        autoRotateIntervalSec = prefs.getInt(AUTO_ROTATE_INTERVAL_KEY, DEFAULT_ROTATE_INTERVAL)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupImmersiveMode()
        setupPanels()
        setupEmptyState()
        setupControlBar()
        setupLockOverlay()
        setupUpdateBanner()
        updateSpeedLabel()
        updateLayoutLabel()
        updateAutoRotateLabel()

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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupImmersiveMode()
        // El tamano de playersRoot recien queda actualizado despues del proximo layout pass.
        binding.playersRoot.post { maybeRebuildGrid() }
    }

    private fun setupEmptyState() {
        binding.emptyState.btnPickVideos.setOnClickListener { launchPickerForAll() }
        binding.emptyState.btnRandomFolder.setOnClickListener { launchFolderPicker() }
    }

    private fun setupControlBar() {
        binding.controlBar.btnChangeVideos.setOnClickListener { launchPickerForAll() }
        binding.controlBar.btnSets.setOnClickListener {
            videoSetsLauncher.launch(Intent(this, VideoSetsActivity::class.java))
        }
        binding.controlBar.btnRandom.setOnClickListener { launchFolderPicker() }
        binding.controlBar.btnPlayPause.setOnClickListener { toggleMasterPlayPause() }
        binding.controlBar.btnMuteAll.setOnClickListener {
            activeIndex = -1
            applyVolumes()
            updatePanelHighlights()
            revealOverlayUi()
        }
        binding.controlBar.btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % SPEED_VALUES.size
            prefs.edit().putInt(SPEED_INDEX_KEY, speedIndex).apply()
            applySpeed()
            updateSpeedLabel()
            revealOverlayUi()
        }
        binding.controlBar.btnLayout.setOnClickListener {
            cycleLayoutMode()
            revealOverlayUi()
        }
        binding.controlBar.btnAutoRotate.setOnClickListener {
            toggleAutoRotate()
            revealOverlayUi()
        }
        binding.controlBar.btnAutoRotate.setOnLongClickListener {
            cycleAutoRotateInterval()
            revealOverlayUi()
            true
        }
        binding.controlBar.btnLock.setOnClickListener { setLocked(true) }
        binding.controlBar.btnPin.setOnClickListener { pinCurrentAsFixed() }
        binding.controlBar.btnSaveSet.setOnClickListener { saveCurrentAsSet() }

        binding.controlBar.volumeSeekBar.progress = volumeLevel
        binding.controlBar.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                volumeLevel = progress
                prefs.edit().putInt(VOLUME_KEY, volumeLevel).apply()
                applyVolumes()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                uiHandler.removeCallbacks(hideOverlayRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (isPlaying) {
                    uiHandler.postDelayed(hideOverlayRunnable, AUTO_HIDE_DELAY_MS)
                }
            }
        })
    }

    private fun setupPanels() {
        for (index in 0 until MAX_PANELS) {
            val panel = panels[index]
            val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (appMode == MODE_POOL) {
                        swapPanelToRandomClip(index)
                        revealOverlayUi()
                    } else {
                        onPanelSingleTap(index)
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!hasStorageAccess()) {
                        requestStorageAccess()
                        return true
                    }
                    replaceTargetIndex = index
                    videoPickerLauncher.launch(
                        Intent(this@MainActivity, VideoPickerActivity::class.java)
                            .putExtra(VideoPickerActivity.EXTRA_MAX_SELECTION, 1)
                            .putExtra(VideoPickerActivity.EXTRA_MIN_SELECTION, 1)
                    )
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (appMode == MODE_POOL) {
                        onPanelSingleTap(index)
                    } else {
                        startPanelDrag(index)
                    }
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val dy = e2.y - (e1?.y ?: e2.y)
                    if (abs(dy) > abs(e2.x - (e1?.x ?: e2.x)) &&
                        abs(dy) > SWIPE_MIN_DISTANCE_PX
                    ) {
                        // Deslizar arriba/abajo solo muestra u oculta la barra, sin cambiar nada.
                        if (dy < 0) revealOverlayUi() else hideOverlayUi()
                        return true
                    }
                    return false
                }
            })
            panel.playerView.setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                true
            }
            panel.root.setOnDragListener { view, event -> onPanelDragEvent(index, view, event) }
        }
    }

    private fun startPanelDrag(index: Int) {
        if (index >= activePanelCount) return
        val cell = panels[index].root
        draggingView = cell
        cell.alpha = 0.4f
        val shadow = View.DragShadowBuilder(cell)
        cell.startDragAndDrop(ClipData.newPlainText("panelIndex", index.toString()), shadow, index, 0)
    }

    private fun onPanelDragEvent(targetIndex: Int, view: View, event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_ENTERED -> {
                view.alpha = if (view === draggingView) 0.4f else 0.7f
                return true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                if (view !== draggingView) view.alpha = 1f
                return true
            }
            DragEvent.ACTION_DROP -> {
                view.alpha = 1f
                val sourceIndex = event.localState as? Int ?: return false
                if (sourceIndex != targetIndex &&
                    sourceIndex in 0 until activePanelCount &&
                    targetIndex in 0 until activePanelCount
                ) {
                    swapPanels(sourceIndex, targetIndex)
                }
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                for (i in 0 until activePanelCount) {
                    panels[i].root.alpha = 1f
                }
                draggingView = null
                return true
            }
            else -> return true
        }
    }

    private fun swapPanels(a: Int, b: Int) {
        val playerA = players[a]
        val playerB = players[b]
        players[a] = playerB
        players[b] = playerA
        panels[a].playerView.player = players[a]
        panels[b].playerView.player = players[b]

        val aspectA = videoAspects[a]
        videoAspects[a] = videoAspects[b]
        videoAspects[b] = aspectA

        val labelA = panels[a].labelFilename.text
        panels[a].labelFilename.text = panels[b].labelFilename.text
        panels[b].labelFilename.text = labelA

        val uriA = prefs.getString(uriKey(a), null)
        val uriB = prefs.getString(uriKey(b), null)
        val posA = prefs.getLong(positionKey(a), 0L)
        val posB = prefs.getLong(positionKey(b), 0L)
        val editor = prefs.edit()
        if (uriB != null) editor.putString(uriKey(a), uriB) else editor.remove(uriKey(a))
        if (uriA != null) editor.putString(uriKey(b), uriA) else editor.remove(uriKey(b))
        editor.putLong(positionKey(a), posB)
        editor.putLong(positionKey(b), posA)
        editor.apply()

        applyVolumes()
        updatePanelHighlights()
    }

    private fun onPanelSingleTap(index: Int) {
        activeIndex = if (activeIndex == index) -1 else index
        applyVolumes()
        revealOverlayUi()
    }

    private fun applyVolumes() {
        val level = volumeLevel / 100f
        for (i in 0 until activePanelCount) {
            players[i]?.volume = if (i == activeIndex) level else 0f
        }
    }

    private fun updatePanelHighlights() {
        for (i in 0 until activePanelCount) {
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
        binding.controlBar.btnPin.visibility =
            if (appMode == MODE_POOL) View.VISIBLE else View.GONE
        for (i in 0 until activePanelCount) {
            panels[i].labelFilename.visibility = View.VISIBLE
        }
        updatePanelHighlights()
        if (isPlaying) {
            uiHandler.postDelayed(hideOverlayRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun hideOverlayUi() {
        binding.controlBar.root.visibility = View.GONE
        for (i in 0 until activePanelCount) {
            panels[i].labelFilename.visibility = View.GONE
            panels[i].activeBorder.visibility = View.GONE
        }
    }

    private fun launchPickerForAll() {
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        replaceTargetIndex = -1
        videoPickerLauncher.launch(
            Intent(this, VideoPickerActivity::class.java)
                .putExtra(VideoPickerActivity.EXTRA_MAX_SELECTION, MAX_PANELS)
                .putExtra(VideoPickerActivity.EXTRA_MIN_SELECTION, MIN_PANELS)
        )
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.storage_permission_title)
                .setMessage(R.string.storage_permission_message)
                .setPositiveButton(R.string.go_to_settings) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERMISSION
            )
        }
    }

    private fun handlePickedAllUris(uris: List<Uri>) {
        applyVideoSelection(uris.take(MAX_PANELS))
    }

    private fun applyVideoSelection(uris: List<Uri>) {
        if (uris.size < MIN_PANELS) return

        appMode = MODE_FIXED
        uiHandler.removeCallbacks(autoRotateRunnable)

        val editor = prefs.edit()
        editor.putString(MODE_KEY, MODE_FIXED)
        editor.putInt(PANEL_COUNT_KEY, uris.size)
        for (i in 0 until MAX_PANELS) {
            editor.remove(positionKey(i))
            if (i < uris.size) {
                editor.putString(uriKey(i), uris[i].toString())
            } else {
                editor.remove(uriKey(i))
            }
        }
        editor.apply()

        activePanelCount = uris.size
        videoAspects = arrayOfNulls(MAX_PANELS)
        currentGridShape = null
        isPlaying = true
        releasePlayers()
        createPlayers()
        maybeRebuildGrid()

        showPlayersUi()
        loadUrisIntoPlayers(getSavedUris())
    }

    private fun handlePickedSingleUri(index: Int, uri: Uri) {
        prefs.edit()
            .putString(uriKey(index), uri.toString())
            .remove(positionKey(index))
            .apply()

        showPlayersUi()
        val player = players[index] ?: return
        panels[index].labelFilename.text = queryDisplayName(uri)
        panels[index].progressBar.visibility = View.VISIBLE
        videoAspects[index] = null
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = isPlaying
    }

    private fun uriKey(index: Int) = "uri_$index"

    private fun positionKey(index: Int) = "pos_$index"

    private fun savePlaybackPositions() {
        val editor = prefs.edit()
        for (i in 0 until activePanelCount) {
            val position = players[i]?.currentPosition ?: continue
            editor.putLong(positionKey(i), position)
        }
        editor.apply()
    }

    private fun getSavedUris(): Array<Uri?> =
        Array(MAX_PANELS) { i -> prefs.getString(uriKey(i), null)?.let { Uri.parse(it) } }

    private fun hasAnyVideo(uris: Array<Uri?>) = uris.any { it != null }

    private fun getSavedPanelCount(savedUris: Array<Uri?>): Int {
        val stored = prefs.getInt(PANEL_COUNT_KEY, -1)
        if (stored in MIN_PANELS..MAX_PANELS) return stored
        val inferred = savedUris.count { it != null }
        return if (inferred > 0) inferred.coerceIn(MIN_PANELS, MAX_PANELS) else DEFAULT_PANEL_COUNT
    }

    private fun loadUrisIntoPlayers(uris: Array<Uri?>) {
        for (i in 0 until activePanelCount) {
            val player = players[i] ?: continue
            val uri = uris[i] ?: continue
            panels[i].labelFilename.text = queryDisplayName(uri)
            panels[i].progressBar.visibility = View.VISIBLE
            player.setMediaItem(MediaItem.fromUri(uri))
            val savedPosition = prefs.getLong(positionKey(i), 0L)
            if (savedPosition > 0L) {
                player.seekTo(savedPosition)
            }
            player.prepare()
            player.playWhenReady = isPlaying
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.lastPathSegment.orEmpty()
        }
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
        startSessionTimer()
        revealOverlayUi()
    }

    private fun showEmptyStateUi() {
        uiHandler.removeCallbacks(hideOverlayRunnable)
        sessionRunning = false
        binding.emptyState.root.visibility = View.VISIBLE
        binding.controlBar.root.visibility = View.GONE
    }

    private fun startSessionTimer() {
        uiHandler.removeCallbacks(timerRunnable)
        if (!sessionRunning) {
            sessionStartMs = SystemClock.elapsedRealtime()
            sessionRunning = true
        }
        uiHandler.post(timerRunnable)
    }

    private fun updateTimerText() {
        if (!sessionRunning) return
        val elapsed = (SystemClock.elapsedRealtime() - sessionStartMs) / 1000L
        val h = elapsed / 3600L
        val m = (elapsed % 3600L) / 60L
        val s = elapsed % 60L
        binding.controlBar.tvTimer.text = if (h > 0L) {
            String.format("%d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    private fun buildPlayer(): ExoPlayer? {
        return try {
            // enableDecoderFallback: si el decoder de hardware preferido falla al iniciar
            // (comun con 4 videos largos/pesados decodificando a la vez), reintenta con uno
            // por software en vez de tirar la app. Buffers mas chicos porque con 4 players
            // simultaneos el buffer por defecto (50s c/u) puede agotar la memoria.
            val renderersFactory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 1_000, 2_000)
                .build()
            ExoPlayer.Builder(this, renderersFactory).setLoadControl(loadControl).build()
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun createPlayers() {
        for (i in 0 until activePanelCount) {
            val panel = panels[i]
            val player = buildPlayer()?.apply {
                repeatMode =
                    if (appMode == MODE_POOL) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
                volume = 0f
                playWhenReady = isPlaying
                setPlaybackParameters(PlaybackParameters(SPEED_VALUES[speedIndex]))
            }
            if (player == null) {
                panel.labelFilename.text = getString(R.string.video_load_error)
                continue
            }
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    panel.progressBar.visibility =
                        if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (playbackState == Player.STATE_ENDED && appMode == MODE_POOL) {
                        swapPanelToRandomClip(i)
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width == 0 || videoSize.height == 0) return
                    val rawAspect = videoSize.width.toFloat() / videoSize.height.toFloat()
                    videoAspects[i] = if (videoSize.unappliedRotationDegrees == 90 ||
                        videoSize.unappliedRotationDegrees == 270
                    ) {
                        1f / rawAspect
                    } else {
                        rawAspect
                    }
                    maybeRebuildGrid()
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
        for (i in 0 until MAX_PANELS) {
            panels[i].playerView.player = null
            players[i]?.release()
            players[i] = null
        }
    }

    private fun computeTargetAspect(): Float? {
        val known = (0 until activePanelCount).mapNotNull { videoAspects[it] }
        if (known.isEmpty()) return null
        val avgLn = known.map { ln(it) }.average()
        return exp(avgLn).toFloat()
    }

    private fun gridShapeCandidates(count: Int): List<Pair<Int, Int>> = when (count) {
        2 -> listOf(1 to 2, 2 to 1)
        3 -> listOf(1 to 3, 3 to 1)
        4 -> listOf(1 to 4, 4 to 1, 2 to 2)
        else -> listOf(count to 1)
    }

    private fun pickBestGridShape(
        count: Int,
        screenWidth: Int,
        screenHeight: Int,
        targetAspect: Float?,
        isPortraitDevice: Boolean
    ): Pair<Int, Int> {
        val candidates = gridShapeCandidates(count)
        if (targetAspect == null) {
            return if (isPortraitDevice) {
                candidates.first { it.second == 1 }
            } else {
                candidates.first { it.first == 1 }
            }
        }
        return candidates.minByOrNull { (rows, cols) ->
            val cellAspect = (screenWidth.toFloat() * rows) / (screenHeight.toFloat() * cols)
            abs(ln(cellAspect) - ln(targetAspect))
        } ?: candidates.first()
    }

    private fun maybeRebuildGrid() {
        val root = binding.playersRoot
        if (root.width == 0 || root.height == 0) {
            root.post { maybeRebuildGrid() }
            return
        }
        val shape = when (layoutMode) {
            LAYOUT_ONE_COL -> activePanelCount to 1
            LAYOUT_ONE_ROW -> 1 to activePanelCount
            LAYOUT_GRID_2X2 -> if (activePanelCount >= 3) 2 to 2 else 1 to activePanelCount
            else -> {
                val targetAspect = computeTargetAspect()
                val isPortraitDevice = root.height >= root.width
                pickBestGridShape(
                    activePanelCount, root.width, root.height, targetAspect, isPortraitDevice
                )
            }
        }
        if (shape == currentGridShape) return
        currentGridShape = shape
        rebuildGridViews(shape.first, shape.second)
    }

    private fun rebuildGridViews(rows: Int, cols: Int) {
        val root = binding.playersRoot
        root.removeAllViews()
        var index = 0
        for (r in 0 until rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            for (c in 0 until cols) {
                if (index >= activePanelCount) break
                val cell = panels[index].root
                (cell.parent as? ViewGroup)?.removeView(cell)
                cell.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                )
                rowLayout.addView(cell)
                index++
            }
            root.addView(rowLayout)
        }
    }

    override fun onStart() {
        super.onStart()
        val savedUris = getSavedUris()
        activePanelCount = getSavedPanelCount(savedUris)
        videoAspects = arrayOfNulls(MAX_PANELS)
        currentGridShape = null

        createPlayers()
        maybeRebuildGrid()

        if (hasAnyVideo(savedUris)) {
            showPlayersUi()
            loadUrisIntoPlayers(savedUris)
            if (appMode == MODE_POOL) {
                restorePoolIfNeeded()
                if (autoRotateEnabled) {
                    uiHandler.postDelayed(autoRotateRunnable, autoRotateIntervalSec * 1000L)
                }
            }
        } else {
            showEmptyStateUi()
        }
    }

    private fun restorePoolIfNeeded() {
        if (poolClips.isNotEmpty()) return
        val folder = poolFolder ?: return
        val cached = MediaPool.cachedFor(folder)
        if (cached.isNotEmpty()) {
            poolClips = cached
            return
        }
        Thread {
            val clips = MediaPool.scan(folder)
            runOnUiThread { poolClips = clips }
        }.start()
    }

    override fun onStop() {
        uiHandler.removeCallbacksAndMessages(null)
        savePlaybackPositions()
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

        // Si quedaba un APK de una actualizacion anterior, borrarlo: si la descarga nueva
        // fallara silenciosamente, no queremos terminar instalando el archivo viejo.
        File(getExternalFilesDir(null), UPDATE_APK_FILENAME).delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(getString(R.string.app_name))
            .setDestinationInExternalFilesDir(this, null, UPDATE_APK_FILENAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        pendingDownloadId = downloadManager.enqueue(request)
    }

    private fun installDownloadedApk() {
        val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(pendingDownloadId)
        val succeeded = downloadManager.query(query)?.use { cursor ->
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            cursor.moveToFirst() && statusIndex >= 0 &&
                cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
        } ?: false

        if (!succeeded) {
            binding.updateBanner.btnUpdate.isEnabled = true
            binding.updateBanner.tvUpdateMessage.text = getString(R.string.update_download_failed)
            return
        }

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

    // ----- Modo aleatorio (pool) -----

    private fun launchFolderPicker() {
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        replaceTargetIndex = -1
        videoPickerLauncher.launch(
            Intent(this, VideoPickerActivity::class.java)
                .putExtra(VideoPickerActivity.EXTRA_FOLDER_MODE, true)
        )
    }

    private fun enterPoolMode(folder: String) {
        appMode = MODE_POOL
        poolFolder = folder
        prefs.edit()
            .putString(MODE_KEY, MODE_POOL)
            .putString(POOL_FOLDER_KEY, folder)
            .apply()
        Toast.makeText(this, R.string.scanning_folder, Toast.LENGTH_SHORT).show()
        Thread {
            val clips = MediaPool.scan(folder)
            runOnUiThread {
                poolClips = clips
                if (clips.size < MIN_PANELS) {
                    Toast.makeText(this, R.string.pool_too_few, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Toast.makeText(
                    this,
                    getString(R.string.pool_found_toast, clips.size),
                    Toast.LENGTH_SHORT
                ).show()
                startPoolPlayback()
            }
        }.start()
    }

    private fun startPoolPlayback() {
        val count = poolClips.size.coerceIn(MIN_PANELS, MAX_PANELS)
        val picks = poolClips.shuffled().take(count)

        val editor = prefs.edit()
        editor.putInt(PANEL_COUNT_KEY, count)
        for (i in 0 until MAX_PANELS) {
            editor.remove(positionKey(i))
            if (i < picks.size) {
                editor.putString(uriKey(i), Uri.fromFile(File(picks[i])).toString())
            } else {
                editor.remove(uriKey(i))
            }
        }
        editor.apply()

        activePanelCount = count
        videoAspects = arrayOfNulls(MAX_PANELS)
        currentGridShape = null
        isPlaying = true
        releasePlayers()
        createPlayers()
        maybeRebuildGrid()

        showPlayersUi()
        loadUrisIntoPlayers(getSavedUris())

        uiHandler.removeCallbacks(autoRotateRunnable)
        if (autoRotateEnabled) {
            uiHandler.postDelayed(autoRotateRunnable, autoRotateIntervalSec * 1000L)
        }
    }

    private fun currentlyShownPaths(): Set<String> =
        (0 until activePanelCount)
            .mapNotNull { prefs.getString(uriKey(it), null)?.let { u -> Uri.parse(u).path } }
            .toSet()

    private fun randomClipExcluding(exclude: Set<String>): String? {
        if (poolClips.isEmpty()) return null
        val available = poolClips.filterNot { it in exclude }
        return (if (available.isNotEmpty()) available else poolClips).random()
    }

    private fun swapPanelToRandomClip(index: Int) {
        if (appMode != MODE_POOL) return
        if (index !in 0 until activePanelCount) return
        val player = players[index] ?: return
        val next = randomClipExcluding(currentlyShownPaths()) ?: return
        val uri = Uri.fromFile(File(next))
        prefs.edit()
            .putString(uriKey(index), uri.toString())
            .remove(positionKey(index))
            .apply()
        panels[index].labelFilename.text = queryDisplayName(uri)
        panels[index].progressBar.visibility = View.VISIBLE
        videoAspects[index] = null
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = isPlaying
    }

    /**
     * "Fijar estos": deja de rotar y pasa los clips que estan sonando ahora a modo
     * repetitivo (loop). No abre el selector: usa lo que ya hay en cada panel.
     */
    private fun pinCurrentAsFixed() {
        if (appMode != MODE_POOL) return
        appMode = MODE_FIXED
        prefs.edit().putString(MODE_KEY, MODE_FIXED).apply()

        uiHandler.removeCallbacks(autoRotateRunnable)
        if (autoRotateEnabled) {
            autoRotateEnabled = false
            prefs.edit().putBoolean(AUTO_ROTATE_KEY, false).apply()
            updateAutoRotateLabel()
        }
        for (i in 0 until activePanelCount) {
            players[i]?.setRepeatMode(Player.REPEAT_MODE_ALL)
        }
        Toast.makeText(this, R.string.pinned_toast, Toast.LENGTH_SHORT).show()
        revealOverlayUi()
    }

    /** Guarda los videos que estan en los paneles ahora mismo como un set con nombre. */
    private fun saveCurrentAsSet() {
        val uris = (0 until activePanelCount).mapNotNull { prefs.getString(uriKey(it), null) }
        if (uris.size < MIN_PANELS) {
            Toast.makeText(this, R.string.pool_too_few, Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.new_set_name_hint)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_set_name_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                    .ifEmpty { getString(R.string.default_set_name) }
                val sets = VideoSetsStore.load(this)
                sets.add(VideoSet(System.currentTimeMillis(), name, uris))
                VideoSetsStore.save(this, sets)
                Toast.makeText(
                    this,
                    getString(R.string.set_saved_toast, name),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ----- Velocidad / distribucion / auto-rotacion / bloqueo -----

    private fun applySpeed() {
        val params = PlaybackParameters(SPEED_VALUES[speedIndex])
        for (player in players) {
            player?.setPlaybackParameters(params)
        }
    }

    private fun updateSpeedLabel() {
        val value = SPEED_VALUES[speedIndex]
        val text = if (value == value.toLong().toFloat()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        binding.controlBar.btnSpeed.text = getString(R.string.speed_label, text)
    }

    private fun cycleLayoutMode() {
        var next = (layoutMode + 1) % 4
        if (next == LAYOUT_GRID_2X2 && activePanelCount < 3) {
            next = LAYOUT_AUTO
        }
        layoutMode = next
        prefs.edit().putInt(LAYOUT_MODE_KEY, layoutMode).apply()
        currentGridShape = null
        maybeRebuildGrid()
        updateLayoutLabel()
    }

    private fun updateLayoutLabel() {
        val res = when (layoutMode) {
            LAYOUT_ONE_COL -> R.string.layout_one_col
            LAYOUT_ONE_ROW -> R.string.layout_one_row
            LAYOUT_GRID_2X2 -> R.string.layout_grid
            else -> R.string.layout_auto
        }
        binding.controlBar.btnLayout.text = getString(res)
    }

    private fun toggleAutoRotate() {
        if (appMode != MODE_POOL) {
            Toast.makeText(this, R.string.auto_rotate_only_pool, Toast.LENGTH_SHORT).show()
            return
        }
        autoRotateEnabled = !autoRotateEnabled
        prefs.edit().putBoolean(AUTO_ROTATE_KEY, autoRotateEnabled).apply()
        uiHandler.removeCallbacks(autoRotateRunnable)
        if (autoRotateEnabled) {
            uiHandler.postDelayed(autoRotateRunnable, autoRotateIntervalSec * 1000L)
        }
        updateAutoRotateLabel()
    }

    private fun cycleAutoRotateInterval() {
        val currentIdx = ROTATE_INTERVALS.indexOf(autoRotateIntervalSec).takeIf { it >= 0 } ?: 1
        autoRotateIntervalSec = ROTATE_INTERVALS[(currentIdx + 1) % ROTATE_INTERVALS.size]
        prefs.edit().putInt(AUTO_ROTATE_INTERVAL_KEY, autoRotateIntervalSec).apply()
        Toast.makeText(
            this,
            getString(R.string.auto_rotate_interval_toast, autoRotateIntervalSec),
            Toast.LENGTH_SHORT
        ).show()
        if (autoRotateEnabled) {
            uiHandler.removeCallbacks(autoRotateRunnable)
            uiHandler.postDelayed(autoRotateRunnable, autoRotateIntervalSec * 1000L)
        }
        updateAutoRotateLabel()
    }

    private fun updateAutoRotateLabel() {
        binding.controlBar.btnAutoRotate.text = if (autoRotateEnabled) {
            getString(R.string.auto_rotate_on, autoRotateIntervalSec)
        } else {
            getString(R.string.auto_rotate_off)
        }
    }

    private fun setupLockOverlay() {
        binding.lockOverlay.setOnClickListener { /* traga toques */ }
        binding.btnUnlock.setOnClickListener { setLocked(false) }
    }

    private fun setLocked(locked: Boolean) {
        isLocked = locked
        binding.lockOverlay.visibility = if (locked) View.VISIBLE else View.GONE
        if (locked) {
            uiHandler.removeCallbacks(hideOverlayRunnable)
            hideOverlayUi()
        } else {
            revealOverlayUi()
        }
    }

    companion object {
        private const val PREFS_NAME = "trivideo_prefs"
        private const val PANEL_COUNT_KEY = "panel_count"
        private const val VOLUME_KEY = "volume_level"
        private const val AUTO_HIDE_DELAY_MS = 7000L
        private const val SWIPE_MIN_DISTANCE_PX = 90f
        private const val GITHUB_OWNER = "nicopyjs"
        private const val GITHUB_REPO = "trivideo"
        private const val UPDATE_APK_FILENAME = "update.apk"
        private const val MIN_PANELS = 2
        private const val MAX_PANELS = 4
        private const val DEFAULT_PANEL_COUNT = 3
        private const val REQUEST_STORAGE_PERMISSION = 1001

        private const val MODE_KEY = "mode"
        private const val MODE_FIXED = "fixed"
        private const val MODE_POOL = "pool"
        private const val POOL_FOLDER_KEY = "pool_folder"
        private const val SPEED_INDEX_KEY = "speed_index"
        private const val LAYOUT_MODE_KEY = "layout_mode"
        private const val AUTO_ROTATE_KEY = "auto_rotate"
        private const val AUTO_ROTATE_INTERVAL_KEY = "auto_rotate_interval"

        private val SPEED_VALUES = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private const val DEFAULT_SPEED_INDEX = 2

        private const val LAYOUT_AUTO = 0
        private const val LAYOUT_ONE_COL = 1
        private const val LAYOUT_ONE_ROW = 2
        private const val LAYOUT_GRID_2X2 = 3

        private val ROTATE_INTERVALS = intArrayOf(15, 30, 60, 120)
        private const val DEFAULT_ROTATE_INTERVAL = 30
    }
}
