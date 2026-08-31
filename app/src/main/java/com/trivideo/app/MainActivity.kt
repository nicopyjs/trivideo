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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
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
import com.google.android.material.bottomsheet.BottomSheetDialog
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
    private var poolClipsSet: Set<String> = emptySet()
    /** Vista de poolClips restringida por el filtro de categorias (= poolClips si no hay filtro). */
    private var activeClips: List<String> = emptyList()
    /** Codes de categoria activos en el filtro del pool; vacio = sin filtro (todas). */
    private var categoryFilter: MutableSet<String> = mutableSetOf()
    /** El selector de carpeta abierto es para entrar a modo clasificador (no a pool). */
    private var pendingClassifyFolder: Boolean = false
    /** Bolsa de barajado: clips que faltan reproducir en el ciclo actual (se saca del final). */
    private var poolBag: MutableList<String> = mutableListOf()
    private val heldPanels = BooleanArray(MAX_PANELS)
    private var speedIndex: Int = DEFAULT_SPEED_INDEX
    private var layoutMode: Int = LAYOUT_AUTO
    private var autoRotateEnabled: Boolean = false
    private var autoRotateIntervalSec: Int = DEFAULT_ROTATE_INTERVAL
    private var isLocked: Boolean = false
    private var sessionStartMs: Long = 0L
    private var sessionRunning: Boolean = false
    private var syncingControls: Boolean = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlayUi() }
    private val hideLabelsRunnable = Runnable {
        if (binding.controlBar.root.visibility != View.VISIBLE) {
            setPanelChrome(false)
        }
    }

    private val autoRotateRunnable = object : Runnable {
        override fun run() {
            if (autoRotateEnabled && appMode == MODE_POOL && activePanelCount > 0) {
                val candidates = (0 until activePanelCount).filterNot { heldPanels[it] }
                if (candidates.isNotEmpty()) {
                    swapPanelToRandomClip(candidates.random())
                }
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
                if (pendingClassifyFolder) {
                    pendingClassifyFolder = false
                    enterClassifyMode(folder)
                } else {
                    enterPoolMode(folder)
                }
                return@registerForActivityResult
            }
            pendingClassifyFolder = false

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
        setupGestureHint()
        setupUpdateBanner()
        applyPanelInsets()
        applyPanelOrientation()
        syncControls()

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
        applyPanelOrientation()
        // El tamano de playersRoot recien queda actualizado despues del proximo layout pass.
        binding.playersRoot.post { maybeRebuildGrid() }
    }

    private fun setupEmptyState() {
        binding.emptyState.btnPickVideos.setOnClickListener { launchPickerForAll() }
        binding.emptyState.btnRandomFolder.setOnClickListener { launchFolderPicker() }
        binding.emptyState.btnClassifyFolder.setOnClickListener {
            if (hasStorageAccess()) pendingClassifyFolder = true
            launchFolderPicker()
        }
    }

    private fun setupControlBar() {
        val cb = binding.controlBar

        cb.btnPlayPause.setOnClickListener { toggleMasterPlayPause() }
        cb.btnMuteAll.setOnClickListener {
            activeIndex = -1
            applyVolumes()
            updatePanelHighlights()
            revealOverlayUi()
        }
        cb.btnLock.setOnClickListener { setLocked(true) }

        cb.btnPickFolder.setOnClickListener { launchFolderPicker() }
        cb.btnChangeVideos.setOnClickListener { launchPickerForAll() }
        cb.btnSets.setOnClickListener {
            videoSetsLauncher.launch(Intent(this, VideoSetsActivity::class.java))
        }
        cb.btnSaveSet.setOnClickListener { saveCurrentAsSet() }

        cb.btnPoolCategory.setOnClickListener { showPoolByCategoryDialog() }
        cb.categoryFilterChips.setOnCheckedStateChangeListener { group, _ ->
            if (syncingControls) return@setOnCheckedStateChangeListener
            val selected = buildSet {
                for (i in 0 until group.childCount) {
                    val chip = group.getChildAt(i) as? com.google.android.material.chip.Chip ?: continue
                    if (chip.isChecked) (chip.tag as? String)?.let { add(it) }
                }
            }
            applyCategoryFilter(selected)
        }

        cb.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (syncingControls || !isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnModePool -> switchToPoolMode()
                R.id.btnModeClassify -> switchToClassifyMode()
                else -> switchToFixedMode()
            }
        }

        cb.speedChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (syncingControls) return@setOnCheckedStateChangeListener
            val idx = when (checkedIds.firstOrNull()) {
                R.id.chipSpeed050 -> 0
                R.id.chipSpeed075 -> 1
                R.id.chipSpeed125 -> 3
                R.id.chipSpeed150 -> 4
                R.id.chipSpeed200 -> 5
                R.id.chipSpeed100 -> 2
                else -> return@setOnCheckedStateChangeListener
            }
            speedIndex = idx
            prefs.edit().putInt(SPEED_INDEX_KEY, speedIndex).apply()
            applySpeed()
            revealOverlayUi()
        }

        cb.layoutToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (syncingControls || !isChecked) return@addOnButtonCheckedListener
            layoutMode = when (checkedId) {
                R.id.btnLayoutCol -> LAYOUT_ONE_COL
                R.id.btnLayoutRow -> LAYOUT_ONE_ROW
                R.id.btnLayoutGrid -> LAYOUT_GRID_2X2
                else -> LAYOUT_AUTO
            }
            prefs.edit().putInt(LAYOUT_MODE_KEY, layoutMode).apply()
            currentGridShape = null
            maybeRebuildGrid()
            revealOverlayUi()
        }

        cb.switchAutoRotate.setOnCheckedChangeListener { _, checked ->
            if (syncingControls) return@setOnCheckedChangeListener
            setAutoRotate(checked)
            revealOverlayUi()
        }

        cb.rotateChips.setOnCheckedStateChangeListener { _, checkedIds ->
            if (syncingControls) return@setOnCheckedStateChangeListener
            autoRotateIntervalSec = when (checkedIds.firstOrNull()) {
                R.id.chipRot15 -> 15
                R.id.chipRot60 -> 60
                R.id.chipRot120 -> 120
                else -> 30
            }
            prefs.edit().putInt(AUTO_ROTATE_INTERVAL_KEY, autoRotateIntervalSec).apply()
            if (autoRotateEnabled) {
                uiHandler.removeCallbacks(autoRotateRunnable)
                uiHandler.postDelayed(autoRotateRunnable, autoRotateIntervalSec * 1000L)
            }
            revealOverlayUi()
        }

        cb.volumeSeekBar.progress = volumeLevel
        cb.volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
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

    private fun setupGestureHint() {
        binding.gestureHint.setOnClickListener { dismissGestureHint() }
        binding.btnGestureHintOk.setOnClickListener { dismissGestureHint() }
    }

    private fun maybeShowGestureHint() {
        if (prefs.getBoolean(SEEN_GESTURE_HINT_KEY, false)) return
        binding.gestureHint.visibility = View.VISIBLE
    }

    private fun dismissGestureHint() {
        binding.gestureHint.visibility = View.GONE
        prefs.edit().putBoolean(SEEN_GESTURE_HINT_KEY, true).apply()
    }

    private fun applyPanelInsets() {
        val basePad = dp(20f)
        ViewCompat.setOnApplyWindowInsetsListener(binding.controlBar.panelContent) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePad + bottom)
            insets
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    /** Sincroniza todos los controles del panel con el estado actual, sin disparar sus listeners. */
    private fun syncControls() {
        val cb = binding.controlBar
        syncingControls = true

        cb.modeToggle.check(
            when (appMode) {
                MODE_POOL -> R.id.btnModePool
                MODE_CLASSIFY -> R.id.btnModeClassify
                else -> R.id.btnModeFixed
            }
        )

        cb.speedChips.check(
            when (speedIndex) {
                0 -> R.id.chipSpeed050
                1 -> R.id.chipSpeed075
                3 -> R.id.chipSpeed125
                4 -> R.id.chipSpeed150
                5 -> R.id.chipSpeed200
                else -> R.id.chipSpeed100
            }
        )

        cb.layoutToggle.check(
            when (layoutMode) {
                LAYOUT_ONE_COL -> R.id.btnLayoutCol
                LAYOUT_ONE_ROW -> R.id.btnLayoutRow
                LAYOUT_GRID_2X2 -> R.id.btnLayoutGrid
                else -> R.id.btnLayoutAuto
            }
        )
        cb.btnLayoutGrid.isEnabled = activePanelCount >= 3

        cb.switchAutoRotate.isChecked = autoRotateEnabled
        cb.rotateChips.check(
            when (autoRotateIntervalSec) {
                15 -> R.id.chipRot15
                60 -> R.id.chipRot60
                120 -> R.id.chipRot120
                else -> R.id.chipRot30
            }
        )
        for (i in 0 until cb.rotateChips.childCount) {
            cb.rotateChips.getChildAt(i).isEnabled = autoRotateEnabled
        }

        cb.btnPlayPause.setImageResource(
            if (isPlaying) R.drawable.ic_pause_24 else R.drawable.ic_play_24
        )

        rebuildCategoryChips()

        syncingControls = false
    }

    /**
     * Seccion de categorias del panel: visible solo en modo aleatorio. Los chips de
     * filtro se arman con las categorias realmente presentes en el pool actual; el
     * scroll se oculta si no hay ninguna (pero los botones Ver categoria / Categorias
     * siguen disponibles).
     */
    private fun rebuildCategoryChips() {
        val cb = binding.controlBar
        val group = cb.categoryFilterChips

        val inPool = appMode == MODE_POOL
        cb.categorySection.visibility = if (inPool) View.VISIBLE else View.GONE
        group.removeAllViews()
        if (!inPool) return

        val present = LinkedHashSet<String>()
        var hasUncategorized = false
        for (path in poolClips) {
            val c = VideoFileOps.categoryOf(path)
            if (c == null) hasUncategorized = true else present.add(c)
        }

        val known = CategoryStore.ALL.associateBy { it.code }
        val entries = present.map { code -> code to (known[code]?.name ?: code) }
        for ((code, label) in entries) {
            addFilterChip(group, code, label)
        }
        if (hasUncategorized && entries.isNotEmpty()) {
            addFilterChip(group, CategoryStore.UNCATEGORIZED, getString(R.string.category_none))
        }
        cb.categoryFilterScroll.visibility = if (group.childCount > 0) View.VISIBLE else View.GONE
        // Limpia del filtro los codes que ya no estan presentes.
        categoryFilter.retainAll(entries.map { it.first }.toSet() + CategoryStore.UNCATEGORIZED)
    }

    private fun addFilterChip(
        group: com.google.android.material.chip.ChipGroup,
        code: String,
        label: String
    ) {
        val chip = layoutInflater.inflate(R.layout.chip_category_filter, group, false)
                as com.google.android.material.chip.Chip
        chip.text = label
        chip.tag = code
        chip.isChecked = code in categoryFilter
        group.addView(chip)
    }

    private fun setAutoRotate(enabled: Boolean) {
        if (enabled && appMode != MODE_POOL) {
            Toast.makeText(this, R.string.auto_rotate_only_pool, Toast.LENGTH_SHORT).show()
            syncControls()
            return
        }
        autoRotateEnabled = enabled
        prefs.edit().putBoolean(AUTO_ROTATE_KEY, enabled).apply()
        uiHandler.removeCallbacks(autoRotateRunnable)
        if (enabled) {
            uiHandler.postDelayed(autoRotateRunnable, autoRotateIntervalSec * 1000L)
        }
        syncControls()
    }

    private fun switchToPoolMode() {
        if (appMode == MODE_POOL) return
        val folder = poolFolder
        if (folder == null) {
            launchFolderPicker()
            syncControls()
            return
        }
        val fromClassify = appMode == MODE_CLASSIFY
        appMode = MODE_POOL
        prefs.edit().putString(MODE_KEY, MODE_POOL).apply()
        for (i in 0 until activePanelCount) {
            players[i]?.setRepeatMode(Player.REPEAT_MODE_OFF)
        }
        restorePoolIfNeeded()
        uiHandler.removeCallbacks(autoRotateRunnable)
        if (autoRotateEnabled) {
            uiHandler.postDelayed(autoRotateRunnable, autoRotateIntervalSec * 1000L)
        }
        updateFavoriteButtons()
        if (!fromClassify) Toast.makeText(this, R.string.mode_pool_toast, Toast.LENGTH_SHORT).show()
        revealOverlayUi()
    }

    private fun switchToFixedMode() {
        if (appMode == MODE_FIXED) return
        if (appMode == MODE_CLASSIFY) {
            // Clasificador -> fijo: congela los 2 clips actuales en loop.
            appMode = MODE_FIXED
            prefs.edit().putString(MODE_KEY, MODE_FIXED).apply()
            for (i in 0 until activePanelCount) players[i]?.setRepeatMode(Player.REPEAT_MODE_ALL)
            updateFavoriteButtons()
            revealOverlayUi()
            return
        }
        pinCurrentAsFixed()
    }

    private fun switchToClassifyMode() {
        if (appMode == MODE_CLASSIFY) return
        val folder = poolFolder
        if (folder == null) {
            if (hasStorageAccess()) pendingClassifyFolder = true
            launchFolderPicker()
            syncControls()
            return
        }
        enterClassifyMode(folder)
    }

    private fun setupPanels() {
        for (index in 0 until MAX_PANELS) {
            val panel = panels[index]
            val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (isPoolLike()) {
                        if (index < activePanelCount && heldPanels[index]) {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.panel_held_tap_toast,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            swapPanelToRandomClip(index)
                        }
                        flashPanelFeedback()
                    } else {
                        onPanelSingleTap(index)
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (appMode == MODE_CLASSIFY) return true
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
                    when (appMode) {
                        MODE_POOL -> toggleHold(index)
                        MODE_FIXED -> startPanelDrag(index)
                        // Clasificador: sin long-press.
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
                        abs(dy) > dp(56f)
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
            panel.btnFavorite.setOnClickListener { favoritePanelVideo(index) }
            panel.btnCategorize.setOnClickListener { showCategorizeSheet(index) }
            panel.btnCatAn.setOnClickListener { setPanelCategory(index, "an") }
            panel.btnCatTt.setOnClickListener { setPanelCategory(index, "tt") }
            panel.btnCatCs.setOnClickListener { setPanelCategory(index, "cs") }
            panel.btnCatCu.setOnClickListener { setPanelCategory(index, "cu") }
            panel.btnCatOr.setOnClickListener { setPanelCategory(index, "or") }
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
        updateFavoriteButtons()
    }

    private fun onPanelSingleTap(index: Int) {
        activeIndex = if (activeIndex == index) -1 else index
        applyVolumes()
        flashPanelFeedback()
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
        uiHandler.removeCallbacks(hideLabelsRunnable)
        val panel = binding.controlBar.root
        val wasHidden = panel.visibility != View.VISIBLE
        panel.visibility = View.VISIBLE
        syncControls()
        if (wasHidden && animationsEnabled()) {
            val landscape =
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            panel.alpha = 0f
            panel.translationX = if (landscape) dp(28f).toFloat() else 0f
            panel.translationY = if (landscape) 0f else dp(20f).toFloat()
            panel.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(200L).start()
        } else {
            panel.alpha = 1f
            panel.translationX = 0f
            panel.translationY = 0f
        }
        setPanelChrome(true)
        updatePanelHighlights()
        if (isPlaying) {
            uiHandler.postDelayed(hideOverlayRunnable, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun hideOverlayUi() {
        uiHandler.removeCallbacks(hideLabelsRunnable)
        val panel = binding.controlBar.root
        panel.animate().cancel()
        panel.alpha = 1f
        panel.translationX = 0f
        panel.translationY = 0f
        panel.visibility = View.GONE
        setPanelChrome(false)
    }

    /** Muestra u oculta los nombres de cada panel (la estrella es permanente). */
    private fun setPanelChrome(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        for (i in 0 until activePanelCount) {
            panels[i].labelFilename.visibility = vis
            if (!visible) panels[i].activeBorder.visibility = View.GONE
        }
    }

    /**
     * Estrella + chrome de clasificar en cada panel activo. En modo clasificador se
     * ven los 5 botones directos (`categoryButtons`); en el resto, el boton de tag.
     */
    private fun updateFavoriteButtons() {
        val classify = appMode == MODE_CLASSIFY
        for (i in 0 until MAX_PANELS) {
            val btn = panels[i].btnFavorite
            val tag = panels[i].btnCategorize
            val row = panels[i].categoryButtons
            if (i >= activePanelCount) {
                btn.visibility = View.GONE
                tag.visibility = View.GONE
                row.visibility = View.GONE
                continue
            }
            val path = prefs.getString(uriKey(i), null)?.let { Uri.parse(it).path }
            val fav = path != null && VideoFileOps.isInFavorites(path)
            btn.visibility = View.VISIBLE
            btn.setImageResource(
                if (fav) R.drawable.ic_star_24 else R.drawable.ic_star_border_24
            )
            btn.setColorFilter(
                getColor(if (fav) R.color.brand_violet else R.color.text_primary)
            )
            btn.alpha = if (fav) 1f else 0.5f

            val cat = path?.let { VideoFileOps.categoryOf(it) }
            tag.visibility = if (classify) View.GONE else View.VISIBLE
            tag.setColorFilter(
                getColor(if (cat != null) R.color.brand_violet else R.color.text_primary)
            )
            tag.alpha = if (cat != null) 1f else 0.5f

            row.visibility = if (classify) View.VISIBLE else View.GONE
            if (classify) {
                styleCategoryButton(panels[i].btnCatAn, cat == "an")
                styleCategoryButton(panels[i].btnCatTt, cat == "tt")
                styleCategoryButton(panels[i].btnCatCs, cat == "cs")
                styleCategoryButton(panels[i].btnCatCu, cat == "cu")
                styleCategoryButton(panels[i].btnCatOr, cat == "or")
            }
        }
    }

    private fun styleCategoryButton(view: android.widget.TextView, active: Boolean) {
        view.setBackgroundResource(
            if (active) R.drawable.play_fab_bg else R.drawable.circle_btn_bg
        )
        view.alpha = if (active) 1f else 0.5f
    }

    private fun favoritePanelVideo(index: Int) {
        if (index !in 0 until activePanelCount) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            Toast.makeText(this, R.string.storage_needed_for_org, Toast.LENGTH_LONG).show()
            return
        }
        val path = prefs.getString(uriKey(index), null)?.let { Uri.parse(it).path } ?: return
        if (VideoFileOps.isInFavorites(path)) {
            // Ya esta en /Favoritos raiz. En clasificador cuenta como "archivado": lo
            // sacamos del pool de la sesion y pasamos al siguiente.
            if (appMode == MODE_CLASSIFY) {
                setPoolClips(poolClips.filterNot { it == path })
                if (!heldPanels[index]) swapPanelToRandomClip(index)
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.favorite_already, VideoFileOps.FAVORITES_DIR_NAME),
                    Toast.LENGTH_SHORT
                ).show()
            }
            flashPanelFeedback()
            return
        }
        val newFile = VideoFileOps.moveToFavorites(this, path)
        if (newFile == null) {
            Toast.makeText(this, R.string.favorite_move_failed, Toast.LENGTH_SHORT).show()
            return
        }
        VideoFileOps.updateReferences(this, path, newFile)
        setPoolClips(poolClips.filterNot { it == path || it == newFile.absolutePath })
        panels[index].labelFilename.text = newFile.name
        Toast.makeText(
            this,
            getString(R.string.favorite_move_done, VideoFileOps.FAVORITES_DIR_NAME),
            Toast.LENGTH_SHORT
        ).show()
        updateFavoriteButtons()
        if (appMode == MODE_CLASSIFY && !heldPanels[index]) swapPanelToRandomClip(index)
        flashPanelFeedback()
    }

    // ----- Categorias -----

    private fun ensureOrganizeAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            Toast.makeText(this, R.string.storage_needed_for_org, Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    /**
     * Un clip pertenece al pool actual si esta bajo la carpeta del pool y NO dentro
     * de una subcarpeta de categoria distinta a la raiz del pool. Refleja lo que hace
     * MediaPool.scan(): al navegar /Favoritos, lo ya clasificado no vuelve a salir.
     */
    private fun clipBelongsInPool(absPath: String): Boolean {
        val folder = poolFolder ?: return false
        val rootPath = File(folder).absolutePath
        if (absPath != rootPath && !absPath.startsWith(rootPath + File.separator)) return false
        val catDir = VideoFileOps.categoryOf(absPath)
            ?.let { VideoFileOps.categoryDir(it).absolutePath }
        return catDir == null || catDir == rootPath
    }

    /** Mini-selector de categoria para un panel. Ventana aparte: no dispara gestos del panel. */
    private fun showCategorizeSheet(index: Int) {
        if (index !in 0 until activePanelCount) return
        if (!ensureOrganizeAccess()) return
        val path = prefs.getString(uriKey(index), null)?.let { Uri.parse(it).path } ?: return
        val current = VideoFileOps.categoryOf(path)

        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_categorize, null)
        val chips = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.sheetCategoryChips)
        view.findViewById<android.widget.TextView>(R.id.sheetFilename).text = File(path).name

        fun addChip(code: String?, label: String) {
            val chip = layoutInflater.inflate(R.layout.chip_category_filter, chips, false)
                    as com.google.android.material.chip.Chip
            chip.text = label
            chip.isChecked = code == current
            chip.setOnClickListener {
                sheet.dismiss()
                setPanelCategory(index, code)
            }
            chips.addView(chip)
        }
        for (c in CategoryStore.ALL) addChip(c.code, c.name)
        addChip(null, getString(R.string.category_none))

        sheet.setContentView(view)
        sheet.show()
    }

    /** Aplica (o quita, si code == null) la categoria al clip del panel. */
    private fun setPanelCategory(index: Int, code: String?) {
        if (index !in 0 until activePanelCount) return
        if (!ensureOrganizeAccess()) return
        val path = prefs.getString(uriKey(index), null)?.let { Uri.parse(it).path } ?: return
        if (VideoFileOps.categoryOf(path) == code) {
            flashPanelFeedback()
            return
        }
        val newFile = if (code == null) {
            VideoFileOps.removeFromCategory(this, path)
        } else {
            VideoFileOps.moveToCategory(this, path, code)
        }
        if (newFile == null) {
            Toast.makeText(this, R.string.categorize_failed, Toast.LENGTH_SHORT).show()
            return
        }
        VideoFileOps.updateReferences(this, path, newFile)
        // El clip clasificado sale del pool salvo que sigas navegando su propia carpeta.
        val filtered = poolClips.filterNot { it == path || it == newFile.absolutePath }
        val belongs = clipBelongsInPool(newFile.absolutePath)
        setPoolClips(if (belongs) filtered + newFile.absolutePath else filtered)
        panels[index].labelFilename.text = newFile.name
        Toast.makeText(
            this,
            if (code == null) getString(R.string.category_removed_toast)
            else getString(R.string.categorized_toast, code),
            Toast.LENGTH_SHORT
        ).show()
        updateFavoriteButtons()
        syncControls()
        // Si el clip dejo el pool (flujo de limpieza), el panel avanza al siguiente.
        if (!belongs && isPoolLike() && !heldPanels[index]) {
            swapPanelToRandomClip(index)
        }
        flashPanelFeedback()
    }

    /** selected vacio = sin filtro (todas las categorias). */
    private fun applyCategoryFilter(selected: Set<String>) {
        val candidate = if (selected.isEmpty()) {
            poolClips
        } else {
            poolClips.filter {
                (VideoFileOps.categoryOf(it) ?: CategoryStore.UNCATEGORIZED) in selected
            }
        }
        if (selected.isNotEmpty() && candidate.isEmpty()) {
            Toast.makeText(this, R.string.category_empty_pool, Toast.LENGTH_SHORT).show()
            syncControls()
            return
        }
        categoryFilter = selected.toMutableSet()
        activeClips = candidate.ifEmpty { poolClips }
        refillBag()
        val allowed = activeClips.toHashSet()
        for (i in 0 until activePanelCount) {
            if (heldPanels[i]) continue
            val p = prefs.getString(uriKey(i), null)?.let { Uri.parse(it).path } ?: continue
            if (p !in allowed) swapPanelToRandomClip(i)
        }
        revealOverlayUi()
    }

    private fun rescanPool() {
        val folder = poolFolder ?: return
        Thread {
            val clips = MediaPool.scan(folder)
            runOnUiThread {
                setPoolClips(clips)
                updateFavoriteButtons()
                syncControls()
            }
        }.start()
    }

    private fun showPoolByCategoryDialog() {
        val cats = CategoryStore.ALL
        val labels = cats.map { "${it.name}  ·  ${it.short}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pool_by_category)
            .setItems(labels) { _, which ->
                if (!ensureOrganizeAccess()) return@setItems
                val dir = VideoFileOps.categoryDir(cats[which].code)
                if (!dir.isDirectory || dir.listFiles()?.any { it.isFile } != true) {
                    Toast.makeText(this, R.string.category_empty_pool, Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                categoryFilter.clear()
                enterPoolMode(dir.absolutePath)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun animationsEnabled(): Boolean = try {
        Settings.Global.getFloat(
            contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) != 0f
    } catch (_: Exception) {
        true
    }

    /**
     * Feedback liviano al tocar un panel (cambiar clip, retener, elegir audio): muestra
     * los nombres y el borde activo unos segundos, sin abrir el panel de controles.
     * El panel de controles solo aparece con el gesto de deslizar hacia arriba.
     */
    private fun flashPanelFeedback() {
        uiHandler.removeCallbacks(hideLabelsRunnable)
        setPanelChrome(true)
        updatePanelHighlights()
        if (binding.controlBar.root.visibility != View.VISIBLE) {
            uiHandler.postDelayed(hideLabelsRunnable, LABEL_FLASH_MS)
        }
    }

    private fun applyPanelOrientation() {
        val panel = binding.controlBar.root
        val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            lp.width = minOf(dp(400f), (resources.displayMetrics.widthPixels * 0.52f).toInt())
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.END or Gravity.BOTTOM
            val m = dp(8f)
            lp.setMargins(0, m, m, m)
        } else {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.BOTTOM
            lp.setMargins(0, 0, 0, 0)
        }
        panel.layoutParams = lp
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
        clearHeld()

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
        updateFavoriteButtons()
        // No abrimos el panel de controles al entrar: solo un flash de los nombres.
        // El panel aparece unicamente al deslizar hacia arriba.
        flashPanelFeedback()
    }

    private fun showEmptyStateUi() {
        uiHandler.removeCallbacks(hideOverlayRunnable)
        sessionRunning = false
        binding.emptyState.root.visibility = View.VISIBLE
        binding.controlBar.root.visibility = View.GONE
        for (i in 0 until MAX_PANELS) {
            panels[i].btnFavorite.visibility = View.GONE
            panels[i].btnCategorize.visibility = View.GONE
            panels[i].categoryButtons.visibility = View.GONE
        }
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
                    if (playbackState == Player.STATE_ENDED && appMode == MODE_POOL &&
                        !heldPanels[i]
                    ) {
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
            if (isPoolLike()) {
                restorePoolIfNeeded()
                applyHeldRepeatModes()
                if (autoRotateEnabled && appMode == MODE_POOL) {
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
            setPoolClips(cached)
            return
        }
        Thread {
            val clips = MediaPool.scan(folder)
            runOnUiThread { setPoolClips(clips) }
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
        categoryFilter.clear()
        prefs.edit()
            .putString(MODE_KEY, MODE_POOL)
            .putString(POOL_FOLDER_KEY, folder)
            .apply()
        Toast.makeText(this, R.string.scanning_folder, Toast.LENGTH_SHORT).show()
        Thread {
            val clips = MediaPool.scan(folder)
            runOnUiThread {
                setPoolClips(clips)
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

    /** Modo clasificador: recorre la carpeta con la bolsa (sin repetir), 2 videos, botones directos. */
    private fun enterClassifyMode(folder: String) {
        appMode = MODE_CLASSIFY
        poolFolder = folder
        categoryFilter.clear()
        autoRotateEnabled = false
        prefs.edit()
            .putString(MODE_KEY, MODE_CLASSIFY)
            .putString(POOL_FOLDER_KEY, folder)
            .putBoolean(AUTO_ROTATE_KEY, false)
            .apply()
        uiHandler.removeCallbacks(autoRotateRunnable)
        Toast.makeText(this, R.string.scanning_folder, Toast.LENGTH_SHORT).show()
        Thread {
            val clips = MediaPool.scan(folder)
            runOnUiThread {
                setPoolClips(clips)
                if (clips.size < 2) {
                    Toast.makeText(this, R.string.pool_too_few, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                startPoolPlayback(forceCount = 2)
                Toast.makeText(this, R.string.mode_classify_toast, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun startPoolPlayback(forceCount: Int? = null) {
        val count = forceCount ?: activeClips.size.coerceIn(MIN_PANELS, MAX_PANELS)
        val picks = activeClips.shuffled().take(count)
        // Los clips iniciales tambien cuentan como "vistos" en el ciclo de la bolsa.
        poolBag.removeAll(picks.toSet())

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
        clearHeld()
        releasePlayers()
        createPlayers()
        maybeRebuildGrid()

        showPlayersUi()
        loadUrisIntoPlayers(getSavedUris())

        uiHandler.removeCallbacks(autoRotateRunnable)
        if (autoRotateEnabled) {
            uiHandler.postDelayed(autoRotateRunnable, autoRotateIntervalSec * 1000L)
        }
        maybeShowGestureHint()
    }

    private fun currentlyShownPaths(): Set<String> =
        (0 until activePanelCount)
            .mapNotNull { prefs.getString(uriKey(it), null)?.let { u -> Uri.parse(u).path } }
            .toSet()

    /** Pool y clasificador comparten toda la maquinaria de bolsa/aleatorio. */
    private fun isPoolLike(): Boolean = appMode == MODE_POOL || appMode == MODE_CLASSIFY

    /** Asigna la lista de clips y rebaraja la bolsa. Unico punto de entrada para tocar poolClips. */
    private fun setPoolClips(clips: List<String>) {
        poolClips = clips
        poolClipsSet = clips.toHashSet()
        poolFolder?.let { MediaPool.replaceCached(it, clips) }
        recomputeActiveClips()
        refillBag()
    }

    /** Recalcula activeClips segun el filtro de categorias vigente. */
    private fun recomputeActiveClips() {
        activeClips = if (categoryFilter.isEmpty()) {
            poolClips
        } else {
            poolClips.filter {
                (VideoFileOps.categoryOf(it) ?: CategoryStore.UNCATEGORIZED) in categoryFilter
            }
        }
        if (activeClips.isEmpty()) activeClips = poolClips
    }

    private fun refillBag() {
        poolBag = activeClips.shuffled().toMutableList()
    }

    /**
     * Siguiente clip: sale de la bolsa barajada, asi cada video aparece una vez antes
     * de repetirse ninguno. Se saltan (y se re-encolan en un lugar al azar) los que
     * estan ahora en pantalla; las entradas viejas tras favoritear se descartan.
     */
    private fun randomClipExcluding(exclude: Set<String>): String? {
        if (activeClips.isEmpty()) return null
        if (activeClips.size <= exclude.size + 1) {
            val available = activeClips.filterNot { it in exclude }
            return (if (available.isNotEmpty()) available else activeClips).random()
        }
        val skipped = ArrayList<String>()
        var pick: String? = null
        var guard = 0
        while (guard++ < activeClips.size * 2) {
            if (poolBag.isEmpty()) refillBag()
            val candidate = poolBag.removeAt(poolBag.size - 1)
            if (candidate !in poolClipsSet) continue
            if (candidate in exclude) { skipped.add(candidate); continue }
            pick = candidate
            break
        }
        for (s in skipped) poolBag.add((0..poolBag.size).random(), s)
        return pick
            ?: activeClips.filterNot { it in exclude }.randomOrNull()
            ?: activeClips.random()
    }

    private fun swapPanelToRandomClip(index: Int) {
        if (!isPoolLike()) return
        if (index !in 0 until activePanelCount) return
        if (heldPanels[index]) return
        val player = players[index] ?: return
        val next = randomClipExcluding(currentlyShownPaths()) ?: run {
            if (appMode == MODE_CLASSIFY) {
                Toast.makeText(this, R.string.classify_done_toast, Toast.LENGTH_SHORT).show()
            }
            return
        }
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
        updateFavoriteButtons()
    }

    /**
     * Retener un panel: deja de rotar (auto-rotacion, fin de clip y toque simple lo
     * ignoran) y se queda en loop con su clip actual, mientras los demas siguen
     * buscando. Sirve para ir armando un set panel por panel.
     */
    private fun toggleHold(index: Int) {
        if (appMode != MODE_POOL) return
        if (index !in 0 until activePanelCount) return
        val held = !heldPanels[index]
        heldPanels[index] = held
        panels[index].holdBadge.visibility = if (held) View.VISIBLE else View.GONE
        players[index]?.setRepeatMode(
            if (held) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        )
        Toast.makeText(
            this,
            if (held) R.string.panel_hold_on_toast else R.string.panel_hold_off_toast,
            Toast.LENGTH_SHORT
        ).show()
        flashPanelFeedback()
    }

    private fun clearHeld() {
        for (i in 0 until MAX_PANELS) {
            heldPanels[i] = false
            panels[i].holdBadge.visibility = View.GONE
        }
    }

    private fun applyHeldRepeatModes() {
        for (i in 0 until activePanelCount) {
            if (heldPanels[i]) {
                players[i]?.setRepeatMode(Player.REPEAT_MODE_ALL)
                panels[i].holdBadge.visibility = View.VISIBLE
            }
        }
    }

    /**
     * pool -> fijo, con lo que ya esta en pantalla: deja de rotar y pone los clips
     * actuales en loop. No abre el selector.
     */
    private fun pinCurrentAsFixed() {
        if (appMode != MODE_POOL) return
        appMode = MODE_FIXED
        prefs.edit().putString(MODE_KEY, MODE_FIXED).apply()
        clearHeld()

        uiHandler.removeCallbacks(autoRotateRunnable)
        if (autoRotateEnabled) {
            autoRotateEnabled = false
            prefs.edit().putBoolean(AUTO_ROTATE_KEY, false).apply()
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
        private const val LABEL_FLASH_MS = 1600L
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
        private const val MODE_CLASSIFY = "classify"
        private const val POOL_FOLDER_KEY = "pool_folder"
        private const val SPEED_INDEX_KEY = "speed_index"
        private const val LAYOUT_MODE_KEY = "layout_mode"
        private const val AUTO_ROTATE_KEY = "auto_rotate"
        private const val AUTO_ROTATE_INTERVAL_KEY = "auto_rotate_interval"
        private const val SEEN_GESTURE_HINT_KEY = "seen_gesture_hint"

        private val SPEED_VALUES = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private const val DEFAULT_SPEED_INDEX = 2

        private const val LAYOUT_AUTO = 0
        private const val LAYOUT_ONE_COL = 1
        private const val LAYOUT_ONE_ROW = 2
        private const val LAYOUT_GRID_2X2 = 3

        private const val DEFAULT_ROTATE_INTERVAL = 30
    }
}
