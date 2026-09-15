#!/usr/bin/env python3
"""
Qwen3-TTS full server (CustomVoice + Voice Clone + Voice Design)
for Kaggle / Colab GPU notebooks.

Supports three modes:
  1. custom   → Qwen3-TTS-12Hz-1.7B-CustomVoice  (9 preset speakers + instruct)
  2. clone    → Qwen3-TTS-12Hz-1.7B-Base         (3-second voice cloning)
  3. design   → Qwen3-TTS-12Hz-1.7B-VoiceDesign  (natural language voice design)

Run:
    !python qwen3-tts-server.py
"""

import os
import re
import sys
import uuid
import subprocess
import tempfile
import threading

try:
    sys.stdout.reconfigure(line_buffering=True, write_through=True)
    sys.stderr.reconfigure(line_buffering=True, write_through=True)
except Exception:
    pass

# ---------------------------------------------------------------- setup ---
try:
    import torch
    import torchaudio
    import soundfile as sf
    import numpy as np
except ImportError:
    sys.exit("torch / torchaudio / soundfile missing. Use a GPU runtime.")

try:
    from flask import Flask, request, send_file, jsonify
    from flask_cors import CORS
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "flask", "flask-cors"])
    from flask import Flask, request, send_file, jsonify
    from flask_cors import CORS

try:
    from qwen_tts import Qwen3TTSModel
except ImportError:
    print("Installing qwen-tts ...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "qwen-tts"])
    try:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q",
                               "flash-attn", "--no-build-isolation"], timeout=300)
    except Exception:
        print("flash-attn skipped")
    from qwen_tts import Qwen3TTSModel

if os.path.isdir("/kaggle/working"):
    OUT_DIR = "/kaggle/working/tts_output"
elif os.path.isdir("/content"):
    OUT_DIR = "/content/tts_output"
else:
    OUT_DIR = os.path.join(tempfile.gettempdir(), "tts_output")
os.makedirs(OUT_DIR, exist_ok=True)

VO_MARKER = "{here complete voice over}"
MAX_CHUNK_CHARS = 320
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

# Model IDs
MODELS = {
    "custom": "Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice",
    "clone":  "Qwen/Qwen3-TTS-12Hz-1.7B-Base",
    "design": "Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign",
}

# Lazy-loaded models (to save VRAM)
_loaded = {}

def get_model(mode: str):
    mode = mode.lower().strip()
    if mode not in MODELS:
        mode = "custom"
    if mode not in _loaded:
        print(f"Loading {MODELS[mode]} ...")
        kwargs = {
            "device_map": "cuda:0" if DEVICE == "cuda" else "cpu",
            "dtype": torch.bfloat16 if DEVICE == "cuda" else torch.float32,
        }
        if DEVICE == "cuda":
            kwargs["attn_implementation"] = "flash_attention_2"
        _loaded[mode] = Qwen3TTSModel.from_pretrained(MODELS[mode], **kwargs)
        print(f"{mode} model ready")
    return _loaded[mode]


print("Qwen3-TTS server starting (models load on first use) ...")

# ------------------------------------------------------------- helpers ---
SENT_SPLIT = re.compile(r"(?<=[.!?])\s+")


def extract_vo(text: str) -> str:
    if VO_MARKER not in text:
        return text.strip()
    parts = text.split(VO_MARKER)
    return " ".join(p.strip() for p in parts[1:] if p.strip())


def chunk_text(text: str, max_chars: int = MAX_CHUNK_CHARS):
    sentences = [s for s in SENT_SPLIT.split(text) if s]
    chunks, cur = [], ""
    for s in sentences:
        if len(cur) + len(s) + 1 <= max_chars:
            cur = f"{cur} {s}".strip()
        else:
            if cur:
                chunks.append(cur)
            cur = s
    if cur:
        chunks.append(cur)
    return chunks or [text]


def generate_one(model, mode, text, language, speaker, instruct, ref_audio, ref_text, design_prompt):
    language = language or "Auto"

    if mode == "custom":
        kwargs = {"text": text, "language": language, "speaker": speaker or "Ryan"}
        if instruct and instruct.strip():
            kwargs["instruct"] = instruct.strip()
        wavs, sr = model.generate_custom_voice(**kwargs)

    elif mode == "clone":
        if not ref_audio or not os.path.isfile(ref_audio):
            raise ValueError("ref_audio is required for clone mode")
        if not ref_text or not ref_text.strip():
            raise ValueError("ref_text is required for clone mode")
        wavs, sr = model.generate_voice_clone(
            text=text,
            language=language,
            ref_audio=ref_audio,
            ref_text=ref_text.strip(),
        )

    elif mode == "design":
        if not design_prompt or not design_prompt.strip():
            raise ValueError("design_prompt is required for design mode")
        # VoiceDesign API (natural language description)
        wavs, sr = model.generate_voice_design(
            text=text,
            language=language,
            instruct=design_prompt.strip(),
        )
    else:
        raise ValueError(f"Unknown mode: {mode}")

    audio = torch.from_numpy(wavs[0]).float()
    return audio, sr


