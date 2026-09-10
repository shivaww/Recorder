"""preflight.py — Environment validation and connection checks.

Import this from main.py before starting the server:
    from preflight import run_preflight
"""
import os
import sys
import shutil
import subprocess
import socket
import urllib.request

PORT = 8000


# ─── CONNECTION CHECKS ────────────────────────────────────────────────────────

def check_internet(timeout=5):
    """Verify outbound internet access (multiple fallbacks, no SSL pitfalls)."""
    for url in ("http://www.google.com", "http://github.com", "http://1.1.1.1"):
        try:
            urllib.request.urlopen(url, timeout=timeout)
            return True
        except Exception:
            continue
    try:
        import socket
        socket.create_connection(("1.1.1.1", 443), timeout=timeout)
        return True
    except Exception:
        return False


def check_port_available(port=PORT):
    """Ensure the target port isn't already in use."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind(("0.0.0.0", port))
            return True
        except OSError:
            return False


def check_cloudflared(path="/kaggle/working/cloudflared"):
    """Verify cloudflared binary exists and is executable."""
    if not os.path.isfile(path):
        return False
    if not os.access(path, os.X_OK):
        return False
    return True


# ─── GPU CHECKS ───────────────────────────────────────────────────────────────

def get_gpu_count():
    """Detect available NVIDIA GPUs via nvidia-smi."""
    try:
        r = subprocess.run(
            ["nvidia-smi", "--query-gpu=index", "--format=csv,noheader"],
            capture_output=True, text=True, timeout=10
        )
        if r.returncode == 0:
            return len(r.stdout.strip().split("\n"))
    except Exception:
        pass
    return 0


# ─── DEPENDENCY CHECKS ────────────────────────────────────────────────────────

def check_playwright():
    """Verify Playwright + Chromium are installed."""
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            # Just verify the executable exists
            path = p.chromium.executable_path
            return os.path.isfile(path)
    except Exception:
        return False


def check_ffmpeg_nvenc():
    """Verify ffmpeg exists and has h264_nvenc support."""
    if not shutil.which("ffmpeg"):
        return False
    try:
        r = subprocess.run(
            ["ffmpeg", "-hide_banner", "-encoders"],
            capture_output=True, text=True, timeout=10
        )
        return "h264_nvenc" in r.stdout
    except Exception:
        return False


def check_numpy():
    try:
        import numpy
        return True
    except ImportError:
        return False


# ─── FULL PREFLIGHT ───────────────────────────────────────────────────────────

def run_preflight(port=PORT):
    """Run all checks. Returns (success: bool, report: dict)."""
    report = {}

    report["internet"] = check_internet()
    report["port_free"] = check_port_available(port)
    report["cloudflared"] = check_cloudflared()
    report["gpu_count"] = get_gpu_count()
    report["playwright"] = check_playwright()
    report["ffmpeg_nvenc"] = check_ffmpeg_nvenc()
    report["numpy"] = check_numpy()

    # Verdict
    critical = ["port_free", "playwright", "ffmpeg_nvenc", "numpy"]
    success = all(report[k] for k in critical)

    # Print summary
    print("\n" + "=" * 50)
    print("PREFLIGHT CHECK")
    print("=" * 50)
    for k, v in report.items():
        icon = "✓" if v else "✗"
        print(f"  {icon} {k}: {v}")
    print("=" * 50)
    if success:
        print(f"  GPUs: {report['gpu_count']}x T4")
        print("  STATUS: READY")
    else:
        print("  STATUS: BLOCKED — fix failures above")
    print("=" * 50 + "\n")

    return success, report


if __name__ == "__main__":
    run_preflight()
