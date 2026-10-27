"""sfx.py - URL-based SFX loading, ambience beds, and audio mixing.

SFX events come from the HTML's #sfx JSON manifest as audio URLs. assets.py
downloads each URL into <job>/assets/ and rewrites it to that relative path;
this module decodes those files with ffmpeg and mixes them, plus the
synthesized ambience bed from #ambience, into a WAV for ffmpeg muxing.
"""
import os
import subprocess
import wave
import numpy as np

from config import SAMPLE_RATE, MASTER_GAIN


# --- SFX LOADING -------------------------------------------------------------

TRIM_FADE_SEC = 0.08   # fade-out so a dur cut does not click
MAX_SFX_SEC = 10.0   # per-event cap so a stray full song cannot flood the mix


def load_sfx(path):
    """Decode any ffmpeg-readable audio file to mono float32 at SAMPLE_RATE."""
    cmd = ["ffmpeg", "-nostdin", "-v", "error", "-i", path, "-t", str(MAX_SFX_SEC),
           "-vn", "-f", "f32le", "-ac", "1", "-ar", str(SAMPLE_RATE), "-"]
    r = subprocess.run(cmd, capture_output=True, stdin=subprocess.DEVNULL, timeout=60)
    if r.returncode != 0 or not r.stdout:
        msg = r.stderr.decode("utf-8", "replace").strip()[-200:]
        raise ValueError(msg or "empty audio")
    return np.frombuffer(r.stdout, dtype=np.float32)


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


# --- AUDIO MIX ---------------------------------------------------------------

def mix_audio(events, ambience_type, duration_sec, out_path, base_dir):
    """Mix URL-based SFX events + ambience bed into a WAV file.

    Args:
        events: list of {"t": seconds, "url": "assets/<file>", "gain": 0-1,
            optional "dur": seconds to play, 0 or omitted = whole file};
            assets.py already downloaded each url into base_dir/assets/
        ambience_type: "drone"|"pulse"|"air"|"tension" or None
        duration_sec: total timeline duration
        out_path: where to write the WAV
        base_dir: directory that the event urls are relative to

    Returns True if the WAV was written, False if there was nothing to mix
    (no ambience and no event loaded), in which case the video stays silent.
    """
    total = int(duration_sec * SAMPLE_RATE) + SAMPLE_RATE // 4
    acc = np.zeros(total, dtype=np.float32)
    assets_dir = os.path.realpath(os.path.join(base_dir, "assets"))
    used = False

    if ambience_type:
        bed = get_ambience(ambience_type, int(duration_sec * SAMPLE_RATE))
        acc[:len(bed)] += bed * 0.25
        used = True

    cache = {}
    placed = []   # (path, start, end) in samples of each sound already mixed
    for e in events:
        url = str(e.get("url") or "")
        path = os.path.realpath(os.path.join(base_dir, url))
        # Only decode files assets.py downloaded; never a raw remote url or other path.
        if os.path.dirname(path) != assets_dir or not os.path.isfile(path):
            print("[sfx] skipped (not a downloaded asset): %s" % url[:80], flush=True)
            continue
        if path not in cache:
            try:
                cache[path] = load_sfx(path)
            except Exception as ex:
                cache[path] = None
                print("[sfx] decode failed %s: %s" % (url[:80], ex), flush=True)
        buf = cache[path]
        if buf is None:
            continue
        file_sec = len(buf) / SAMPLE_RATE
        dur = float(e.get("dur") or 0)
        if dur > file_sec + 0.02:
            # asked for more than the file has: play it once as-is, never loop or stretch
            print("[sfx] dur %.2fs asked but file is only %.2fs, playing it once: %s" % (dur, file_sec, url[:60]), flush=True)
        elif dur > 0:
            buf = buf[:int(dur * SAMPLE_RATE)].copy()
            fade = min(int(TRIM_FADE_SEC * SAMPLE_RATE), len(buf))
            if fade > 0:
                buf[-fade:] *= np.linspace(1.0, 0.0, fade, dtype=np.float32)
        start = int(float(e.get("t") or 0) * SAMPLE_RATE)
        if start < 0 or start >= total:
            continue
        end = min(start + len(buf), total)
        if any(p[0] == path and abs(p[1] - start) < int(0.3 * SAMPLE_RATE) for p in placed):
            print("[sfx] skipped repeat of the same sound within 0.3s at t=%.2f: %s" % (start / SAMPLE_RATE, url[:60]), flush=True)
            continue
        if any(start < p[2] and end > p[1] for p in placed):
            print("[sfx] warning: overlaps an earlier sound at t=%.2f" % (start / SAMPLE_RATE), flush=True)
        placed.append((path, start, end))
        acc[start:end] += buf[:end - start] * float(e.get("gain", 0.7))
        used = True

    if not used:
        return False

    acc = acc / (1.0 + np.abs(acc))
    acc = (acc * 32767.0 * MASTER_GAIN).astype(np.int16)

    with wave.open(out_path, 'w') as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(acc.tobytes())
    return True
