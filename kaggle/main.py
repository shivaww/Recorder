"""main.py — BrollRender Kaggle Server orchestrator.

Imports all modules and runs the HTTP server.
Job flow: UPLOADED -> VALIDATED -> WAITING_START -> RENDERING -> ENCODING -> DONE
App uploads HTML, server validates, app sends START signal, server renders.
"""
import os
import json
import threading
import shutil
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse

from config import (
    PORT, WORK_DIR, NUM_GPUS,
    STATE_UPLOADED, STATE_VALIDATING, STATE_VALID, STATE_INVALID,
    STATE_WAITING_START, STATE_RENDERING, STATE_ENCODING,
    STATE_DONE, STATE_FAILED, STATE_CANCELLED
)
from preflight import get_api_key, validate_key, run_preflight
from validator import validate_html
from renderer import render_frames, RenderError
from sfx import mix_audio
from encoder import encode_video, EncodeError
from telemetry import JobTelemetry, get_gpu_usage

# ─── JOB STORE ────────────────────────────────────────────────────────────────
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


# ─── MULTIPART PARSER ─────────────────────────────────────────────────────────

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


# ─── RENDER PIPELINE ──────────────────────────────────────────────────────────

def process_job(job_id):
    """Full render pipeline: frames -> SFX mix -> encode."""
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job:
            return
        job_dir = job["dir"]
        html_path = job["html_path"]
        fps = job["fps"]
        resolution = job["resolution"]
        duration = job["duration"]
        enhance = job["enhance"]
        cancel_event = job["cancel_event"]
        gpu_id = job["gpu"]
        telemetry = job["telemetry"]

    frames_dir = os.path.join(job_dir, "frames")
    wav_path = os.path.join(job_dir, "audio.wav")
    output_mp4 = os.path.join(job_dir, "output.mp4")

    try:
        # Phase 1: Render frames
        telemetry.mark_rendering()
        result = render_frames(
            html_path, frames_dir, fps, resolution, duration, enhance,
            cancel_event,
            progress_cb=lambda f, t: telemetry.update_frame(f, t)
        )

        if cancel_event.is_set():
            telemetry.mark_cancelled()
            shutil.rmtree(job_dir, ignore_errors=True)
            with JOBS_LOCK:
                JOBS.pop(job_id, None)
            return

        # Phase 2: Mix SFX audio
        sfx_events = result.get("sfx_events", [])
        ambience_type = result.get("ambience_type")
        if sfx_events or ambience_type:
            mix_audio(sfx_events, ambience_type, duration, wav_path)
        else:
            wav_path = None

        # Phase 3: Encode
        telemetry.mark_encoding()
        encode_video(frames_dir, output_mp4, fps, wav_path, gpu_id)

        # Cleanup frames
        shutil.rmtree(frames_dir, ignore_errors=True)
        if wav_path and os.path.exists(wav_path):
            os.remove(wav_path)

        telemetry.mark_done()
        with JOBS_LOCK:
            if job_id in JOBS:
                JOBS[job_id]["file_path"] = output_mp4

    except RenderError as e:
        if "CANCELLED" in str(e):
            telemetry.mark_cancelled()
            shutil.rmtree(job_dir, ignore_errors=True)
            with JOBS_LOCK:
                JOBS.pop(job_id, None)
        else:
            telemetry.mark_failed(e)
            shutil.rmtree(frames_dir, ignore_errors=True)
    except EncodeError as e:
        telemetry.mark_failed(e)
        shutil.rmtree(frames_dir, ignore_errors=True)
    except Exception as e:
        print(f"[error] job={job_id} {type(e).__name__}: {e}", flush=True)
        telemetry.mark_failed(e)
        shutil.rmtree(frames_dir, ignore_errors=True)


# ─── VALIDATION WORKER ────────────────────────────────────────────────────────

def validate_job(job_id):
    """Background validation: check HTML, transition to VALID or INVALID."""
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job:
            return
        job["state"] = STATE_VALIDATING
        html_path = job["html_path"]
        resolution = job["resolution"]

    width, height = map(int, resolution.split("x"))
    result = validate_html(html_path, width, height)
    print(f"[validate] job={job_id} valid={result['valid']} reason={result.get('reason')}", flush=True)

    with JOBS_LOCK:
        if job_id not in JOBS:
            return
        if result["valid"]:
            JOBS[job_id]["state"] = STATE_WAITING_START
            JOBS[job_id]["validation"] = result
            JOBS[job_id]["total_frames"] = JOBS[job_id]["fps"] * JOBS[job_id]["duration"]
        else:
            JOBS[job_id]["state"] = STATE_INVALID
            JOBS[job_id]["validation"] = result


# ─── HTTP HANDLER ─────────────────────────────────────────────────────────────

