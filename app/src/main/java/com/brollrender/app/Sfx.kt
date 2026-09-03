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

    // Master output gain - one constant, tuned from on-device feedback.
    // 0.45 ~= -7 dBFS: comfortable phone playback with clipping headroom.
    private const val MASTER_GAIN = 0.45f

    /** Page-declared loudness preference -> gain multiplier. */
    fun loudnessMultiplier(loudness: String?): Float = when (loudness) {
        "low", "quiet" -> 0.5f
        "high", "loud" -> 1.6f
        else -> 1.0f
    }
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

    // ===================== ambience beds =====================
    // Continuous background audio synthesized for the full duration.
    // Fades in over 1s, out over 2s. Mixed under SFX at declared gain.

    /** Deep sustained pad with slow LFO - documentary, storytelling. */
    private fun ambDrone(durationSec: Double): FloatArray {
        val n = (durationSec * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        var ph1 = 0.0; var ph2 = 0.0; var phLfo = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val lfo = 0.6 + 0.4 * sin(TAU * 0.07 * t + phLfo) // very slow modulation
            phLfo += TAU * 0.07 / SAMPLE_RATE
            ph1 += TAU * 55.0 / SAMPLE_RATE
            ph2 += TAU * 82.5 / SAMPLE_RATE
            out[i] = ((sin(ph1) * 0.5 + sin(ph2) * 0.3 +
                sin(ph1 * 2.01) * 0.15) * lfo * 0.4).toFloat()
        }
        return out
    }

    /** Soft rhythmic low throb ~70 BPM - podcast, listicle. */
    private fun ambPulse(durationSec: Double): FloatArray {
        val n = (durationSec * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val beatSec = 60.0 / 70.0 // ~0.857s per beat
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val beatPhase = (t % beatSec) / beatSec
            val env = exp(-beatPhase * 6.0) // sharp attack, soft decay
            ph += TAU * 50.0 / SAMPLE_RATE
            out[i] = (sin(ph) * env * 0.5).toFloat()
        }
        return out
    }

    /** Filtered noise, airy and spacious - explainer, study. */
    private fun ambAir(durationSec: Double): FloatArray {
        val n = (durationSec * SAMPLE_RATE).toInt()
        val nz = noiseFor(99, n)
        val out = FloatArray(n)
        var lp = 0f; var lp2 = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val mod = 0.7 + 0.3 * sin(TAU * 0.12 * t).toFloat() // slow breathing
            lp += (nz[i] - lp) * 0.02f // heavy lowpass
            lp2 += (lp - lp2) * 0.04f // second stage
            out[i] = lp2 * mod * 0.6f
        }
        return out
    }

    /** Rising pad with slow build - news, thriller. */
    private fun ambTension(durationSec: Double): FloatArray {
        val n = (durationSec * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        var ph1 = 0.0; var ph2 = 0.0
        for (i in 0 until n) {
            val p = i.toDouble() / n // 0..1 progress
            val f1 = 80.0 + 120.0 * p // rises 80->200 Hz
            val f2 = f1 * 1.5 // fifth above
            ph1 += TAU * f1 / SAMPLE_RATE
            ph2 += TAU * f2 / SAMPLE_RATE
            val amp = 0.2 + 0.3 * p // builds
            out[i] = ((sin(ph1) * 0.5 + sin(ph2) * 0.3) * amp).toFloat()
        }
        return out
    }

    private fun ambienceBed(type: String, durationSec: Double): FloatArray {
        val raw = when (type) {
            "drone" -> ambDrone(durationSec)
            "pulse" -> ambPulse(durationSec)
            "air" -> ambAir(durationSec)
            "tension" -> ambTension(durationSec)
            else -> ambDrone(durationSec)
        }
        // Fade in 1s, fade out 2s
        val fadeIn = min(SAMPLE_RATE, raw.size)
        val fadeOut = min(2 * SAMPLE_RATE, raw.size)
        for (i in 0 until fadeIn) {
            raw[i] *= (i.toFloat() / fadeIn)
        }
        for (i in 0 until fadeOut) {
            val idx = raw.size - 1 - i
            raw[idx] *= (i.toFloat() / fadeOut)
        }
        return raw
    }

    /**
     * Mix all events + optional ambience bed onto one PCM timeline of
     * [durationSec] seconds (plus a 0.25 s tail so the last decay is not
     * cut). Soft-clip (x/(1+|x|)) keeps overlapping events from
     * hard-distorting.
     */
    fun mix(
        events: List<Event>,
        durationSec: Double,
        loudness: String? = null,
        ambienceType: String? = null,
        ambienceGain: Float = 0.25f
    ): ShortArray {
        val total = (durationSec * SAMPLE_RATE).toInt() + SAMPLE_RATE / 4
        val acc = FloatArray(total)

        // Ambience bed first (under everything)
        if (ambienceType != null) {
            val bed = ambienceBed(ambienceType, durationSec)
            val len = min(bed.size, total)
            for (i in 0 until len) acc[i] += bed[i] * ambienceGain
        }

        // SFX events on top
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
            out[i] = (x * 32767.0 * MASTER_GAIN * loudnessMultiplier(loudness)).toInt().toShort()
        }
        return out
    }
}
