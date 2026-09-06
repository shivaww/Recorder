package com.brollrender.app.remote

object KaggleScripts {
    val step1 = """
import subprocess
import glob
import shutil

print("=== Installing Playwright ===")
subprocess.run(["pip", "install", "-q", "playwright"], check=True)
subprocess.run(["playwright", "install", "--with-deps", "chromium"], check=True)

print("\n=== Downloading FFmpeg (NVENC) ===")
ffmpeg_url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz"
subprocess.run(["wget", "-q", ffmpeg_url, "-O", "ffmpeg.tar.xz"], check=True)
subprocess.run(["tar", "-xf", "ffmpeg.tar.xz"], check=True)

extracted_dir = glob.glob("ffmpeg-*-linux64-gpl")[0]
for f in glob.glob(f"{extracted_dir}/bin/ff*"):
    shutil.copy(f, "/usr/local/bin/")

print("\n=== Verifying NVENC ===")
result = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True)
if "h264_nvenc" in result.stdout:
    print("h264_nvenc is available!")
else:
    print("WARNING: h264_nvenc not found!")

print("\n=== Setup Complete ===")
""".trimIndent()

    val step2 = """
import os
import sys
import subprocess
import urllib.request
from pathlib import Path
import stat

VERSION = "2026.8.2"
FILENAME = "cloudflared-linux-amd64"
URL = f"https://github.com/cloudflare/cloudflared/releases/download/{VERSION}/{FILENAME}"

DEST_DIR = Path("/kaggle/working")
BINARY_PATH = DEST_DIR / "cloudflared"

def download_with_progress(url: str, dest: Path):
    print(f"Downloading: {url}")
    print(f"Saving to  : {dest}")
    dest.parent.mkdir(parents=True, exist_ok=True)

    def reporthook(block_num, block_size, total_size):
        downloaded = block_num * block_size
        if total_size > 0:
            percent = min(100, downloaded * 100 // total_size)
            mb = downloaded / (1024 * 1024)
            total_mb = total_size / (1024 * 1024)
            sys.stdout.write(f"\r[{percent:3d}%] {mb:.1f} / {total_mb:.1f} MB")
            sys.stdout.flush()
        else:
            sys.stdout.write(f"\rDownloaded: {downloaded / (1024*1024):.1f} MB")
            sys.stdout.flush()

    urllib.request.urlretrieve(url, dest, reporthook)
    print("\nDownload complete.")

def make_executable(path: Path):
    path.chmod(path.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    print(f"Made executable: {path}")

def main():
    print("=" * 60)
    print("Downloading cloudflared for Cloudflare Quick Tunnel")
    print("=" * 60)

    if BINARY_PATH.exists():
        print(f"Binary already exists: {BINARY_PATH}")
        make_executable(BINARY_PATH)
    else:
        download_with_progress(URL, BINARY_PATH)
        make_executable(BINARY_PATH)

    print("\nVerifying binary...")
    result = subprocess.run(
        [str(BINARY_PATH), "--version"],
        capture_output=True, text=True
    )
    version_output = result.stdout.strip() or result.stderr.strip()
    print(f"Version: {version_output}" if version_output else "Could not run version check")

    print("\n" + "=" * 60)
    print("Ready!")
    print(f"Binary location: {BINARY_PATH}")
    print("=" * 60)

if __name__ == "__main__":
    main()
""".trimIndent()

