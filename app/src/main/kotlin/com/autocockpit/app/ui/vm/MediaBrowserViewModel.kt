package com.autocockpit.app.ui.vm

import android.app.Application
import android.content.ComponentName
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autocockpit.app.media.AutoCockpitMediaService
import com.autocockpit.app.media.MediaBrowseTree
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MediaNode(
    val id: String,
    val title: String,
    val subtitle: String?,
    val playable: Boolean,
)

data class MediaUiState(
    val connected: Boolean = false,
    val levelId: String = MediaBrowseTree.ROOT_ID,
    val title: String = "Library",
    val nodes: List<MediaNode> = emptyList(),
    val nowPlayingTitle: String? = null,
    val nowPlayingArtist: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
) {
    val canGoBack: Boolean get() = levelId != MediaBrowseTree.ROOT_ID
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Drives the media screen through the real MediaBrowserCompat/MediaControllerCompat path, the
 * same way an AAOS media centre would. The browse hierarchy and playback live in the service.
 */
class MediaBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MediaUiState())
    val state: StateFlow<MediaUiState> = _state.asStateFlow()

    private var browser: MediaBrowserCompat? = null
    private var controller: MediaControllerCompat? = null
    private val backStack = ArrayDeque<Pair<String, String>>()

    init {
        connect()
        viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                refreshPlayback()
            }
        }
    }

    fun connect() {
        if (browser != null) return
        val app = getApplication<Application>()
        val component = ComponentName(app, AutoCockpitMediaService::class.java)
        val client = MediaBrowserCompat(
            app,
            component,
            object : MediaBrowserCompat.ConnectionCallback() {
                override fun onConnected() {
                    val connectedBrowser = browser ?: return
                    runCatching {
                        MediaControllerCompat(app, connectedBrowser.sessionToken).also { mediaController ->
                            controller = mediaController
                            mediaController.registerCallback(
                                object : MediaControllerCompat.Callback() {
                                    override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
                                        refreshPlayback()
                                    }

                                    override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
                                        refreshPlayback()
                                    }
                                },
                            )
                        }
                    }.onFailure { error ->
                        _state.update { it.copy(error = "Controller unavailable: ${error.message}") }
                    }
                    _state.update { it.copy(connected = true, error = null) }
                    load(MediaBrowseTree.ROOT_ID, "Library", reset = true)
                }

                override fun onConnectionFailed() {
                    _state.update { it.copy(connected = false, error = "Media service unavailable") }
                }

                override fun onConnectionSuspended() {
                    _state.update { it.copy(connected = false) }
                }
            },
            null,
        )
        browser = client
        client.connect()
    }

    fun browse(nodeId: String, title: String) {
        backStack.addLast(_state.value.levelId to _state.value.title)
        load(nodeId, title, reset = false)
    }

    fun back(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        load(previous.first, previous.second, reset = false)
        return true
    }

    fun play(mediaId: String) {
        controller?.transportControls?.playFromMediaId(mediaId, null)
        refreshPlayback()
    }

    fun togglePlay() {
        val controls = controller?.transportControls ?: return
        if (_state.value.isPlaying) controls.pause() else controls.play()
        refreshPlayback()
    }

    fun skipNext() {
        controller?.transportControls?.skipToNext()
        refreshPlayback()
    }

    fun skipPrevious() {
        controller?.transportControls?.skipToPrevious()
        refreshPlayback()
    }

    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs)
        refreshPlayback()
    }

    fun refresh() {
        load(_state.value.levelId, _state.value.title, reset = false)
        refreshPlayback()
    }

    private fun load(parentId: String, title: String, reset: Boolean) {
        val client = browser ?: return
        if (reset) backStack.clear()
        _state.update { it.copy(levelId = parentId, title = title, nodes = emptyList()) }
        runCatching { client.unsubscribe(parentId) }
        client.subscribe(
            parentId,
            object : MediaBrowserCompat.SubscriptionCallback() {
                override fun onChildrenLoaded(
                    parent: String,
                    children: MutableList<MediaBrowserCompat.MediaItem>,
                ) {
                    val nodes = children.map { item ->
                        MediaNode(
                            id = item.mediaId ?: "",
                            title = item.description.title?.toString().orEmpty(),
                            subtitle = item.description.subtitle?.toString(),
                            playable = item.isPlayable,
                        )
                    }
                    _state.update { it.copy(nodes = nodes, error = null) }
                }

                override fun onError(parent: String) {
                    _state.update { it.copy(error = "Could not load $parent") }
                }
            },
        )
    }

    private fun refreshPlayback() {
        val currentController = controller ?: return
        val metadata = currentController.metadata
        val playback = currentController.playbackState
        val position = playback?.position ?: 0L
        val state = playback?.state ?: PlaybackStateCompat.STATE_NONE
        _state.update {
            it.copy(
                nowPlayingTitle = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE),
                nowPlayingArtist = metadata?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST),
                isPlaying = state == PlaybackStateCompat.STATE_PLAYING,
                positionMs = position.coerceAtLeast(0L),
                durationMs = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L,
            )
        }
    }

    override fun onCleared() {
        runCatching { browser?.unsubscribe(_state.value.levelId) }
        browser?.disconnect()
        browser = null
        controller = null
        super.onCleared()
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
