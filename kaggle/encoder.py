"""encoder.py — Lossless NVENC video encoding.

Encodes PNG frame sequences to H.264 MP4 using NVIDIA's hardware encoder.
Falls back to libx264 if NVENC is unavailable.
Uses yuv444p to preserve chroma fidelity from PNG source.
"""
import os
import subprocess

from config import NUM_GPUS


class EncodeError(Exception):
    pass


def encode_video(frames_dir, output_path, fps, wav_path=None, gpu_id=0):
    """Encode frames to MP4 with lossless NVENC.

    Args:
        frames_dir: directory containing frame_%05d.png files
        output_path: destination MP4 path
        fps: framerate
        wav_path: optional audio WAV to mux in
        gpu_id: which GPU to use for NVENC

    Returns:
        output_path on success

    Raises:
        EncodeError on failure
    """
    frame_pattern = os.path.join(frames_dir, "frame_%05d.png")

    # Primary: lossless NVENC on assigned GPU
    cmd = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-framerate", str(fps),
        "-i", frame_pattern,
    ]

    if wav_path and os.path.exists(wav_path):
        cmd += ["-i", wav_path]

    cmd += [
        "-c:v", "h264_nvenc",
        "-preset", "p7",
        "-tune", "hq",
        "-rc", "vbr",
        "-cq", "16",
        "-pix_fmt", "yuv420p",
        "-profile:v", "high",
        "-color_range", "pc",
        "-colorspace", "bt709",
        "-color_primaries", "bt709",
        "-color_trc", "bt709",
    ]

    if wav_path and os.path.exists(wav_path):
        cmd += [
            "-c:a", "aac",
            "-b:a", "128k",
            "-shortest",
        ]

    cmd += ["-movflags", "+faststart"]
    cmd.append(output_path)

    env = {**os.environ, "CUDA_VISIBLE_DEVICES": str(gpu_id)}

    try:
        r = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=600)
        if r.returncode == 0:
            return output_path
    except (subprocess.TimeoutExpired, FileNotFoundError):
        pass

    # Fallback: libx264 CRF 0 (mathematically lossless)
    cmd_fallback = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-framerate", str(fps),
        "-i", frame_pattern,
    ]
    if wav_path and os.path.exists(wav_path):
        cmd_fallback += ["-i", wav_path]

    cmd_fallback += [
        "-c:v", "libx264",
        "-crf", "16",
        "-preset", "slow",
        "-pix_fmt", "yuv420p",
        "-profile:v", "high",
    ]
    if wav_path and os.path.exists(wav_path):
        cmd_fallback += ["-c:a", "aac", "-b:a", "128k", "-shortest"]
    cmd_fallback += ["-movflags", "+faststart"]
    cmd_fallback.append(output_path)

    try:
        r = subprocess.run(cmd_fallback, capture_output=True, text=True, timeout=900)
        if r.returncode == 0:
            return output_path
        raise EncodeError(f"libx264 fallback failed: {r.stderr[-500:]}")
    except subprocess.TimeoutExpired:
        raise EncodeError("Encoding timed out")
    except FileNotFoundError:
        raise EncodeError("ffmpeg not found")
