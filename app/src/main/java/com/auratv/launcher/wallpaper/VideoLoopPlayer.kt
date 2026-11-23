package com.auratv.launcher.wallpaper

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.auratv.launcher.util.Bg

/**
 * Minimal video-only looping player.
 *
 * Why not MediaPlayer/VideoView: they always initialise the audio pipeline (even when muted)
 * and loop with a visible hitch. Here only the video track is selected, decoded by the
 * platform's hardware codec straight onto the Surface (zero copies), and looping is seamless:
 * at end of stream the extractor seeks back to 0 and timestamps continue monotonically, so
 * the decoder never needs flushing.
 *
 * A drop-rate monitor reports when this device can't keep up so the UI can suggest Cinematic mode.
 */
class VideoLoopPlayer(
    private val path: String,
    private val surface: Surface,
    private val listener: Listener,
) {
    interface Listener {
        fun onVideoSize(width: Int, height: Int)
        fun onStruggling(dropPercent: Int)
        fun onError(message: String)
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "aura-video").apply { start() }
    }

    fun release() {
        running = false
        thread?.let {
            it.interrupt()
            try { it.join(500) } catch (_: InterruptedException) {}
        }
        thread = null
    }

    private fun runLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i; format = f; break
                }
            }
            if (track < 0 || format == null) {
                post { listener.onError("No video track found") }
                return
            }
            extractor.selectTrack(track) // only video: the audio track is never read or decoded
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            post { listener.onVideoSize(w, h) }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, surface, null, 0)
            codec.start()
            decode(extractor, codec)
        } catch (e: InterruptedException) {
            // released
        } catch (e: Exception) {
            Log.w(TAG, "video wallpaper failed", e)
            if (running) post { listener.onError(e.message ?: e.javaClass.simpleName) }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun decode(extractor: MediaExtractor, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var ptsOffset = 0L          // added to timestamps after each loop
        var lastPts = 0L
        var frameDur = 33_333L
        var startNs = -1L           // wall clock corresponding to pts 0
        var inputEos = false
        var rendered = 0
        var dropped = 0
        var windowStart = System.nanoTime()
        var warned = false

        while (running) {
            if (!inputEos) {
                val inIdx = codec.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    var size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        // Loop: rewind and keep timestamps increasing.
                        ptsOffset = lastPts + frameDur
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                            continue
                        }
                    }
                    val pts = extractor.sampleTime + ptsOffset
                    if (pts > lastPts) {
                        val d = pts - lastPts
                        if (d in 5_000..200_000) frameDur = d
                        lastPts = pts
                    }
                    codec.queueInputBuffer(inIdx, 0, size, pts, 0)
                    extractor.advance()
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, 5_000)
            if (outIdx >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    codec.releaseOutputBuffer(outIdx, false)
                    return
                }
                val now = System.nanoTime()
                if (startNs < 0) startNs = now - info.presentationTimeUs * 1000
                val due = startNs + info.presentationTimeUs * 1000
                val late = now - due
                if (late > 50_000_000L) {
                    // More than ~1.5 frames late: drop instead of rendering stale frames.
                    codec.releaseOutputBuffer(outIdx, false)
                    dropped++
                    if (late > 1_000_000_000L) startNs = now - info.presentationTimeUs * 1000 // resync after a stall
                } else {
                    val early = due - now
                    if (early > 15_000_000L) Thread.sleep((early - 10_000_000L) / 1_000_000L)
                    codec.releaseOutputBuffer(outIdx, due) // vsync-aligned presentation
                    rendered++
                }
                if (now - windowStart > 10_000_000_000L) {
                    val total = rendered + dropped
                    val pct = if (total > 0) dropped * 100 / total else 0
                    Log.i(TAG, "video: rendered=$rendered dropped=$dropped ($pct%) over 10s")
                    if (pct >= 15 && !warned) {
                        warned = true
                        post { listener.onStruggling(pct) }
                    }
                    rendered = 0; dropped = 0; windowStart = now
                }
            }
        }
    }

    private fun post(r: () -> Unit) {
        Bg.main.post { if (running) r() }
    }

    companion object {
        private const val TAG = "AuraVideo"
    }
}