class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        try:
            print(f"[http] {format % args}", flush=True)
        except Exception:
            pass

    def _send_json(self, code, data):
        body = json.dumps(data, default=str).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _check_key(self):
        if not validate_key(self.headers.get("X-API-Key")):
            self._send_json(403, {"error": "Forbidden"})
            return False
        return True

    def do_GET(self):
        try:
            path = urlparse(self.path).path

            if path == "/health":
                self._send_json(200, {"status": "OK", "gpus": NUM_GPUS})
                return

            if not self._check_key():
                return

            parts = path.split("/")

            if path == "/gpu":
                self._send_json(200, {"gpus": get_gpu_usage()})
                return

            if path.startswith("/jobs/") and path.endswith("/status") and len(parts) >= 3:
                job_id = parts[2]
                with JOBS_LOCK:
                    job = JOBS.get(job_id)
                if not job:
                    self._send_json(404, {"error": "Not found"})
                    return
                telemetry = job["telemetry"]
                status = telemetry.to_status_dict()
                if job["state"] in (STATE_VALID, STATE_INVALID, STATE_VALIDATING, STATE_WAITING_START):
                    status["state"] = job["state"]
                    status["validation"] = job.get("validation")
                self._send_json(200, status)
                return

            if path.startswith("/jobs/") and path.endswith("/download") and len(parts) >= 3:
                job_id = parts[2]
                with JOBS_LOCK:
                    job = JOBS.get(job_id)
                if not job or job["state"] != STATE_DONE or not os.path.exists(job.get("file_path", "")):
                    self._send_json(404, {"error": "Not ready"})
                    return
                file_path = job["file_path"]
                file_size = os.path.getsize(file_path)
                self.send_response(200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Length", str(file_size))
                self.end_headers()
                with open(file_path, "rb") as f:
                    while chunk := f.read(65536):
                        self.wfile.write(chunk)
                return

            self._send_json(404, {"error": "Unknown route"})
        except Exception as e:
            import traceback
            print(f"[http-error] GET {self.path}: {type(e).__name__}: {e}", flush=True)
            traceback.print_exc()
            try:
                self._send_json(500, {"error": str(e)})
            except Exception:
                pass

    def do_POST(self):
        try:
            print(f"[http-raw] POST {self.path} key_present={self.headers.get('X-API-Key') is not None}", flush=True)
            if not self._check_key():
                return
            path = urlparse(self.path).path
            parts = path.split("/")

            # Submit new job
            if path == "/jobs":
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
                    enhance = fields.get("enhance", "false").lower() == "true"
                except (ValueError, IndexError):
                    self._send_json(400, {"error": "Invalid parameters"})
                    return

                job_id = str(uuid.uuid4())[:8]
                job_dir = os.path.join(WORK_DIR, job_id)
                os.makedirs(job_dir, exist_ok=True)
                html_file = files["html"]
                data = html_file["data"]
                if fields.get("encoding") == "base64":
                    import base64 as b64mod
                    data = b64mod.b64decode(data)
                html_path = os.path.join(job_dir, html_file["filename"] or "input.html")
                with open(html_path, "wb") as f:
                    f.write(data)

                total_frames = int(fps * duration)
                telemetry = JobTelemetry(job_id, total_frames)

                with JOBS_LOCK:
                    JOBS[job_id] = {
                        "state": STATE_UPLOADED,
                        "dir": job_dir,
                        "html_path": html_path,
                        "fps": fps,
                        "resolution": resolution,
                        "duration": duration,
                        "enhance": enhance,
                        "gpu": assign_gpu(),
                        "cancel_event": threading.Event(),
                        "telemetry": telemetry,
                        "total_frames": total_frames,
                        "file_path": None,
                        "validation": None,
                    }

                # Validate in background
                threading.Thread(target=validate_job, args=(job_id,), daemon=True).start()
                print(f"[recv] job={job_id} html={html_file['filename']} bytes={len(html_file['data'])}", flush=True)
                self._send_json(200, {"job_id": job_id})
                return

            # Start rendering (app sends signal after seeing VALID)
            if len(parts) >= 4 and parts[1] == "jobs" and parts[3] == "start":
                job_id = parts[2]
                with JOBS_LOCK:
                    job = JOBS.get(job_id)
                if not job:
                    self._send_json(404, {"error": "Not found"})
                    return
                if job["state"] != STATE_WAITING_START:
                    self._send_json(400, {"error": f"Cannot start: state is {job['state']}"})
                    return
                job["state"] = STATE_RENDERING
                executor.submit(process_job, job_id)
                self._send_json(200, {"started": True})
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
                if job["state"] in (STATE_DONE, STATE_FAILED, STATE_INVALID, STATE_CANCELLED):
                    with JOBS_LOCK:
                        JOBS.pop(job_id, None)
                    shutil.rmtree(os.path.join(WORK_DIR, job_id), ignore_errors=True)
                else:
                    job["cancel_event"].set()
                    job["telemetry"].mark_cancelled()
                    with JOBS_LOCK:
                        job["state"] = STATE_CANCELLED
                self.send_response(204)
                self.end_headers()
                return
            self._send_json(404, {"error": "Unknown route"})
        except Exception as e:
            self._send_json(500, {"error": str(e)})


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


if __name__ == "__main__":
    success, report = run_preflight(PORT)
    if not success:
        raise SystemExit("Preflight failed. Fix issues above.")

    print(f"Starting server on port {PORT} with {NUM_GPUS} GPU(s)...")
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Server listening on port {PORT}")
    server.serve_forever()
