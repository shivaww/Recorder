package com.brollrender.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/**
 * Spec section 6 - H.264 surface-input encoder + MPEG-4 muxer.
 *
 * Presentation times are SYNTHESIZED per written sample (frameIndex * 1e6 / fps)
 * instead of trusting the encoder's arrival-time stamps: guarantees strictly
 * increasing, frame-accurate timestamps no matter when the codec consumed each
 * canvas frame. CODEC_CONFIG buffers are skipped (size 0); the muxer track is
 * added on INFO_OUTPUT_FORMAT_CHANGED; codec is released before muxer
 * (pitfall 7.9). Only the render thread touches codec/surface/drains.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    bitRate: Int,
    outputFile: File
) {
    private lateinit var codec: MediaCodec
    private lateinit var muxer: MediaMuxer
    private val outPath = outputFile.absolutePath

    var inputSurface: Surface? = null
        private set
    var writtenFrames: Long = 0
        private set

    private var trackIndex = -1
    private var muxerStarted = false
    private var wroteAnySample = false
    private var codecStarted = false

    fun start() {
        val fmt = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
        }
        // Surface-INPUT encoding: pass null as the output surface, then create
        // the input surface after configure (the only pattern the API allows).
        codec = MediaCodec.createEncoderByType("video/avc")
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        codecStarted = true
        muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /**
     * Drain available output. With [endOfStream] = false this returns as soon
     * as the codec has nothing ready (per-frame call). With true it keeps
     * draining (bounded: 500 x 10 ms idle) until BUFFER_FLAG_END_OF_STREAM.
     */
    fun drain(endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idlePolls = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 10_000L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idlePolls > 500) {
                        throw RuntimeException("encoder EOS timeout - no output for 5 s")
                    }
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "encoder changed output format twice" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0 // format data: already applied via addTrack
                    }
                    if (info.size != 0 && muxerStarted) {
                        val buf = codec.getOutputBuffer(idx)
                            ?: throw RuntimeException("encoder output buffer vanished")
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sample = MediaCodec.BufferInfo()
                        sample.set(0, info.size, writtenFrames * 1_000_000L / fps, info.flags)
                        muxer.writeSampleData(trackIndex, buf, sample)
                        writtenFrames++
                        wroteAnySample = true
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun signalEos() {
        codec.signalEndOfInputStream()
    }

    /**
     * Stop + release everything; codec before muxer (pitfall 7.9). Idempotent -
     * safe on both the success path and Cancel's mid-render abort. Muxer stop()
     * is only attempted when a track exists and samples were written, because
     * MediaMuxer.stop() throws on an empty-but-started muxer.
     */
    fun release() {
        try {
            if (codecStarted) codec.stop()
        } catch (_: Exception) {
        }
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        try {
            if (codecStarted) codec.release()
        } catch (_: Exception) {
        }
        try {
            if (muxerStarted && wroteAnySample) muxer.stop()
        } catch (_: Exception) {
        }
        try {
            muxer.release()
        } catch (_: Exception) {
        }
        inputSurface = null
        codecStarted = false
    }
}