    val step3 = """
%%writefile server.py
import os
import re
import json
import threading
import subprocess
import shutil
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, unquote

from playwright.sync_api import sync_playwright

API_KEY = "your_secret_api_key_here"  # CHANGE THIS - must match the Android app
PORT = 8000
NUM_GPUS = 2
WORK_DIR = "/kaggle/working/broll_jobs"
os.makedirs(WORK_DIR, exist_ok=True)

JOBS = {}
JOBS_LOCK = threading.Lock()
executor = ThreadPoolExecutor(max_workers=NUM_GPUS)

_gpu_lock = threading.Lock()
_next_gpu = [0]


def assign_gpu():
    with _gpu_lock:
        g = _next_gpu[0] % NUM_GPUS
        _next_gpu[0] += 1
        return g


def parse_multipart(body, boundary):
    fields = {}
    files = {}
    parts = body.split(b"--" + boundary)
    for part in parts:
        if part in (b"--", b"--\r\n", b"", b"\r\n") or b"\r\n\r\n" not in part:
            continue
        header_block, content = part.split(b"\r\n\r\n", 1)
        content = content[:-2]
        headers = {}
        for line in header_block.split(b"\r\n"):
            if b": " in line:
                k, v = line.split(b": ", 1)
                headers[k.lower()] = v
        cd = headers.get(b"content-disposition", b"").decode()
        name = None
        filename = None
        for item in cd.split(";"):
            item = item.strip()
            if item.startswith("name="):
                name = item[5:].strip('"')
            elif item.startswith("filename="):
                filename = item[9:].strip('"')
        if filename:
            files[name] = {"filename": filename, "data": content}
        elif name:
            fields[name] = content.decode()
    return fields, files


def process_job(job_id):
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job or job["cancel_event"].is_set():
            return
        job["state"] = "RENDERING"
        gpu = job["gpu"]
        html_path = job["html_path"]
        fps = job["fps"]
        resolution = job["resolution"]
        duration = job["duration"]
        enhance = job["enhance"]
        cancel_event = job["cancel_event"]

    job_dir = os.path.join(WORK_DIR, job_id)
    frames_dir = os.path.join(job_dir, "frames")
    os.makedirs(frames_dir, exist_ok=True)

    def cleanup_and_mark(state, error=None):
        shutil.rmtree(frames_dir, ignore_errors=True)
        with JOBS_LOCK:
            if job_id in JOBS:
                JOBS[job_id]["state"] = state
                if error:
                    JOBS[job_id]["error"] = error
        if state == "CANCELLED":
            shutil.rmtree(job_dir, ignore_errors=True)
            with JOBS_LOCK:
                JOBS.pop(job_id, None)

    try:
        width, height = map(int, resolution.split("x"))
        total_frames = int(fps * duration)

        with sync_playwright() as p:
            browser = p.chromium.launch(args=[
                "--no-sandbox", "--hide-scrollbars",
                "--disable-gpu", "--disable-dev-shm-usage",
            ])
            page = browser.new_page(viewport={"width": width, "height": height}, device_scale_factor=1)
            page.goto(f"file://{html_path}", wait_until="networkidle")
            
            # Wait for fonts and images (ported from JsContracts.kt)
            page.wait_for_function("document.fonts.status === 'loaded'", timeout=15000)
            page.wait_for_function("""() => {
                const imgs = document.querySelectorAll('img');
                if (imgs.length === 0) return true;
                for (const img of imgs) {
                    if (!img.complete || img.naturalWidth === 0) return false;
                }
                return true;
            }""", timeout=15000)

            # Detect Frame (ported from DETECT_FRAME_JS)
            detect_js = """(() => {
              const el = document.querySelector('.fit')
                  || document.querySelector('#video-frame')
                  || [...document.querySelectorAll('div')].find(d => {
                       const r = d.getBoundingClientRect();
                       return r.width > 100 && Math.abs(r.width / r.height - 16 / 9) < 0.01
                           && !!d.querySelector('.stage');
                     });
              if (!el) return null;
              const r = el.getBoundingClientRect();
              return { x: r.left, y: r.top, w: r.width, h: r.height };
            })()"""
            clip_bounds = page.evaluate(detect_js)
            
            if not clip_bounds:
                # Fallback to content bounds (ported from CONTENT_BOUNDS_JS)
                content_js = """(() => {
                  const de = document.documentElement;
                  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
                  const els = document.body ? document.body.querySelectorAll('*') : [];
                  for (const el of els) {
                    if (el.offsetParent === null && getComputedStyle(el).position !== 'fixed') continue;
                    const r = el.getBoundingClientRect();
                    if (r.width < 1 || r.height < 1) continue;
                    minX = Math.min(minX, r.left); minY = Math.min(minY, r.top);
                    maxX = Math.max(maxX, r.right); maxY = Math.max(maxY, r.bottom);
                  }
                  if (minX === Infinity) return null;
                  return { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
                })()"""
                clip_bounds = page.evaluate(content_js)
                if not clip_bounds:
                    raise RuntimeError("FRAME_NOT_FOUND and NO_CONTENT")

            if enhance:
                page.evaluate("() => { document.documentElement.style.filter = 'saturate(1.18) contrast(1.12)'; }")

            # Pause animations (ported from PAUSE_JS)
            page.evaluate("""(() => {
              const s = document.createElement('style');
              s.textContent = '*,*::before,*::after{animation-play-state:paused!important;animation-fill-mode:both!important}';
              document.head.appendChild(s);
              window.__a = document.getAnimations();
            })()""")

            for i in range(total_frames):
                if cancel_event.is_set():
                    browser.close()
                    cleanup_and_mark("CANCELLED")
                    return
                t_ms = (i / fps) * 1000
                # Scrub animations (ported from SEEK_TEMPLATE)
                seek_js = f"window.__a.forEach(a => a.currentTime = {t_ms}); if(window.__broll&&window.__broll.seek)window.__broll.seek({t_ms}/1000); void document.body.offsetHeight"
                page.evaluate(seek_js)
                
                page.screenshot(
                    path=os.path.join(frames_dir, f"frame_{i:05d}.png"), 
                    type="png",
                    clip=clip_bounds
                )
                with JOBS_LOCK:
                    if job_id in JOBS:
                        JOBS[job_id]["frame"] = i + 1
                        JOBS[job_id]["total_frames"] = total_frames
            browser.close()

        if cancel_event.is_set():
            cleanup_and_mark("CANCELLED")
            return

        with JOBS_LOCK:
            if job_id in JOBS:
                JOBS[job_id]["state"] = "ENCODING"

        output_mp4 = os.path.join(job_dir, "output.mp4")
        base_cmd = [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-framerate", str(fps),
            "-i", os.path.join(frames_dir, "frame_%05d.png"),
            "-pix_fmt", "yuv420p",
        ]
        env = {**os.environ, "CUDA_VISIBLE_DEVICES": str(gpu)}
        r = subprocess.run(
            base_cmd + ["-c:v", "h264_nvenc", "-preset", "p5", "-b:v", "16M", output_mp4],
            env=env, capture_output=True, text=True
        )
        if r.returncode != 0:
            subprocess.run(
                base_cmd + ["-c:v", "libx264", "-preset", "medium", "-b:v", "16M", output_mp4],
                check=True
            )

        shutil.rmtree(frames_dir, ignore_errors=True)
        with JOBS_LOCK:
            if job_id in JOBS:
                JOBS[job_id]["state"] = "DONE"
                JOBS[job_id]["file_path"] = output_mp4

    except Exception as e:
        cleanup_and_mark("FAILED", error=str(e))


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, code, data):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _check_key(self):
        if self.headers.get("X-API-Key") != API_KEY:
            self._send_json(403, {"error": "Forbidden"})
            return False
        return True

    def do_GET(self):
        try:
            path = urlparse(self.path).path
            if path == "/health":
                self._send_json(200, {"status": "OK"})
                return
            if not self._check_key():
                return
            parts = path.split("/")
            if path.startswith("/jobs/") and path.endswith("/status") and len(parts) >= 3:
                job_id = parts[2]
                with JOBS_LOCK:
                    job = JOBS.get(job_id)
                if not job:
                    self._send_json(404, {"error": "Not found"})
                    return
                self._send_json(200, {
                    "state": job["state"],
                    "progress": {"frame": job.get("frame", 0), "total_frames": job.get("total_frames", 0)},
                    "error": job.get("error"),
                })
                return
            if path.startswith("/jobs/") and path.endswith("/download") and len(parts) >= 3:
                job_id = parts[2]
                with JOBS_LOCK:
                    job = JOBS.get(job_id)
                if not job or job["state"] != "DONE" or not os.path.exists(job.get("file_path", "")):
                    self._send_json(404, {"error": "Not found or not ready"})
                    return
                file_path = job["file_path"]
                self.send_response(200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Length", str(os.path.getsize(file_path)))
                self.end_headers()
                with open(file_path, "rb") as f:
                    while True:
                        chunk = f.read(8192)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                return
            self._send_json(404, {"error": "Unknown route"})
        except Exception as e:
            self._send_json(500, {"error": str(e)})

    def do_DELETE(self):
        try:
            if not self._check_key():
                return
            path = urlparse(self.path).path
            parts = path.split("/")
            if path.startswith("/jobs/") and len(parts) >= 3:
                job_id = parts[2]
                with JOBS_LOCK:
                    job = JOBS.get(job_id)
                if not job:
                    self._send_json(404, {"error": "Not found"})
                    return
                if job["state"] in ("DONE", "FAILED", "CANCELLED"):
                    with JOBS_LOCK:
                        JOBS.pop(job_id, None)
                    shutil.rmtree(os.path.join(WORK_DIR, job_id), ignore_errors=True)
                else:
                    job["cancel_event"].set()
                    with JOBS_LOCK:
                        job["state"] = "CANCELLED"
                self.send_response(204)
                self.end_headers()
                return
            self._send_json(404, {"error": "Unknown route"})
        except Exception as e:
            self._send_json(500, {"error": str(e)})

    def do_POST(self):
        try:
            if not self._check_key():
                return
            path = urlparse(self.path).path
            if path != "/jobs":
                self._send_json(404, {"error": "Unknown route"})
                return
            ctype = self.headers.get("Content-Type", "")
            if "boundary=" not in ctype:
                self._send_json(400, {"error": "Invalid Content-Type"})
                return
            boundary = ctype.split("boundary=")[1].encode()
            length = int(self.headers.get("Content-Length", 0) or 0)
            body = self.rfile.read(length)
            fields, files = parse_multipart(body, boundary)
            if "html" not in files:
                self._send_json(400, {"error": "Missing html file"})
                return

            try:
                fps = int(fields.get("fps", 30))
                duration = float(fields.get("duration", 10))
                resolution = fields.get("resolution", "1920x1080")
                w, h = map(int, resolution.split("x"))
                enhance = fields.get("enhance", "false").lower() == "true"
            except (ValueError, IndexError):
                self._send_json(400, {"error": "Invalid fps/duration/resolution"})
                return

            job_id = str(uuid.uuid4())[:8]
            job_dir = os.path.join(WORK_DIR, job_id)
            os.makedirs(job_dir, exist_ok=True)
            html_file = files["html"]
            html_path = os.path.join(job_dir, html_file["filename"] or "input.html")
            with open(html_path, "wb") as f:
                f.write(html_file["data"])

            with JOBS_LOCK:
                JOBS[job_id] = {
                    "state": "QUEUED",
                    "frame": 0,
                    "total_frames": int(fps * duration),
                    "error": None,
                    "html_path": html_path,
                    "fps": fps,
                    "resolution": resolution,
                    "duration": duration,
                    "enhance": enhance,
                    "gpu": assign_gpu(),
                    "cancel_event": threading.Event(),
                }
            executor.submit(process_job, job_id)
            self._send_json(200, {"job_id": job_id})
        except Exception as e:
            self._send_json(500, {"error": str(e)})


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Server listening on port {PORT}")
    server.serve_forever()
""".trimIndent()

    val step4 = """
import subprocess, sys, time, os

PORT = 8000
CF_PATH = "/kaggle/working/cloudflared"
SERVER_SCRIPT = "/kaggle/working/server.py"

if not os.path.exists(CF_PATH):
    raise SystemExit("cloudflared not found - download it first.")

print("Starting BrollRender Server...")
server_proc = subprocess.Popen([sys.executable, SERVER_SCRIPT])
time.sleep(2)

print("Starting Cloudflare Tunnel...")
cf_proc = subprocess.Popen(
    [CF_PATH, "tunnel", "--url", f"http://localhost:{PORT}"],
    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
)

print("\nWaiting for public URL...\n")
try:
    while True:
        line = cf_proc.stdout.readline()
        if not line:
            break
        if "trycloudflare.com" in line:
            for tok in line.split():
                if tok.startswith("https://"):
                    print(f"\nPUBLIC BASE URL: {tok}")
                    print("Enter this URL + API key in the app.\n")
        if cf_proc.poll() is not None or server_proc.poll() is not None:
            print("A process exited. Shutting down.")
            break
except KeyboardInterrupt:
    print("\nShutting down...")
finally:
    cf_proc.terminate()
    server_proc.terminate()
""".trimIndent()
}
