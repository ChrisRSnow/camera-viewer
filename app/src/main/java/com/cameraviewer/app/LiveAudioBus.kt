package com.cameraviewer.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Same hand-off pattern as LiveFrameBus, for audio: AudioCaptureService's
 * one connection to the camera app's /audio endpoint publishes raw WAV
 * bytes here as they arrive, and AudioRelayServerService fans them out to
 * any number of connected viewers — so a remote viewer never opens its
 * own second connection to the camera app for audio, same reasoning as
 * video (see LiveFrameBus's doc comment).
 *
 * No replay here (unlike LiveFrameBus's replay=1): a new listener joining
 * mid-stream doesn't need the last audio chunk the way a new video viewer
 * benefits from an instant first frame — it should just start hearing
 * from whenever it connects, not replay a stale chunk of audio.
 */
object LiveAudioBus {
    private val _chunks = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val chunks: SharedFlow<ByteArray> = _chunks.asSharedFlow()

    fun publish(chunk: ByteArray) {
        _chunks.tryEmit(chunk)
    }
}
