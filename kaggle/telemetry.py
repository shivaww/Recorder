"""telemetry.py — Real-time job status, ETA, GPU usage, error tracking.

Provides structured status data the app polls via /jobs/{id}/status.
Tracks per-job progress, estimates completion time, monitors GPU utilization.
"""
import time
import subprocess
import threading


class JobTelemetry:
    """Per-job telemetry tracker."""

    def __init__(self, job_id, total_frames):
        self.job_id = job_id
        self.total_frames = total_frames
        self.current_frame = 0
        self.state = "QUEUED"
        self.error = None
        self.started_at = None
        self.encoding_started_at = None
        self.done_at = None
        self.fps_actual = 0.0
        self._lock = threading.Lock()

    def mark_rendering(self):
        with self._lock:
            self.state = "RENDERING"
            self.started_at = time.time()

    def mark_encoding(self):
        with self._lock:
            self.state = "ENCODING"
            self.encoding_started_at = time.time()

    def mark_done(self):
        with self._lock:
            self.state = "DONE"
            self.done_at = time.time()

    def mark_failed(self, error):
        with self._lock:
            self.state = "FAILED"
            self.error = str(error)

    def mark_cancelled(self):
        with self._lock:
            self.state = "CANCELLED"

    def update_frame(self, frame, total):
        with self._lock:
            self.current_frame = frame
            self.total_frames = total
            if self.started_at and frame > 0:
                elapsed = time.time() - self.started_at
                self.fps_actual = frame / elapsed if elapsed > 0 else 0

    @property
    def progress_pct(self):
        if self.total_frames <= 0:
            return 0
        return int(self.current_frame * 100 / self.total_frames)

    @property
    def eta_seconds(self):
        """Estimated seconds remaining based on actual render rate."""
        with self._lock:
            if self.state == "DONE":
                return 0
            if self.state == "ENCODING":
                return 5  # encoding is fast on NVENC
            if self.fps_actual <= 0 or self.current_frame <= 0:
                return -1
            remaining = self.total_frames - self.current_frame
            return int(remaining / self.fps_actual)

    def to_status_dict(self):
        """Format for the /jobs/{id}/status API response."""
        with self._lock:
            return {
                "state": self.state,
                "progress": {
                    "frame": self.current_frame,
                    "total_frames": self.total_frames,
                    "pct": self.progress_pct,
                },
                "eta_sec": self.eta_seconds,
                "render_fps": round(self.fps_actual, 1),
                "error": self.error,
            }


def get_gpu_usage():
    """Query nvidia-smi for GPU utilization. Returns list of dicts."""
    try:
        r = subprocess.run(
            ["nvidia-smi",
             "--query-gpu=index,utilization.gpu,memory.used,memory.total,temperature.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=5
        )
        if r.returncode != 0:
            return []
        gpus = []
        for line in r.stdout.strip().split("\n"):
            parts = [p.strip() for p in line.split(",")]
            if len(parts) >= 5:
                gpus.append({
                    "id": int(parts[0]),
                    "util_pct": int(parts[1]),
                    "mem_used_mb": int(parts[2]),
                    "mem_total_mb": int(parts[3]),
                    "temp_c": int(parts[4]),
                })
        return gpus
    except Exception:
        return []
