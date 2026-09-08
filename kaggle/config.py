"""config.py — Shared constants for all BrollRender Kaggle modules."""
import os
import subprocess

# ─── SERVER ───────────────────────────────────────────────────────────────────
PORT = 8000
WORK_DIR = "/tmp/broll_jobs"
os.makedirs(WORK_DIR, exist_ok=True)

# ─── AUDIO ────────────────────────────────────────────────────────────────────
SAMPLE_RATE = 44100
MASTER_GAIN = 0.45

# ─── GPU ──────────────────────────────────────────────────────────────────────

def detect_gpus():
    try:
        r = subprocess.run(
            ["nvidia-smi", "--query-gpu=index", "--format=csv,noheader"],
            capture_output=True, text=True, timeout=10
        )
        if r.returncode == 0:
            return len(r.stdout.strip().split("\n"))
    except Exception:
        pass
    return 1

NUM_GPUS = detect_gpus()

# ─── PIXEL-PERFECT CHROMIUM FLAGS ─────────────────────────────────────────────
# Eliminates sub-pixel variance, font hinting diffs, GPU compositing artifacts
CHROMIUM_FLAGS = [
    "--no-sandbox",
    "--disable-dev-shm-usage",
    "--headless=new",
    "--use-angle=vulkan",
    "--enable-features=Vulkan",
    "--disable-vulkan-surface",
    "--enable-unsafe-webgpu",
    "--ignore-gpu-blocklist",
    "--enable-gpu-rasterization",
    "--enable-zero-copy",
    "--disable-gpu-sandbox",
    "--hide-scrollbars",
    "--force-device-scale-factor=1",
    "--force-color-profile=srgb",
    "--font-render-hinting=none",
    "--disable-font-subpixel-positioning",
    "--disable-lcd-text",
    "--disable-skia-runtime-opts",
    "--disable-field-trial-config",
    "--disable-variations",
]

# Safe CPU-only set if EGL/GPU launch fails
CPU_FALLBACK_FLAGS = [
    "--no-sandbox",
    "--disable-dev-shm-usage",
    "--headless=new",
    "--disable-gpu",
    "--hide-scrollbars",
    "--force-device-scale-factor=1",
    "--force-color-profile=srgb",
    "--font-render-hinting=none",
    "--disable-font-subpixel-positioning",
    "--disable-lcd-text",
    "--disable-skia-runtime-opts",
    "--disable-field-trial-config",
    "--disable-variations",
]

# ─── JOB STATES ───────────────────────────────────────────────────────────────
# UPLOADED -> VALIDATING -> VALID / INVALID
# VALID -> WAITING_START (app sends start signal)
# WAITING_START -> RENDERING -> ENCODING -> DONE
# Any state -> FAILED / CANCELLED
STATE_UPLOADED = "UPLOADED"
STATE_VALIDATING = "VALIDATING"
STATE_VALID = "VALID"
STATE_INVALID = "INVALID"
STATE_WAITING_START = "WAITING_START"
STATE_RENDERING = "RENDERING"
STATE_ENCODING = "ENCODING"
STATE_DONE = "DONE"
STATE_FAILED = "FAILED"
STATE_CANCELLED = "CANCELLED"
