"""bootstrap.py — Single-command Kaggle launcher.

Paste ONE line into a Kaggle cell:
    !git clone https://github.com/shivaww/Recorder.git /kaggle/working/R && cd /kaggle/working/R/kaggle && python bootstrap.py

This script:
  1. Installs all dependencies (Playwright, FFmpeg, cloudflared, numpy)
  2. Runs preflight checks
  3. Starts the render server
  4. Starts Cloudflare tunnel
  5. Prints API KEY + BASE URL for the Android app
"""
import subprocess
import sys
import os
import time
import shutil

WORK = "/kaggle/working"
REPO_DIR = os.path.join(WORK, "R", "kaggle")
CF_PATH = os.path.join(WORK, "cloudflared")


def step(msg):
    print(f"\n{'='*50}\n  {msg}\n{'='*50}", flush=True)


def run_live(cmd, **kwargs):
    """Run a command, streaming its output to the notebook in real time."""
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, bufsize=1, **kwargs)
    for line in proc.stdout:
        print("   | " + line.rstrip(), flush=True)
    proc.wait()
    if proc.returncode != 0:
        raise subprocess.CalledProcessError(proc.returncode, cmd)


def install_deps():
    step("STEP 1/5: Installing dependencies")

    print("  -> pip install playwright numpy...", flush=True)
    run_live([sys.executable, "-m", "pip", "install", "playwright", "numpy"])

    print("  -> playwright install chromium (takes a few minutes)...", flush=True)
    run_live(["playwright", "install", "--with-deps", "chromium"])

    print("  -> downloading cloudflared...")
    if not os.path.exists(CF_PATH):
        subprocess.run([
            "wget", "-q",
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64",
            "-O", CF_PATH
        ], check=True)
    os.chmod(CF_PATH, 0o755)

    print("  -> verifying ffmpeg + NVENC...")
    if not shutil.which("ffmpeg"):
        print("  -> installing ffmpeg...", flush=True)
        run_live(["apt-get", "update", "-qq"])
        run_live(["apt-get", "install", "-y", "ffmpeg"])

    r = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True)
    if "h264_nvenc" in r.stdout:
        print("  -> NVENC: available")
    else:
        print("  -> WARNING: h264_nvenc not found, will use libx264 fallback")

    print("  Dependencies installed.")


def run_preflight_checks():
    step("STEP 2/5: Running preflight checks")
    sys.path.insert(0, REPO_DIR)
    os.chdir(REPO_DIR)

    from preflight import run_preflight, get_api_key
    from config import PORT

    success, report = run_preflight(PORT)
    if not success:
        print("\n  PREFLIGHT FAILED. Fix issues above and re-run.")
        sys.exit(1)

    api_key = get_api_key()
    return api_key, PORT


def start_server():
    step("STEP 3/5: Starting BrollRender server")
    proc = subprocess.Popen(
        [sys.executable, "main.py"],
        cwd=REPO_DIR,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )
    time.sleep(3)
    if proc.poll() is not None:
        out = proc.stdout.read()
        print(f"  Server crashed:\n{out}")
        sys.exit(1)
    print("  Server running.")
    return proc


def start_tunnel(port):
    step("STEP 4/5: Starting Cloudflare tunnel")
    proc = subprocess.Popen(
        [CF_PATH, "tunnel", "--url", f"http://localhost:{port}"],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )
    return proc


def wait_for_url(cf_proc, api_key):
    step("STEP 5/5: Waiting for public URL")
    print("  Scanning tunnel output...")

    while True:
        line = cf_proc.stdout.readline()
        if not line:
            if cf_proc.poll() is not None:
                print("  Tunnel process died.")
                return None
            continue

        if "trycloudflare.com" in line:
            for tok in line.split():
                if tok.startswith("https://"):
                    return tok


if __name__ == "__main__":
    print("\n" + "#"*50)
    print("#  BROLLRENDER KAGGLE BOOTSTRAP")
    print("#"*50)

    install_deps()
    api_key, port = run_preflight_checks()
    server_proc = start_server()
    cf_proc = start_tunnel(port)
    url = wait_for_url(cf_proc, api_key)

    if url:
        print("\n" + "#"*50)
        print("#  READY — ENTER THESE IN THE ANDROID APP")
        print("#"*50)
        print(f"\n  API KEY:  {api_key}")
        print(f"  BASE URL: {url}\n")
        print("#"*50)
        print("\n  Server is running. Keep this cell alive.")
        print("  Press Ctrl+C or stop the cell to shut down.\n")

        # Keep alive
        try:
            while server_proc.poll() is None and cf_proc.poll() is None:
                time.sleep(2)
        except KeyboardInterrupt:
            pass
        finally:
            server_proc.terminate()
            cf_proc.terminate()
    else:
        print("  Failed to get tunnel URL. Try re-running.")
        server_proc.terminate()
        cf_proc.terminate()
