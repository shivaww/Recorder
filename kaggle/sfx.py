"""sfx.py: ambience beds and audio mixing.

Mixes the SFX clips declared in the HTML's #sfx JSON manifest (each url was
downloaded and converted to a local WAV by media.py) with the synthesized
#ambience bed, and writes one WAV for ffmpeg muxing.
"""
import os
import wave
import numpy as np

from config import SAMPLE_RATE, MASTER_GAIN


# --- SFX CLIPS (downloaded WAVs) ---------------------------------------------

def load_clip(rel_path, base_dir):
    """Load a local 16-bit mono WAV at SAMPLE_RATE as float32 in [-1, 1].

    Returns None if the path escapes base_dir or the file is missing/invalid.
    """
    root = os.path.realpath(base_dir)
    path = os.path.realpath(os.path.join(root, rel_path))
    if not path.startswith(root + os.sep) or not os.path.isfile(path):
        return None
    try:
        with wave.open(path, 'rb') as wf:
            if (wf.getnchannels() != 1 or wf.getsampwidth() != 2
                    or wf.getframerate() != SAMPLE_RATE):
                return None
            raw = wf.readframes(wf.getnframes())
    except (wave.Error, EOFError):
        return None
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0


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

def mix_audio(events, ambience_type, duration_sec, out_path, base_dir):
    """Mix SFX clips + ambience bed into a WAV file.

    Args:
        events: list of {"t": seconds, "url": "assets/<key>.wav", "gain": 0-1}
        ambience_type: "drone"|"pulse"|"air"|"tension" or None
        duration_sec: total timeline duration
        out_path: where to write the WAV
        base_dir: job directory that the event urls are relative to
    """
    total = int(duration_sec * SAMPLE_RATE) + SAMPLE_RATE // 4
    acc = np.zeros(total, dtype=np.float32)

    if ambience_type:
        bed = get_ambience(ambience_type, int(duration_sec * SAMPLE_RATE))
        acc[:len(bed)] += bed * 0.25

    clips = {}
    for e in events:
        url = e.get("url")
        if not url:
            print("[sfx] skipped event without url: %r" % (e,), flush=True)
            continue
        if url not in clips:
            clips[url] = load_clip(url, base_dir)
        buf = clips[url]
        if buf is None:
            print("[sfx] skipped unusable clip: %s" % url[:80], flush=True)
            continue
        t_sec = e.get("t")
        if not isinstance(t_sec, (int, float)):
            continue
        start = int(t_sec * SAMPLE_RATE)
        if start < 0 or start >= total:
            continue
        end = min(start + len(buf), total)
        acc[start:end] += buf[:end - start] * e.get("gain", 0.7)

    acc = acc / (1.0 + np.abs(acc))
    acc = (acc * 32767.0 * MASTER_GAIN).astype(np.int16)

    with wave.open(out_path, 'w') as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(acc.tobytes())
