package com.brollrender.app

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Deterministic SFX synthesis - pure Kotlin, zero assets, zero network.
 * The page declares sound EVENTS as data (JsContracts.SFX_MANIFEST_JS); the
 * engine synthesizes each event into PCM and mixes them onto one timeline
 * aligned with the video's master frame clock - audio and video are
 * sample-accurate by construction. Mono 44.1 kHz; the mixer soft-clips so
 * overlapping events never hard-distort; noise is seeded per id, so renders
 * are bit-stable across runs.
 */
object Sfx {

    const val SAMPLE_RATE = 44100
    private const val TAU = Math.PI * 2.0

    /** One declared sound event: start time (s), vocabulary id, gain 0..1. */
    data class Event(val tSec: Double, val id: String, val gain: Float)

    private fun samples(sec: Double): Int = (sec * SAMPLE_RATE).toInt().coerceAtLeast(1)

    /** Deterministic noise - same seed, same waveform, every render. */
    private fun noiseFor(seed: Int, len: Int): FloatArray {
        val rnd = Random(seed.toLong() * 2654435761L)
        val out = FloatArray(len)
        for (i in 0 until len) out[i] = (rnd.nextDouble() * 2.0 - 1.0).toFloat()
        return out
    }

    // ===================== vocabulary =====================

    /** Stamp / kinetic word landing: pitch-dropping thump + transient. */
    private fun slam(): FloatArray {
        val n = samples(0.3)
        val out = FloatArray(n)
        val nz = noiseFor(11, n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val f = 140.0 * exp(-t * 22.0) + 42.0
            ph += TAU * f / SAMPLE_RATE
            out[i] = (sin(ph) * exp(-t * 14.0)).toFloat() +
                (nz[i] * exp(-t * 90.0) * 0.5).toFloat()
        }
        return out
    }

    /** Deep impact: sub-heavy sweep with a long tail. */
    private fun boom(): FloatArray {
        val n = samples(0.7)
        val out = FloatArray(n)
        val nz = noiseFor(37, n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val f = 70.0 * exp(-t * 8.0) + 28.0
            ph += TAU * f / SAMPLE_RATE
            out[i] = (sin(ph) * exp(-t * 6.0)).toFloat() +
                (nz[i] * exp(-t * 20.0) * 0.15).toFloat()
        }
        return out
    }

    /** Enter/exit sweep: filtered noise, swelling then falling. */
    private fun whoosh(): FloatArray {
        val n = samples(0.45)
        val nz = noiseFor(23, n)
        val out = FloatArray(n)
        var lp = 0f
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val a = (0.04 + 0.46 * p).toFloat() // filter opens across the sweep
            lp += (nz[i] - lp) * a
            val amp = (sin(Math.PI * p) * 0.8).toFloat()
            out[i] = lp * amp
        }
        return out
    }

    /** Small UI/HUD click. */
    private fun tick(): FloatArray {
        val n = samples(0.02)
        val nz = noiseFor(31, n)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            out[i] = (nz[i] * exp(-t * 400.0) * 0.9).toFloat()
        }
        return out
    }

    /** Build-up INTO a beat: rising exponential sweep + tremolo. */
    private fun rise(): FloatArray {
        val n = samples(0.8)
        val out = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val f = 180.0 * Math.pow(1400.0 / 180.0, p)
            ph += TAU * f / SAMPLE_RATE
            val trem = 0.7 + 0.3 * sin(TAU * 7.0 * p)
            val amp = 0.15 + 0.55 * p
            out[i] = (sin(ph) * trem * amp).toFloat()
        }
        return out
    }

    /** Reveal ding: two decaying partials. */
    private fun ding(): FloatArray {
        val n = samples(0.45)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val d = exp(-t * 8.0)
            out[i] = ((sin(TAU * 1318.0 * t) * 0.6 +
                sin(TAU * 1976.0 * t) * 0.25) * d).toFloat()
        }
        return out
    }

    /** Small object landing: short upward blip. */
    private fun pop(): FloatArray {
        val n = samples(0.07)
        val out = FloatArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val f = 260.0 + 640.0 * p
            ph += TAU * f / SAMPLE_RATE
            out[i] = (sin(ph) * (1.0 - p) * 0.8).toFloat()
        }
        return out
    }

    /** Error/reject: gated noise ticks. */
    private fun glitch(): FloatArray {
        val n = samples(0.18)
        val nz = noiseFor(47, n)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val gate = if (sin(TAU * 60.0 * t) > 0) 1.0 else 0.0
            out[i] = (nz[i] * gate * exp(-t * 12.0) * 0.8).toFloat()
        }
        return out
    }

    /** Unknown id -> safe audible fallback. */
    fun synth(id: String): FloatArray = when (id) {
        "slam" -> slam()
        "boom" -> boom()
        "whoosh" -> whoosh()
        "rise" -> rise()
        "ding" -> ding()
        "pop" -> pop()
        "glitch" -> glitch()
        else -> tick()
    }

    /**
     * Mix all events onto one PCM timeline of [durationSec] seconds (plus a
     * 0.25 s tail so the last decay is not cut). Soft-clip (x/(1+|x|)) keeps
     * overlapping events from hard-distorting.
     */
    fun mix(events: List<Event>, durationSec: Double): ShortArray {
        val total = (durationSec * SAMPLE_RATE).toInt() + SAMPLE_RATE / 4
        val acc = FloatArray(total)
        for (e in events) {
            val buf = synth(e.id)
            val start = (e.tSec * SAMPLE_RATE).toInt()
            if (start < 0 || start >= total) continue
            val len = min(buf.size, total - start)
            for (i in 0 until len) acc[start + i] += buf[i] * e.gain
        }
        val out = ShortArray(total)
        for (i in 0 until total) {
            val x = acc[i] / (1.0 + abs(acc[i]))
            out[i] = (x * 32767.0 * 0.95).toInt().toShort()
        }
        return out
    }
}
