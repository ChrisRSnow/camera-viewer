package com.cameraviewer.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process hand-off from CameraDetectionService's single upstream MJPEG
 * connection to VideoRelayServerService, which re-serves those same frames
 * to any number of remote viewers. This is the whole point of the relay:
 * the third-party Android IP Camera app's encoder degrades under a second
 * simultaneous connection (see ARCHITECTURE.md §1), and a remote viewer
 * connecting directly to it — as CameraMonitorService used to do — is
 * exactly that second connection, just made over Tailscale instead of
 * locally. Routing viewers through this bus instead means the camera app
 * only ever sees the one connection CameraDetectionService already holds,
 * no matter how many phones are watching.
 *
 * replay = 1 so a viewer that connects mid-stream gets the last frame
 * immediately instead of waiting for the next one; extraBufferCapacity = 1
 * with DROP_OLDEST means a slow relay client never backpressures the
 * detection loop itself — video relay is best-effort, detection is not.
 */
object LiveFrameBus {
    private val _frames = MutableSharedFlow<ByteArray>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    fun publish(frame: ByteArray) {
        _frames.tryEmit(frame)
    }
}
