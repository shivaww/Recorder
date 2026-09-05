import os
import json
import threading
import subprocess
import shutil
import time
import uuid
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright

# !!! MUST MATCH THE API KEY IN THE ANDROID APP !!!
API_KEY = "your_secret_api_key_here" 
PORT = 8000
WORK_DIR = "/kaggle/working/broll_jobs"
os.makedirs(WORK_DIR, exist_ok=True)

JOBS = {}
JOBS_LOCK = threading.Lock()

def parse_multipart(body, boundary):
    """Manually parse multipart/form-data without cgi module."""
    fields = {}
    files = {}
    parts = body.split(b"--" + boundary)
    for part in parts:
        if part in (b"--", b"--\r\n", b"", b"\r\n") or b"\r\n\r\n" not in part:
            continue
        header_block, content = part.split(b"\r\n\r\n", 1)
        content = content[:-2]  # strip trailing \r\n
        
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
    """Background worker: Playwright render -> NVENC encode."""
    with JOBS_LOCK:
        job = JOBS.get(job_id)
        if not job: return
        job["state"] = "RENDERING"
    
    job_dir = os.path.join(WORK_DIR, job_id)
    frames_dir = os.path.join(job_dir, "frames")
    os.makedirs(frames_dir, exist_ok=True)
    
    try:
        width, height = map(int, job["resolution"].split("x"))
        total_frames = job["fps"] * job["duration"]
        
        with sync_playwright() as p:
            browser = p.chromium.launch(args=["--no-sandbox", "--hide-scrollbars"])
            page = browser.new_page(viewport={"width": width, "height": height})
            page.goto(f"file://{job['html_path']}", wait_until="networkidle")
            page.evaluate("document.fonts.ready")
            
            # Apply Enhance CSS filter if requested
            if job["enhance"]:
                page.evaluate("""() => {
                    let s = document.createElement('style');
                    s.textContent = 'html { filter: contrast(1.12) saturate(1.18); }';
                    document.head.appendChild(s);
                }""")
            
            for i in range(total_frames):
                if job_id not in JOBS: break  # Cancelled
                
                time_ms = (i / job["fps"]) * 1000
                page.evaluate(f"""
                    document.getAnimations().forEach(a => {{
                        a.currentTime = {time_ms};
                        a.pause();
                    }});
                """)
                page.screenshot(path=os.path.join(frames_dir, f"frame_{i:05d}.png"), type="png")
                
                with JOBS_LOCK:
                    if job_id in JOBS:
                        JOBS[job_id]["frame"] = i + 1
                        JOBS[job_id]["total_frames"] = total_frames
                        
            browser.close()
            
        if job_id not in JOBS: return  # Cancelled
        
        with JOBS_LOCK:
            JOBS[job_id]["state"] = "ENCODING"
        
        output_mp4 = os.path.join(job_dir, "output.mp4")
        ffmpeg_cmd = [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-framerate", str(job["fps"]),
            "-i", os.path.join(frames_dir, "frame_%05d.png"),
            "-c:v", "h264_nvenc",
            "-preset", "p5",
            "-b:v", "16M",
            "-pix_fmt", "yuv420p",
            output_mp4
        ]
        subprocess.run(ffmpeg_cmd, check=True)
        shutil.rmtree(frames_dir, ignore_errors=True)
        
        with JOBS_LOCK:
            if job_id in JOBS:
                JOBS[job_id]["state"] = "DONE"
                JOBS[job_id]["file_path"] = output_mp4
                
    except Exception as e:
        with JOBS_LOCK:
            if job_id in JOBS:
                JOBS[job_id]["state"] = "FAILED"
                JOBS[job_id]["error"] = str(e)
        shutil.rmtree(frames_dir, ignore_errors=True)

class Handler(BaseHTTPRequestHandler):
    def _send_json(self, code, data):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def _check_key(self):
        if self.headers.get("X-API-Key") != API_KEY:
            self._send_json(403, {"error": "Forbidden"})
            return False
        return True

    def do_GET(self):
        path = urlparse(self.path).path
        
        if path == "/health":
            self._send_json(200, {"status": "OK"})
            return
            
        if not self._check_key(): return
        
        if path.startswith("/jobs/") and path.endswith("/status"):
            job_id = path.split("/")[2]
            with JOBS_LOCK:
                job = JOBS.get(job_id)
            if not job:
                self._send_json(404, {"error": "Not found"})
                return
            self._send_json(200, {
                "state": job["state"],
                "progress": {
                    "frame": job.get("frame", 0),
                    "total_frames": job.get("total_frames", 0)
                },
                "error": job.get("error")
            })
            return
            
        if path.startswith("/jobs/") and path.endswith("/download"):
            job_id = path.split("/")[2]
            with JOBS_LOCK:
                job = JOBS.get(job_id)
            if not job or job["state"] != "DONE" or not os.path.exists(job.get("file_path", "")):
                self._send_json(404, {"error": "Not found or not ready"})
                return
            
            file_path = job["file_path"]
            file_size = os.path.getsize(file_path)
            self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Content-Length", str(file_size))
            self.end_headers()
            with open(file_path, "rb") as f:
                while chunk := f.read(8192):
                    self.wfile.write(chunk)
            return

    def do_DELETE(self):
        if not self._check_key(): return
        path = urlparse(self.path).path
        if path.startswith("/jobs/"):
            job_id = path.split("/")[2]
            with JOBS_LOCK:
                if job_id in JOBS:
                    del JOBS[job_id]
            job_dir = os.path.join(WORK_DIR, job_id)
            shutil.rmtree(job_dir, ignore_errors=True)
            self.send_response(204)
            self.end_headers()
            return

    def do_POST(self):
        if not self._check_key(): return
        path = urlparse(self.path).path
        
        if path == "/jobs":
            ctype = self.headers.get("Content-Type", "")
            if "boundary=" not in ctype:
                self._send_json(400, {"error": "Invalid Content-Type"})
                return
                
            boundary = ctype.split("boundary=")[1].encode()
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length)
            
            fields, files = parse_multipart(body, boundary)
            
            if "html" not in files:
                self._send_json(400, {"error": "Missing html file"})
                return
                
            job_id = str(uuid.uuid4())[:8]
            job_dir = os.path.join(WORK_DIR, job_id)
            os.makedirs(job_dir, exist_ok=True)
            
            html_file = files["html"]
            html_path = os.path.join(job_dir, html_file["filename"] or "input.html")
            with open(html_path, "wb") as f:
                f.write(html_file["data"])
                
            fps = int(fields.get("fps", 30))
            resolution = fields.get("resolution", "1920x1080")
            duration = int(fields.get("duration", 10))
            enhance = fields.get("enhance", "false") == "true"
            
            with JOBS_LOCK:
                JOBS[job_id] = {
                    "state": "QUEUED",
                    "frame": 0,
                    "total_frames": fps * duration,
                    "error": None,
                    "html_path": html_path,
                    "fps": fps,
                    "resolution": resolution,
                    "duration": duration,
                    "enhance": enhance
                }
                
            threading.Thread(target=process_job, args=(job_id,), daemon=True).start()
            self._send_json(200, {"job_id": job_id})
            return

class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True

if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Server listening on port {PORT}")
    server.serve_forever()
