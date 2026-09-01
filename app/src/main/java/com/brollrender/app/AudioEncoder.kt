package com.brollrender.app

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaFormat

/**
 * PCM16 -> AAC (audio/mp4a-latm) encoder for the SFX track. Encodes the
 * whole timeline synchronously and returns raw (non-ADTS) samples plus the
 * codec's output MediaFormat for muxer.addTrack. PTS derive from the input
 * sample index - the same master clock the video frames use - clamped
 * non-negative (AAC priming can emit small negative stamps, which
 * MediaMuxer rejects).
 */
class AudioEncoder(
    private val sampleRate: Int = 44100,
    private val channels: Int = 1,
    private val bitRate: Int = 96_000
) {

    class Sample internal constructor(
        val data: ByteArray,
        val ptsUs: Long
    )

    class Encoded internal constructor(
        val format: MediaFormat,
        val samples: List<Sample>
    )

    fun encode(pcm: ShortArray): Encoded {
        val fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val samples = mutableListOf<Sample>()
            var outFormat: MediaFormat? = null
            val info = BufferInfo()
            var inPos = 0
            var inDone = false
            var idle = 0
            val chunk = 8192 // samples per input buffer
            while (true) {
                if (!inDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)
                            ?: throw RuntimeException("audio input buffer vanished")
                        ib.clear()
                        val n = minOf(chunk, pcm.size - inPos)
                        if (n > 0) {
                            for (i in 0 until n) ib.putShort(pcm[inPos + i])
                            val pts = inPos.toLong() * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inIdx, 0, n * 2, pts, 0)
                            inPos += n
                        } else {
                            codec.queueInputBuffer(
                                inIdx, 0, 0,
                                inPos.toLong() * 1_000_000L / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inDone = true
                        }
                        idle = 0
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inDone && ++idle > 500) {
                            throw RuntimeException("audio encoder EOS timeout")
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outFormat = codec.outputFormat
                    }
                    outIdx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            info.size > 0
                        ) {
                            val ob = codec.getOutputBuffer(outIdx)
                                ?: throw RuntimeException("audio output buffer vanished")
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            ob.get(bytes)
                            val pts = if (info.presentationTimeUs > 0) info.presentationTimeUs else 0L
                            samples.add(Sample(bytes, pts))
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        idle = 0
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            return Encoded(outFormat!!, samples)
                        }
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            try {
                codec.release()
            } catch (_: Exception) {
            }
        }
    }
}
