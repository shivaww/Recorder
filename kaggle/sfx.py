"""sfx.py — SFX synthesis, ambience beds, and audio mixing.

Synthesizes declarative audio events from the HTML's #sfx JSON manifest
and ambience beds from #ambience, mixes them into a WAV for ffmpeg muxing.
"""
import wave
import numpy as np

from config import SAMPLE_RATE, MASTER_GAIN


# ─── SFX SYNTHESIS ────────────────────────────────────────────────────────────

def synth_slam(n):
    t = np.arange(n) / SAMPLE_RATE
    nz = np.random.RandomState(11).rand(n) * 2 - 1
    f = 140.0 * np.exp(-t * 22.0) + 42.0
    ph = np.cumsum(2 * np.pi * f / SAMPLE_RATE)
    return np.sin(ph) * np.exp(-t * 14.0) + nz * np.exp(-t * 90.0) * 0.5

def synth_boom(n):
    t = np.arange(n) / SAMPLE_RATE
    nz = np.random.RandomState(37).rand(n) * 2 - 1
    f = 70.0 * np.exp(-t * 8.0) + 28.0
    ph = np.cumsum(2 * np.pi * f / SAMPLE_RATE)
    return np.sin(ph) * np.exp(-t * 6.0) + nz * np.exp(-t * 20.0) * 0.15

def synth_whoosh(n):
    nz = np.random.RandomState(23).rand(n) * 2 - 1
    p = np.arange(n) / n
    a = (0.04 + 0.46 * p)
    lp = np.zeros(n)
    for i in range(1, n):
        lp[i] = lp[i-1] + a[i] * (nz[i] - lp[i-1])
    return lp * np.sin(np.pi * p) * 0.8

def synth_tick(n):
    t = np.arange(n) / SAMPLE_RATE
    nz = np.random.RandomState(31).rand(n) * 2 - 1
    return nz * np.exp(-t * 400.0) * 0.9

def synth_rise(n):
    p = np.arange(n) / n
    f = 180.0 * (1400.0 / 180.0) ** p
    ph = np.cumsum(2 * np.pi * f / SAMPLE_RATE)
    trem = 0.7 + 0.3 * np.sin(2 * np.pi * 7.0 * p)
    return np.sin(ph) * trem * (0.15 + 0.55 * p)

def synth_ding(n):
    t = np.arange(n) / SAMPLE_RATE
    d = np.exp(-t * 8.0)
    return (np.sin(2*np.pi*1318.0*t)*0.6 + np.sin(2*np.pi*1976.0*t)*0.25) * d

def synth_pop(n):
    p = np.arange(n) / n
    f = 260.0 + 640.0 * p
    ph = np.cumsum(2 * np.pi * f / SAMPLE_RATE)
    return np.sin(ph) * (1.0 - p) * 0.8

def synth_glitch(n):
    t = np.arange(n) / SAMPLE_RATE
    nz = np.random.RandomState(47).rand(n) * 2 - 1
    gate = (np.sin(2 * np.pi * 60.0 * t) > 0).astype(float)
    return nz * gate * np.exp(-t * 12.0) * 0.8

def synth_sound(sid, n):
    dispatch = {
        "slam": synth_slam, "boom": synth_boom, "whoosh": synth_whoosh,
        "rise": synth_rise, "ding": synth_ding, "pop": synth_pop,
        "glitch": synth_glitch,
    }
    return dispatch.get(sid, synth_tick)(n)


# ─── AMBIENCE BEDS ────────────────────────────────────────────────────────────

def amb_drone(n):
    t = np.arange(n) / SAMPLE_RATE
    lfo = 0.6 + 0.4 * np.sin(2 * np.pi * 0.07 * t)
    return (np.sin(2*np.pi*55.0*t)*0.5 + np.sin(2*np.pi*82.5*t)*0.3 + np.sin(2*np.pi*110.55*t)*0.15) * lfo * 0.4

def amb_pulse(n):
    t = np.arange(n) / SAMPLE_RATE
    beat_sec = 60.0 / 70.0
    beat_phase = (t % beat_sec) / beat_sec
    env = np.exp(-beat_phase * 6.0)
    return np.sin(2*np.pi*50.0*t) * env * 0.5

def amb_air(n):
    nz = np.random.RandomState(99).rand(n) * 2 - 1
    t = np.arange(n) / SAMPLE_RATE
    mod = 0.7 + 0.3 * np.sin(2 * np.pi * 0.12 * t)
    lp = np.zeros(n)
    lp2 = np.zeros(n)
    for i in range(1, n):
        lp[i] = lp[i-1] + 0.02 * (nz[i] - lp[i-1])
        lp2[i] = lp2[i-1] + 0.04 * (lp[i] - lp2[i-1])
    return lp2 * mod * 0.6

def amb_tension(n):
    p = np.arange(n) / n
    f1 = 80.0 + 120.0 * p
    f2 = f1 * 1.5
    ph1 = np.cumsum(2 * np.pi * f1 / SAMPLE_RATE)
    ph2 = np.cumsum(2 * np.pi * f2 / SAMPLE_RATE)
    amp = 0.2 + 0.3 * p
    return (np.sin(ph1) * 0.5 + np.sin(ph2) * 0.3) * amp

def get_ambience(typ, n):
    if typ == "drone": raw = amb_drone(n)
    elif typ == "pulse": raw = amb_pulse(n)
    elif typ == "air": raw = amb_air(n)
    elif typ == "tension": raw = amb_tension(n)
    else: raw = amb_drone(n)
    fade_in = min(SAMPLE_RATE, n)
    fade_out = min(2 * SAMPLE_RATE, n)
    raw[:fade_in] *= np.arange(fade_in) / fade_in
    raw[-fade_out:] *= np.arange(fade_out)[::-1] / fade_out
    return raw


# ─── AUDIO MIX ────────────────────────────────────────────────────────────────

def mix_audio(events, ambience_type, duration_sec, out_path):
    """Synthesize SFX events + ambience bed into a WAV file.

    Args:
        events: list of {"t": seconds, "id": sound_id, "gain": 0-1}
        ambience_type: "drone"|"pulse"|"air"|"tension" or None
        duration_sec: total timeline duration
        out_path: where to write the WAV
    """
    total = int(duration_sec * SAMPLE_RATE) + SAMPLE_RATE // 4
    acc = np.zeros(total, dtype=np.float32)

    if ambience_type:
        bed = get_ambience(ambience_type, int(duration_sec * SAMPLE_RATE))
        acc[:len(bed)] += bed * 0.25

    for e in events:
        t_sec = e.get("t", 0)
        eid = e.get("id", "tick")
        gain = e.get("gain", 0.7)
        n = int(0.8 * SAMPLE_RATE)
        buf = synth_sound(eid, n)
        start = int(t_sec * SAMPLE_RATE)
        if start < 0 or start >= total:
            continue
        end = min(start + len(buf), total)
        acc[start:end] += buf[:end-start] * gain

    acc = acc / (1.0 + np.abs(acc))
    acc = (acc * 32767.0 * MASTER_GAIN).astype(np.int16)

    with wave.open(out_path, 'w') as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(acc.tobytes())
