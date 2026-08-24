package com.trivideo.app

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var playerViews: Array<PlayerView>
    private var players: Array<ExoPlayer?> = arrayOfNulls(PLAYER_COUNT)
    private var gestureDetectors: Array<GestureDetector?> = arrayOfNulls(PLAYER_COUNT)

    private var activeIndex: Int = -1

    private val pickVideosLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                handlePickedUris(uris)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupImmersiveMode()

        playerViews = arrayOf(
            findViewById(R.id.playerView1),
            findViewById(R.id.playerView2),
            findViewById(R.id.playerView3)
        )

        setupTapHandling()
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

    private fun setupTapHandling() {
        for (index in 0 until PLAYER_COUNT) {
            val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onPanelSingleTap(index)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    launchPicker()
                    return true
                }
            })
            gestureDetectors[index] = detector
            playerViews[index].setOnTouchListener { _, event ->
                detector.onTouchEvent(event)
                true
            }
        }
    }

    private fun onPanelSingleTap(index: Int) {
        activeIndex = if (activeIndex == index) -1 else index
        applyVolumes()
    }

    private fun applyVolumes() {
        for (i in 0 until PLAYER_COUNT) {
            players[i]?.volume = if (i == activeIndex) 1f else 0f
        }
    }

    private fun launchPicker() {
        pickVideosLauncher.launch(arrayOf("video/*"))
    }

    private fun handlePickedUris(uris: List<Uri>) {
        val chosen = uris.take(PLAYER_COUNT)
        for (uri in chosen) {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
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

        loadSavedUrisIntoPlayers()
    }

    private fun uriKey(index: Int) = "uri_$index"

    private fun getSavedUris(): List<Uri>? {
        val uris = mutableListOf<Uri>()
        for (i in 0 until PLAYER_COUNT) {
            val value = prefs.getString(uriKey(i), null) ?: return null
            uris.add(Uri.parse(value))
        }
        return uris
    }

    private fun loadSavedUrisIntoPlayers() {
        val uris = getSavedUris() ?: return
        for (i in 0 until PLAYER_COUNT) {
            players[i]?.let { player ->
                player.setMediaItem(MediaItem.fromUri(uris[i]))
                player.prepare()
            }
        }
    }

    private fun createPlayers() {
        for (i in 0 until PLAYER_COUNT) {
            val player = ExoPlayer.Builder(this).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                volume = 0f
                playWhenReady = true
            }
            players[i] = player
            playerViews[i].player = player
        }
        activeIndex = -1
    }

    private fun releasePlayers() {
        for (i in 0 until PLAYER_COUNT) {
            playerViews[i].player = null
            players[i]?.release()
            players[i] = null
        }
    }

    override fun onStart() {
        super.onStart()
        createPlayers()

        val savedUris = getSavedUris()
        if (savedUris != null) {
            loadSavedUrisIntoPlayers()
        } else {
            launchPicker()
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