def synthesize(mode, text, language, speaker, instruct, ref_audio, ref_text, design_prompt, job_id):
    model = get_model(mode)
    vo_text = extract_vo(text)
    chunks = chunk_text(vo_text)

    pieces = []
    sr = 24000
    for i, chunk in enumerate(chunks):
        print(f"[{job_id}] {mode} chunk {i+1}/{len(chunks)}: {chunk[:55]}...")
        wav, sr = generate_one(
            model, mode, chunk, language, speaker, instruct,
            ref_audio, ref_text, design_prompt
        )
        pieces.append(wav)
        if i < len(chunks) - 1:
            pieces.append(torch.zeros(int(sr * 0.22)))

    full = torch.cat(pieces, dim=0).unsqueeze(0)
    out_path = os.path.join(OUT_DIR, f"{job_id}.wav")
    torchaudio.save(out_path, full, sr)
    return out_path, full.shape[1] / sr


# ---------------------------------------------------------------- flask ---
app = Flask(__name__)
CORS(app)
JOBS = {}


@app.route("/health")
def health():
    return jsonify(
        status="ok",
        device=DEVICE,
        modes=["custom", "clone", "design"],
        models=MODELS,
        loaded=list(_loaded.keys()),
    )


@app.route("/generate", methods=["POST"])
def generate():
    text = request.form.get("text", "")
    if "file" in request.files and request.files["file"].filename:
        text = request.files["file"].read().decode("utf-8", errors="ignore")
    if not text.strip():
        return jsonify(error="no text or file provided"), 400

    mode = request.form.get("mode", "custom").lower().strip()
    if mode not in ("custom", "clone", "design"):
        mode = "custom"

    language = request.form.get("language", "English").strip() or "English"
    speaker = request.form.get("speaker", "Ryan").strip() or "Ryan"
    instruct = request.form.get("instruct", "").strip()
    design_prompt = request.form.get("design_prompt", "").strip()
    ref_text = request.form.get("ref_text", "").strip()

    # Save reference audio for clone mode
    ref_path = None
    if mode == "clone" and "ref_audio" in request.files and request.files["ref_audio"].filename:
        ref_path = os.path.join(OUT_DIR, f"ref_{uuid.uuid4().hex[:8]}.wav")
        request.files["ref_audio"].save(ref_path)

    job_id = uuid.uuid4().hex[:12]
    JOBS[job_id] = {"status": "processing", "path": None, "duration": None, "error": None}

    def worker():
        try:
            path, duration = synthesize(
                mode, text, language, speaker, instruct,
                ref_path, ref_text, design_prompt, job_id
            )
            JOBS[job_id].update(status="done", path=path, duration=round(duration, 2))
            print(f"[{job_id}] done ({duration:.1f}s)")
        except Exception as e:
            JOBS[job_id].update(status="error", error=str(e))
            print(f"[{job_id}] failed: {e}")

    threading.Thread(target=worker, daemon=True).start()
    return jsonify(id=job_id, status="processing", mode=mode), 202


@app.route("/status/<job_id>")
def status(job_id):
    job = JOBS.get(job_id)
    if not job:
        return jsonify(error="not found"), 404
    return jsonify(id=job_id, **job)


@app.route("/download/<job_id>")
def download(job_id):
    job = JOBS.get(job_id)
    if not job:
        return jsonify(error="not found"), 404
    if job["status"] == "processing":
        return jsonify(error="still processing"), 425
    if job["status"] == "error":
        return jsonify(error=job["error"]), 500
    path = job["path"]
    if not path or not os.path.isfile(path):
        return jsonify(error="file missing"), 404
    return send_file(path, mimetype="audio/wav", as_attachment=True,
                     download_name=f"qwen3_{job_id}.wav")


# ------------------------------------------------------------ cloudflare --
def start_cloudflared(port: int):
    binary = os.path.join(OUT_DIR, "cloudflared")
    if not os.path.isfile(binary):
        print("downloading cloudflared ...")
        subprocess.check_call([
            "wget", "-q", "-O", binary,
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64",
        ])
        os.chmod(binary, 0o755)

    proc = subprocess.Popen(
        [binary, "tunnel", "--url", f"http://localhost:{port}"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
    )
    pattern = re.compile(r"https://[a-zA-Z0-9-]+\.trycloudflare\.com")
    print("waiting for cloudflared tunnel URL ...")
    for line in proc.stdout:
        m = pattern.search(line)
        if m:
            print(f"\npublic URL: {m.group(0)}\n")
            break
    else:
        print("cloudflared exited without URL")

    def drain():
        for _ in proc.stdout:
            pass
    threading.Thread(target=drain, daemon=True).start()
    return proc


if __name__ == "__main__":
    PORT = int(os.environ.get("PORT", "5000"))
    # run.sh starts its own cloudflared tunnel and sets TTS_NO_TUNNEL=1;
    # standalone `python server.py` keeps the built-in tunnel behavior.
    if os.environ.get("TTS_NO_TUNNEL") != "1":
        start_cloudflared(PORT)
    print(f"starting flask on port {PORT} ...")
    app.run(host="0.0.0.0", port=PORT)
