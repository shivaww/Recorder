#!/usr/bin/env python3
"""
Breeze2-TTS voice server (Voice Design + Voice Clone + Voice Direction)
for Kaggle GPU notebooks. Mirrors the qwen3tts/server.py job-queue API so
the Nexon Studio client (VoiceApi.kt) talks to it unchanged:
    POST /generate (multipart) -> {id, status}
    GET  /status/<id> / GET /download/<id> -> WAV (mono 24 kHz)
Modes (mapped onto the app's qwen-era field names):
  custom    -> text + optional instruct (no preset speakers exist;
              speaker/language are accepted and ignored)
  clone     -> ref_audio + ref_text (exact transcript, MANDATORY);
              adding instruct turns it into Voice Direction
  design    -> design_prompt: natural-language voice design
  direction -> ref_audio + ref_text + instruct (native mode)
Engine: BreezeBlue/breeze-tts-2 via breezeblue-ai/breeze-tts (eager
attention; bf16 is hard-coded upstream - see the T4 note in run.sh).
run.sh sets BREEZE_REPO / BREEZE_CKPT / PYTHONPATH.
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
BREEZE_REPO = os.environ.get("BREEZE_REPO", "/kaggle/working/breeze-tts")
BREEZE_CKPT = os.environ.get("BREEZE_CKPT", "/kaggle/working/breeze-tts-2")
if os.path.isdir(BREEZE_REPO) and BREEZE_REPO not in sys.path:
    sys.path.insert(0, BREEZE_REPO)

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
    from breeze_infer.runtime import (
        load_runtime,
        resolve_device,
        set_all_seeds,
        update_generation_config_for_breeze,
    )
    from breeze_infer.templates import get_template, prepare_inputs, select_template_name
    from models.fast_streaming import FastBreezeStreamingRuntime, FastStreamingConfig
except ImportError as e:
    sys.exit(
        "breeze_infer / models not importable: %r\n"
        "Expected the breeze-tts repo at BREEZE_REPO=%s (run.sh clones it)." % (e, BREEZE_REPO)
    )

if os.path.isdir("/kaggle/working"):
    OUT_DIR = "/kaggle/working/breeze_output"
elif os.path.isdir("/content"):
    OUT_DIR = "/content/breeze_output"
else:
    OUT_DIR = os.path.join(tempfile.gettempdir(), "breeze_output")
os.makedirs(OUT_DIR, exist_ok=True)

VO_MARKER = "{here complete voice over}"
MAX_CHUNK_CHARS = 500
# Reference clip cap for cloning, seconds. Never truncated: ref_text must
# stay an exact transcript of the whole clip (breeze requirement).
MAX_REF_SECONDS = 30
# Upstream recommends cfg_scale 4 with an instruction; 1.0 (their default)
# when none is present.
CFG_WITH_INSTRUCTION = 4.0
CFG_PLAIN = 1.0
# Match infer.py: caps audio tokens per call; 1.1 repetition penalty.
MAX_NEW_TOKENS = 1500
MAX_SEQ_LEN = 2048
REPETITION_PENALTY = 1.1
# Reference clips must match the codec's native rate or cloning smears.
REF_SR = 24000

DEVICE = resolve_device()
MODES = ("custom", "clone", "design", "direction")

print("Breeze2-TTS server starting (model loads on first use) ...")

# -------------------------------------------------------------- runtime ---
_runtime = None          # FastBreezeStreamingRuntime (all modes share it)
_tokenizer = None
_model = None
_audio_tokenizer = None
_runtime_lock = threading.Lock()


def get_runtime():
    """Load the single Breeze2 runtime on first use.

    One model serves every mode, so there is no per-mode eviction like the
    qwen server - just a lock serializing load vs. use. bf16 is hard-coded
    upstream: on T4 (sm_75) PyTorch emulates it; if the load hard-fails,
    apply the sed patch documented in run.sh and re-run.
    """
    global _runtime, _tokenizer, _model, _audio_tokenizer
    with _runtime_lock:
        if _runtime is None:
            if not os.path.isdir(BREEZE_CKPT):
                sys.exit("FATAL: checkpoint dir not found: %s" % BREEZE_CKPT)
            print("Loading Breeze2 checkpoint on %s (eager attention) ..." % DEVICE)
            tokenizer, model, audio_tokenizer = load_runtime(
                BREEZE_CKPT, device=DEVICE, attn_implementation="eager"
            )
            update_generation_config_for_breeze(model)
            config = FastStreamingConfig(
                max_new_tokens=MAX_NEW_TOKENS,
                max_seq_len=MAX_SEQ_LEN,
                repetition_penalty=REPETITION_PENALTY,
            )
            _runtime = FastBreezeStreamingRuntime(
                model, audio_tokenizer, config, tokenizer=tokenizer
            )
            _tokenizer, _model, _audio_tokenizer = tokenizer, model, audio_tokenizer
            print("Breeze2 runtime ready")
    return _runtime, _tokenizer, _model, _audio_tokenizer


# ------------------------------------------------------------ synthesis ---
def generate_breeze(text, instruction, ref_path, ref_text, cfg_scale, seed, job_id):
    """One synthesis call through the official in-process path.

    Mirrors infer.py: request dict -> select_template_name -> prepare_inputs
    -> set_all_seeds -> iter_audio_chunks. Returns (float32 mono numpy,
    sample_rate).
    """
    runtime, tokenizer, model, audio_tokenizer = get_runtime()
    req = {"id": job_id, "text": text, "speaker": "S0"}
    if instruction:
        req["instruction"] = instruction
    if ref_path:
        req["ref_audio_path"] = str(ref_path)
        req["ref_text"] = ref_text
    template_name = select_template_name(req)
    inputs = prepare_inputs(
        tokenizer, audio_tokenizer, model, [req],
        get_template(template_name),
        guidance_scale=cfg_scale,
        guidance_scale_ref=None,
        guidance_scale_ins=None,
    )
    set_all_seeds(seed)
    pieces = []
    for chunk in runtime.iter_audio_chunks(inputs, request_id=job_id, seed=seed):
        pieces.append(np.asarray(chunk.audio, dtype=np.float32))
    audio = np.concatenate(pieces) if pieces else np.zeros(0, dtype=np.float32)
    return audio, runtime.sample_rate


# -------------------------------------------------------------- helpers ---
SENT_SPLIT = re.compile(r"(?<=[.!?])\s+")
PARA_SPLIT = re.compile(r"\n\s*\n")  # blank line = topic boundary
# Explicit user chunk tags: <chunk1> ... </chunk1> (any label after chunk).
CHUNK_TAG = re.compile(r"<\s*/?\s*chunk[^>]*>", re.IGNORECASE)


def extract_vo(text):
    if VO_MARKER not in text:
        return text.strip()
    parts = text.split(VO_MARKER)
    return " ".join(p.strip() for p in parts[1:] if p.strip())


def _tag_blocks(text):
    """User-marked <chunkN>...</chunkN> runs with the tags stripped.

    Returns None when the script has no tags (caller falls back to
    paragraphs). Untagged runs around/between tags are kept as their own
    blocks so no text is ever dropped.
    """
    if not CHUNK_TAG.search(text):
        return None
    return [p for p in CHUNK_TAG.split(text) if p.strip()]


def chunk_text(text, limit=MAX_CHUNK_CHARS):
    """Tag/paragraph-aware chunking.

    Explicit <chunkN> tags (or blank-line paragraphs when untagged) define
    blocks; sentences pack within a block up to `limit` chars; the last
    chunk of each block is flagged for a topic pause. A single sentence
    longer than the limit is hard-split on commas, then spaces.
    """
    blocks = _tag_blocks(text)
    if blocks is None:
        blocks = [p for p in PARA_SPLIT.split(text) if p.strip()] or [text]
    chunks = []
    for blk in blocks:
        sents = [s.strip() for s in SENT_SPLIT.split(blk) if s.strip()]
        cur = ""
        for s in sents:
            while len(s) > limit:
                if cur:
                    chunks.append((cur, False))
                    cur = ""
                cut = s.rfind(",", 0, limit)
                if cut < limit // 2:
                    cut = s.rfind(" ", 0, limit)
                if cut < limit // 2:
                    cut = limit
                chunks.append((s[:cut].strip(), False))
                s = s[cut:].lstrip()
            if not s:
                continue
            if cur and len(cur) + 1 + len(s) <= limit:
                cur = cur + " " + s
            else:
                if cur:
                    chunks.append((cur, False))
                cur = s
        if cur:
            chunks.append((cur, False))
        if chunks:
            last_c, _ = chunks[-1]
            chunks[-1] = (last_c, True)
    return chunks


def _trim_silence(audio, sr, thresh_db=-45.0, margin_ms=30.0):
    """Trim leading/trailing near-silence so chunk seams are ours to place."""
    if audio.size == 0:
        return audio
    peak = float(np.max(np.abs(audio)))
    if peak <= 0.0:
        return audio
    thresh = peak * (10.0 ** (thresh_db / 20.0))
    loud = np.where(np.abs(audio) >= thresh)[0]
    if loud.size == 0:
        return audio
    margin = int(sr * margin_ms / 1000.0)
    start = max(0, int(loud[0]) - margin)
    end = min(audio.size, int(loud[-1]) + 1 + margin)
    return audio[start:end]


def _edge_fade(audio, sr, fade_ms=25.0):
    """Short linear fades at both ends kill clicks at trimmed boundaries."""
    n = min(audio.size, int(sr * fade_ms / 1000.0))
    if n <= 1:
        return audio
    ramp = np.linspace(0.0, 1.0, n, dtype=np.float32)
    out = audio.copy()
    out[:n] *= ramp
    out[-n:] *= ramp[::-1]
    return out


def _pause_for(chunk, sr, default_ms=150.0, para_end=False):
    """Pause after a chunk; block ends get a topic-shift pause."""
    if para_end:
        return int(sr * 0.55)
    tail = chunk.rstrip()
    if not tail:
        return int(sr * default_ms / 1000.0)
    if tail[-1] in ".!?":
        return int(sr * 0.35)
    if tail[-1] == ",":
        return int(sr * 0.18)
    return int(sr * default_ms / 1000.0)


_synth_lock = threading.Lock()


def _speech_rms(audio):
    """RMS of a mono numpy speech array; None for empty audio."""
    if audio is None or audio.size == 0:
        return None
    return float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)))


def _match_rms(wav, ref_rms, max_gain_db=10.0):
    """Gain-match a chunk to ref_rms (the first chunk's speech level)."""
    r = _speech_rms(wav)
    if r is None or ref_rms is None or r <= 0.0:
        return wav
    cap = 10.0 ** (max_gain_db / 20.0)
    gain = min(max(ref_rms / r, 1.0 / cap), cap)
    if abs(gain - 1.0) < 1e-3:
        return wav
    return np.clip(wav * gain, -1.0, 1.0).astype(np.float32)


def synthesize(mode, text, instruction, ref_path, ref_text, cfg_scale, seed, job_id, loudness=1.0):
    """Serialize the whole job (single model, single GPU) and stitch chunks."""
    vo_text = extract_vo(text)
    chunks = chunk_text(vo_text)
    if not chunks:
        raise ValueError("no text to synthesize after VO marker extraction")
    with _synth_lock:
        pieces = []
        ref_rms = None
        sr = REF_SR
        for i, (chunk, para_end) in enumerate(chunks):
            print("[%s] %s chunk %d/%d: %s..." % (job_id, mode, i + 1, len(chunks), chunk[:55]))
            wav, sr = generate_breeze(
                chunk, instruction, ref_path, ref_text, cfg_scale, seed, job_id
            )
            # Own the seam: trim the model's own edge silence, then fade.
            wav = _trim_silence(wav, sr)
            # Loudness continuity: match every later chunk to the first.
            if i == 0:
                ref_rms = _speech_rms(wav)
            else:
                wav = _match_rms(wav, ref_rms)
            wav = _edge_fade(wav, sr)
            pieces.append(wav)
            if i < len(chunks) - 1:
                pause = _pause_for(chunk, sr, para_end=para_end)
                if pause > 0:
                    pieces.append(np.zeros(pause, dtype=np.float32))
        full = np.concatenate(pieces) if pieces else np.zeros(0, dtype=np.float32)
        if loudness and abs(loudness - 1.0) > 1e-6:
            full = np.clip(full * loudness, -1.0, 1.0).astype(np.float32)
        out_path = os.path.join(OUT_DIR, "%s.wav" % job_id)
        sf.write(out_path, full, sr, subtype="PCM_16")
        return out_path, full.size / float(sr)

# ---------------------------------------------------------------- flask ---
app = Flask(__name__)
CORS(app)
JOBS = {}


@app.route("/health")
def health():
    return jsonify(
        status="ok",
        engine="breeze2-tts",
        device=DEVICE,
        modes=list(MODES),
        checkpoint=BREEZE_CKPT,
        loaded=_runtime is not None,
        sample_rate=REF_SR,
        gpus=torch.cuda.device_count() if DEVICE.startswith("cuda") else 0,
    )


@app.errorhandler(400)
def _bad_request(e):
    desc = getattr(e, "description", str(e))
    print("400 %s %s ctype=%r len=%s desc=%s" % (
        request.method, request.path, request.content_type, request.content_length, desc))
    return jsonify(error=str(desc)), 400


@app.route("/generate", methods=["POST"])
def generate():
    print("/generate ctype=%r form=%s files=%s json=%s" % (
        request.content_type, list(request.form.keys()),
        list(request.files.keys()), request.is_json))
    text = request.form.get("text", "")
    if "file" in request.files and request.files["file"].filename:
        text = request.files["file"].read().decode("utf-8", errors="ignore")
    if not text.strip():
        return jsonify(error="no text or file provided"), 400

    mode = request.form.get("mode", "custom").lower().strip()
    if mode not in MODES:
        mode = "custom"

    # speaker / language / speed are accepted for client compatibility;
    # breeze has no preset voices and no speed API (see docstring up top).
    instruct = request.form.get("instruct", "").strip()
    design_prompt = request.form.get("design_prompt", "").strip()
    ref_text = request.form.get("ref_text", "").strip()

    def _num(name, lo, hi, default):
        try:
            v = float(request.form.get(name, default))
        except (TypeError, ValueError):
            v = default
        return min(max(v, lo), hi)

    loudness = _num("loudness", 0.3, 2.5, 1.0)
    seed = int(_num("seed", 0, 2 ** 31 - 1, 42))

    # ---- mode -> instruction --------------------------------------------
    if mode == "design":
        if not design_prompt:
            return jsonify(error="design_prompt is required for design mode"), 400
        instruction = design_prompt
    elif mode == "direction":
        if not instruct:
            return jsonify(error="instruct is required for direction mode"), 400
        instruction = instruct
    else:  # custom | clone: instruct optional (custom without it = plain synthesis)
        instruction = instruct
    needs_ref = mode in ("clone", "direction")

    # ---- reference audio (clone / direction) -----------------------------
    ref_path = None
    if needs_ref:
        if "ref_audio" not in request.files or not request.files["ref_audio"].filename:
            return jsonify(error="ref_audio is required for %s mode" % mode), 400
        if not ref_text:
            return jsonify(
                error="ref_text is required for %s mode: Breeze cloning needs "
                      "the exact transcript of the reference clip" % mode), 400
        ref_path = os.path.join(OUT_DIR, "ref_%s.wav" % uuid.uuid4().hex[:8])
        request.files["ref_audio"].save(ref_path)
        # Normalize: decode, force mono, rewrite as WAV at the codec rate.
        # Never cut it: ref_text must stay a transcript of the whole clip.
        try:
            try:
                data, sr = sf.read(ref_path, always_2d=True)
            except Exception:
                # m4a/aac: libsndfile can't open it, convert with ffmpeg.
                tmp_in = ref_path + ".in"
                os.replace(ref_path, tmp_in)
                subprocess.run(
                    ["ffmpeg", "-y", "-loglevel", "error", "-i", tmp_in,
                     "-ac", "1", "-ar", str(REF_SR), ref_path],
                    check=True, capture_output=True)
                os.remove(tmp_in)
                data, sr = sf.read(ref_path, always_2d=True)
            if data.shape[1] > 1:
                data = data[:, :1]
            if sr != REF_SR:
                # Match the codec's native rate: a reference clip at a
                # foreign rate smears dense sibilant clusters during cloning.
                ref_t = torch.from_numpy(np.ascontiguousarray(data.T)).float()
                ref_t = torchaudio.functional.resample(ref_t, sr, REF_SR)
                data = ref_t.numpy().T
                sr = REF_SR
            if len(data) > int(sr * MAX_REF_SECONDS):
                return jsonify(
                    error="reference audio is longer than %ds: trim the clip "
                          "and record again" % MAX_REF_SECONDS), 400
            sf.write(ref_path, data, sr)
        except Exception as e:
            return jsonify(error="cannot read reference audio (%s)" % e), 400

    cfg_scale = _num("cfg_scale", 0.5, 10.0,
                     CFG_WITH_INSTRUCTION if instruction else CFG_PLAIN)

    job_id = uuid.uuid4().hex[:12]
    JOBS[job_id] = {"status": "processing", "path": None, "duration": None, "error": None}

    def worker():
        try:
            path, duration = synthesize(
                mode, text, instruction, ref_path, ref_text,
                cfg_scale, seed, job_id, loudness)
            JOBS[job_id].update(status="done", path=path, duration=round(duration, 2))
            print("[%s] done (%.1fs)" % (job_id, duration))
        except Exception as e:
            JOBS[job_id].update(status="error", error=str(e))
            print("[%s] failed: %s" % (job_id, e))

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
                     download_name="breeze2_%s.wav" % job_id)

# ------------------------------------------------------------ cloudflare ---
def start_cloudflared(port):
    binary = os.path.join(OUT_DIR, "cloudflared")
    if not os.path.isfile(binary):
        print("downloading cloudflared ...")
        subprocess.check_call([
            "wget", "-q", "-O", binary,
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64",
        ])
        os.chmod(binary, 0o755)
    proc = subprocess.Popen(
        [binary, "tunnel", "--url", "http://localhost:%d" % port],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
    )
    pattern = re.compile(r"https://[a-zA-Z0-9-]+\.trycloudflare\.com")
    print("waiting for cloudflared tunnel URL ...")
    for line in proc.stdout:
        m = pattern.search(line)
        if m:
            print("\npublic URL: %s\n" % m.group(0))
            break
    else:
        print("cloudflared exited without URL")

    def drain():
        try:
            for _ in proc.stdout:
                pass
        except Exception:
            pass

    threading.Thread(target=drain, daemon=True).start()


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "5001"))
    if not os.environ.get("TTS_NO_TUNNEL"):
        start_cloudflared(port)
    app.run(host="0.0.0.0", port=port, threaded=True)
