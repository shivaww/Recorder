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

try:
    from faster_whisper import WhisperModel
except ImportError:
    try:
        print("Installing faster-whisper ...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "faster-whisper"])
        from faster_whisper import WhisperModel
    except Exception:
        print("faster-whisper unavailable: clone ref_text falls back to client transcript")
        WhisperModel = None

if os.path.isdir("/kaggle/working"):
    OUT_DIR = "/kaggle/working/tts_output"
elif os.path.isdir("/content"):
    OUT_DIR = "/content/tts_output"
else:
    OUT_DIR = os.path.join(tempfile.gettempdir(), "tts_output")
os.makedirs(OUT_DIR, exist_ok=True)

VO_MARKER = "{here complete voice over}"
MAX_CHUNK_CHARS = 500
# Longest reference clip accepted for cloning, in seconds. Clips are never
# truncated: ref_text must stay a transcript of the whole clip.
MAX_REF_SECONDS = 30
# Sampling preset for x-vector cloning: balanced prosody without instability.
# Temperature is variation, not an emotion slider; emotion comes from text.
GEN_KWARGS = {"max_new_tokens": 2048, "temperature": 0.85, "top_p": 0.95,
              "top_k": 50, "repetition_penalty": 1.05}
# Escape hatch for upstream issue #341 (ICL speaks the reference tail):
# x-vector-only cloning has no reference-text conditioning to leak from.
# Flip to True if the per-chunk trim net ever proves insufficient.
# FIX FOR UPSTREAM #341: Use x-vector only to prevent reference-tail leakage.
# This mode ignores ref_text conditioning, stopping "Easy to follow" leaks.
CLONE_X_VECTOR = True
# DEBUG_DISABLE_LEAK_TRIM is now irrelevant as leak source is blocked upstream.
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
# bf16 needs compute capability 8.0+. T4 and P100 lack it, and fp16 is unstable
# for Qwen3-TTS, so those GPUs run fp32.
if DEVICE == "cuda" and torch.cuda.get_device_capability()[0] >= 8:
    MODEL_DTYPE = torch.bfloat16
else:
    MODEL_DTYPE = torch.float32

# Model IDs
MODELS = {
    "custom": "Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice",
    "clone":  "Qwen/Qwen3-TTS-12Hz-1.7B-Base",
    "design": "Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign",
}

# Lazy-loaded models (to save VRAM)
_loaded = {}
_loaded_dev = {}


def _device_for(mode: str) -> str:
    """Spread models across GPUs: custom->0, clone->1, design->0 (2 GPUs)."""
    if DEVICE != "cuda":
        return "cpu"
    order = ["custom", "clone", "design"]
    return f"cuda:{order.index(mode) % torch.cuda.device_count()}"


def _evict_device(dev: str):
    """Unload any model already holding this device so the next one fits."""
    for m in [k for k, d in _loaded_dev.items() if d == dev]:
        print(f"Unloading {m} model from {dev} to free VRAM ...")
        _loaded.pop(m, None)
        _loaded_dev.pop(m, None)
    import gc
    gc.collect()
    if DEVICE == "cuda":
        torch.cuda.empty_cache()


def get_model(mode: str):
    mode = mode.lower().strip()
    if mode not in MODELS:
        mode = "custom"
    if mode not in _loaded:
        dev = _device_for(mode)
        _evict_device(dev)
        print(f"Loading {MODELS[mode]} on {dev} ...")
        kwargs = {
            "device_map": dev,
            "dtype": MODEL_DTYPE,
        }
        if DEVICE == "cuda":
            # Use flash-attn only when actually importable; otherwise PyTorch
            # SDPA. Forcing flash_attention_2 without the package crashes the
            # model load ("FlashAttention2 has been toggled on ...").
            try:
                import flash_attn  # noqa: F401
                kwargs["attn_implementation"] = "flash_attention_2"
            except Exception:
                kwargs["attn_implementation"] = "sdpa"
        try:
            _loaded[mode] = Qwen3TTSModel.from_pretrained(MODELS[mode], **kwargs)
        except Exception as e:
            # Last resort: attention-related rejection -> retry with default.
            if "attention" in str(e).lower() or "attn" in str(e).lower():
                kwargs.pop("attn_implementation", None)
                _loaded[mode] = Qwen3TTSModel.from_pretrained(MODELS[mode], **kwargs)
            else:
                raise
        _loaded_dev[mode] = dev
        print(f"{mode} model ready")
    return _loaded[mode]


_asr = None
_asr_lock = threading.Lock()


def get_asr():
    """Tiny Whisper for transcribing clone references; loaded on first use."""
    global _asr
    if WhisperModel is None:
        return None
    with _asr_lock:
        if _asr is None:
            print("Loading whisper-tiny for reference transcription ...")
            _asr = WhisperModel("tiny",
                                device="cuda" if DEVICE == "cuda" else "cpu",
                                compute_type="float16" if DEVICE == "cuda" else "int8")
        return _asr


def transcribe_ref(path: str) -> str:
    """ASR transcript of the reference clip; '' when ASR is unavailable."""
    try:
        asr = get_asr()
        if asr is None:
            return ""
        # VAD skips pre-roll silence; hallucination_silence_threshold drops
        # silent stretches whisper would otherwise fill with invented words.
        segments, _info = asr.transcribe(
            path, language="en", beam_size=1, vad_filter=True,
            vad_parameters={
                "threshold": 0.5,
                "neg_threshold": 0.35,
                "min_speech_duration_ms": 100,
                "min_silence_duration_ms": 300,
                "speech_pad_ms": 50,
            },
            condition_on_previous_text=False,
            hallucination_silence_threshold=0.5)
        return " ".join(s.text.strip() for s in segments).strip()
    except Exception as e:
        print(f"reference ASR failed, falling back to client ref_text: {e}")
        return ""


print("Qwen3-TTS server starting (models load on first use) ...")

# ------------------------------------------------------------- helpers ---
SENT_SPLIT = re.compile(r"(?<=[.!?])\s+")


def extract_vo(text: str) -> str:
    if VO_MARKER not in text:
        return text.strip()
    parts = text.split(VO_MARKER)
    return " ".join(p.strip() for p in parts[1:] if p.strip())


CONTRACTIONS = {
    "he's": "he is", "she's": "she is", "it's": "it is", "that's": "that is",
    "what's": "what is", "there's": "there is", "here's": "here is",
    "who's": "who is", "where's": "where is", "how's": "how is",
}
_CONTR_RE = re.compile(r"\b(" + "|".join(CONTRACTIONS) + r")\b", re.IGNORECASE)


def _expand_contraction(m):
    out = CONTRACTIONS[m.group(1).lower()]
    return out.capitalize() if m.group(1)[0].isupper() else out


_ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
         "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
         "seventeen", "eighteen", "nineteen"]
_TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]
_SCALES = [(10 ** 9, "billion"), (10 ** 6, "million"), (1000, "thousand")]
_YEAR_CTX = re.compile(r"\b(in|since|from|until|by|before|after|year|circa)\s+(1[1-9]\d\d|20\d\d)\b", re.I)
_MONEY = re.compile(r"\$(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)(\s+(?:thousand|million|billion))?", re.I)
_PERCENT = re.compile(r"\b(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)\s*%")
_NUMBER = re.compile(r"\b(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?\b")
_TEMP = re.compile(r"\b(\d+)\s*(?:°\s*([CF])|degrees?\s+([CF]))\b", re.I)
_PHONE = re.compile(r"\b(\d{3})[\s.\-](\d{3})[\s.\-](\d{3,4})\b")
_ABBR = [
    (re.compile(r"\bDr\."), "Doctor"),
    (re.compile(r"\bMr\."), "Mister"),
    (re.compile(r"\bMrs\."), "Missus"),
    (re.compile(r"\bMs\."), "Miz"),
    (re.compile(r"\bvs\.?(?=\s)", re.I), "versus"),
    (re.compile(r"\be\.g\.", re.I), "for example"),
    (re.compile(r"\bi\.e\.", re.I), "that is"),
]


_ORD_IRREG = {"one": "first", "two": "second", "three": "third", "five": "fifth",
              "eight": "eighth", "nine": "ninth", "twelve": "twelfth"}
_ORDINAL = re.compile(r"\b(\d+)(?:st|nd|rd|th)\b", re.I)
_TIME = re.compile(r"\b(\d{1,2}):(\d{2})(?:\s*([ap])m\b)?", re.I)


def _ord_words(n: int) -> str:
    w = _int_words(n).split(" ")
    last = w[-1]
    if last in _ORD_IRREG:
        w[-1] = _ORD_IRREG[last]
    elif last.endswith("y"):
        w[-1] = last[:-1] + "ieth"
    else:
        w[-1] = last + "th"
    return " ".join(w)


def _time_words(m) -> str:
    h, mi = int(m.group(1)), int(m.group(2))
    if h > 23 or mi > 59:
        return m.group(0)
    out = _int_words(h)
    if mi == 0:
        if not m.group(3):
            out += " o'clock"
    elif mi < 10:
        out += " oh " + _int_words(mi)
    else:
        out += " " + _int_words(mi)
    if m.group(3):
        out += " " + m.group(3).upper() + " M"
    return out


def _int_words(n: int) -> str:
    if n < 20:
        return _ONES[n]
    if n < 100:
        t, o = divmod(n, 10)
        return _TENS[t] + (" " + _ONES[o] if o else "")
    if n < 1000:
        h, r = divmod(n, 100)
        return _ONES[h] + " hundred" + (" " + _int_words(r) if r else "")
    for v, name in _SCALES:
        if n >= v:
            q, r = divmod(n, v)
            return _int_words(q) + " " + name + (" " + _int_words(r) if r else "")
    return str(n)


def _num_words(s: str) -> str:
    s = s.replace(",", "")
    if "." in s:
        a, b = s.split(".", 1)
        return _int_words(int(a or 0)) + " point " + " ".join(_ONES[int(d)] for d in b)
    return _int_words(int(s))


def _year_words(y: int) -> str:
    if 2000 <= y <= 2009:
        return _int_words(y)
    hi, lo = divmod(y, 100)
    if lo == 0:
        return _int_words(hi) + " hundred"
    if lo < 10:
        return _int_words(hi) + " oh " + _int_words(lo)
    return _int_words(hi) + " " + _int_words(lo)


def _temp_words(m) -> str:
    unit = "Celsius" if (m.group(2) or m.group(3)).upper() == "C" else "Fahrenheit"
    return f"{_num_words(m.group(1))} degrees {unit}"


def _phone_words(m) -> str:
    groups = (m.group(1), m.group(2), m.group(3))
    return ", ".join(" ".join(_ONES[int(d)] for d in g) for g in groups)


def _money(m) -> str:
    amt, scale = m.group(1), (m.group(2) or "").strip()
    if scale:
        return f"{_num_words(amt)} {scale} dollars"
    if "." in amt:
        whole, frac = amt.replace(",", "").split(".", 1)
        if len(frac) == 2:
            w = int(whole)
            out = f"{_int_words(w)} {'dollar' if w == 1 else 'dollars'}"
            c = int(frac)
            if c:
                out += f" and {_int_words(c)} {'cent' if c == 1 else 'cents'}"
            return out
        return _num_words(amt) + " dollars"
    n = int(amt.replace(",", ""))
    return f"{_int_words(n)} {'dollar' if n == 1 else 'dollars'}"


def _expand_numbers(text: str) -> str:
    text = _PHONE.sub(_phone_words, text)
    text = _TEMP.sub(_temp_words, text)
    text = _TIME.sub(_time_words, text)
    text = _ORDINAL.sub(lambda m: _ord_words(int(m.group(1))), text)
    text = _YEAR_CTX.sub(lambda m: f"{m.group(1)} {_year_words(int(m.group(2)))}", text)
    text = _MONEY.sub(_money, text)
    text = _PERCENT.sub(lambda m: _num_words(m.group(1)) + " percent", text)
    return _NUMBER.sub(lambda m: _num_words(m.group(0)), text)


def normalize_text(text: str) -> str:
    """Clean text for TTS: the model does no normalization of its own."""
    text = text.replace("\u2019", "'").replace("\u2018", "'")
    text = text.replace("\u201c", '"').replace("\u201d", '"')
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    lines = [ln if ln[-1] in ".!?:;," else ln + "." for ln in lines]
    text = " ".join(lines)
    text = _CONTR_RE.sub(_expand_contraction, text)
    for rx, rep in _ABBR:
        text = rx.sub(rep, text)
    text = re.sub(r"\s*&\s*", " and ", text)
    text = _expand_numbers(text)
    text = re.sub(r"\s*[\u2014\u2013]\s*", ", ", text)
    text = text.replace("\u2026", ".")
    text = re.sub(r"[*#`_~^|<>\[\]{}]", " ", text)
    text = re.sub(r",\s*,", ",", text)
    text = re.sub(r"\.{2,}", ".", text)
    text = re.sub(r"\s+", " ", text)
    return re.sub(r"\s+([,.;:!?])", r"\1", text).strip()


def _split_long(s: str, max_chars: int):
    """Split an over-long sentence at commas, semicolons, or colons."""
    if len(s) <= max_chars:
        return [s]
    out, cur = [], ""
    for p in re.split(r"(?<=[,;:])\s+", s):
        if cur and len(cur) + len(p) + 1 > max_chars:
            out.append(cur)
            cur = p
        else:
            cur = f"{cur} {p}".strip()
    if cur:
        out.append(cur)
    return out


def chunk_text(text: str, max_chars: int = MAX_CHUNK_CHARS):
    text = normalize_text(text)
    sentences = [x for s in SENT_SPLIT.split(text) if s for x in _split_long(s, max_chars)]
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


def generate_one(model, mode, text, language, speaker, instruct, ref_audio, ref_text, design_prompt, speed=1.0):
    language = language or "Auto"

    if mode == "custom":
        kwargs = {"text": text, "language": language, "speaker": speaker or "Ryan", **GEN_KWARGS}
        if instruct and instruct.strip():
            kwargs["instruct"] = instruct.strip()
        if speed and abs(speed - 1.0) > 1e-6:
            kwargs["speed"] = speed
        try:
            wavs, sr = model.generate_custom_voice(**kwargs)
        except TypeError:
            # qwen-tts build without the speed kwarg: retry without it.
            kwargs.pop("speed", None)
            wavs, sr = model.generate_custom_voice(**kwargs)

    elif mode == "clone":
        if not ref_audio or not os.path.isfile(ref_audio):
            raise ValueError("ref_audio is required for clone mode")
        if not CLONE_X_VECTOR and (not ref_text or not ref_text.strip()):
            raise ValueError("ref_text is required for clone mode")
        kwargs = {
            "text": text,
            "language": language,
            "ref_audio": ref_audio,
            **GEN_KWARGS,
        }
        if CLONE_X_VECTOR:
            # Speaker embedding only: no ICL reference-text conditioning,
            # therefore no reference-tail leakage path at all.
            kwargs["ref_text"] = None
            kwargs["x_vector_only_mode"] = True
        else:
            kwargs["ref_text"] = ref_text.strip()
        try:
            wavs, sr = model.generate_voice_clone(**kwargs)
        except TypeError:
            # qwen-tts build without the newer sampling kwargs: retry lean.
            for key in ("top_p", "top_k", "repetition_penalty"):
                kwargs.pop(key, None)
            wavs, sr = model.generate_voice_clone(**kwargs)

    elif mode == "design":
        if not design_prompt or not design_prompt.strip():
            raise ValueError("design_prompt is required for design mode")
        # VoiceDesign API (natural language description)
        wavs, sr = model.generate_voice_design(
            text=text,
            language=language,
            instruct=design_prompt.strip(),
            **GEN_KWARGS,
        )
    else:
        raise ValueError(f"Unknown mode: {mode}")

    audio = torch.from_numpy(wavs[0]).float()
    return audio, sr


_LEAK_MAX_WORDS = 8
_LEAK_PROBE_SECONDS = 3.0


def _norm_words(s):
    return re.sub(r"[^a-z0-9 ]", " ", (s or "").lower()).split()


def trim_ref_leak(audio, sr, ref_text):
    """Cut a spoken reference-tail leak off the head of a generated chunk.

    Qwen3-TTS ICL cloning can continue the reference content before speaking
    the target (upstream issue #341, still unfixed): the output opens with the
    last words of the reference transcript. Detect it by ASR-ing the first few
    seconds of the chunk with word timestamps and matching that head against
    the tail words of ref_text; cut at the end of the matched run.
    """
    asr = get_asr()
    if asr is None or not ref_text or audio.numel() < int(sr * 0.5):
        return audio
    tail = _norm_words(ref_text)
    if len(tail) < 2:
        return audio
    probe = audio[: int(sr * _LEAK_PROBE_SECONDS)]
    try:
        segments, _info = asr.transcribe(
            probe.cpu().numpy().astype(np.float32), language="en", beam_size=1,
            vad_filter=False, condition_on_previous_text=False,
            word_timestamps=True)
        head, ends = [], []
        for seg in segments:
            for w in (seg.words or []):
                toks = _norm_words(w.word)
                head.extend(toks)
                ends.extend([w.end] * len(toks))
    except Exception as e:
        print(f"leak probe failed, keeping chunk head: {e}")
        return audio
    cut_k = 0
    for k in range(min(_LEAK_MAX_WORDS, len(tail)), 1, -1):
        if head[:k] == tail[-k:]:
            cut_k = k
            break
    if not cut_k or cut_k >= len(ends):
        return audio
    cut = int(ends[cut_k - 1] * sr) + int(0.06 * sr)
    if cut >= audio.numel():
        return audio
    print(f"trimmed {cut_k}-word reference leak ({cut / sr:.2f}s) from chunk head")
    return audio[cut:]


def _trim_silence(audio, sr, thresh_db=-45.0, margin_ms=30.0):
    """Trim leading/trailing near-silence from a 1-D float tensor, keeping a small margin."""
    if audio.numel() == 0:
        return audio
    thresh = 10 ** (thresh_db / 20.0)
    nz = torch.nonzero(audio.abs() > thresh, as_tuple=False).flatten()
    if nz.numel() == 0:
        return audio
    margin = int(sr * margin_ms / 1000.0)
    start = max(0, int(nz[0].item()) - margin)
    end = min(int(audio.numel()), int(nz[-1].item()) + margin)
    return audio[start:end]


def _edge_fade(audio, sr, fade_ms=25.0):
    """Short linear fade-in/out so trimmed chunk edges don't click."""
    n = int(sr * fade_ms / 1000.0)
    if n <= 0 or audio.numel() < 2 * n:
        return audio
    out = audio.clone()
    ramp = torch.linspace(0.0, 1.0, n, dtype=audio.dtype, device=audio.device)
    out[:n] = out[:n] * ramp
    out[-n:] = out[-n:] * ramp.flip(0)
    return out


def _pause_for(chunk_text, sr, default_ms=150.0):
    """Inter-chunk pause length driven by the chunk's final punctuation."""
    t = (chunk_text or "").rstrip()
    if t and t[-1] in ".!?\u2026":
        ms = 350.0
    elif t and t[-1] in ",;:":
        ms = 180.0
    else:
        ms = default_ms
    return int(sr * ms / 1000.0)


_dev_locks = {}
_dev_locks_guard = threading.Lock()


def _dev_lock(dev: str):
    with _dev_locks_guard:
        return _dev_locks.setdefault(dev, threading.RLock())


def synthesize(mode, text, language, speaker, instruct, ref_audio, ref_text, design_prompt, job_id, speed=1.0, loudness=1.0):
    """Serialize jobs per GPU so loads and evictions never overlap a running job."""
    m = (mode or "custom").lower().strip()
    if m not in MODELS:
        m = "custom"
    with _dev_lock(_device_for(m)):
        return _synthesize_impl(mode, text, language, speaker, instruct, ref_audio, ref_text, design_prompt, job_id, speed, loudness)


def _synthesize_impl(mode, text, language, speaker, instruct, ref_audio, ref_text, design_prompt, job_id, speed=1.0, loudness=1.0):
    model = get_model(mode)
    vo_text = extract_vo(text)
    chunks = chunk_text(vo_text)

    pieces = []
    sr = 24000
    for i, chunk in enumerate(chunks):
        print(f"[{job_id}] {mode} chunk {i+1}/{len(chunks)}: {chunk[:55]}...")
        wav, sr = generate_one(
            model, mode, chunk, language, speaker, instruct,
            ref_audio, ref_text, design_prompt, speed
        )
        # Trim the model's own leading/trailing silence so we own the seam.
        wav = _trim_silence(wav, sr)
        # Leak trim net retired: x-vector-only cloning removes the ICL
        # reference-text path that leaked (#341), and the trimmer's word
        # timestamp cuts ate legitimate first words of the target.
        # Short fade kills clicks at trimmed boundaries.
        wav = _edge_fade(wav, sr, fade_ms=25.0)
        pieces.append(wav)
        if i < len(chunks) - 1:
            pause = _pause_for(chunk, sr)
            if pause > 0:
                pieces.append(torch.zeros(pause, dtype=wav.dtype))

    full = torch.cat(pieces, dim=0).unsqueeze(0)
    if loudness and abs(loudness - 1.0) > 1e-6:
        full = (full * loudness).clamp(-1.0, 1.0)
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
        gpus=torch.cuda.device_count() if DEVICE == "cuda" else 0,
    )


@app.errorhandler(400)
def _bad_request(e):
    desc = getattr(e, "description", str(e))
    print(f"400 {request.method} {request.path} ctype={request.content_type!r} len={request.content_length} desc={desc}")
    return jsonify(error=str(desc)), 400


@app.route("/generate", methods=["POST"])
def generate():
    print(f"/generate ctype={request.content_type!r} form={list(request.form.keys())} files={list(request.files.keys())} json={request.is_json}")
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

    def _num(name, lo, hi, default):
        try:
            v = float(request.form.get(name, default))
        except (TypeError, ValueError):
            v = default
        return min(max(v, lo), hi)

    speed = _num("speed", 0.5, 2.0, 1.0)
    loudness = _num("loudness", 0.3, 2.5, 1.0)

    # Save reference audio for clone mode
    ref_path = None
    if mode == "clone" and "ref_audio" in request.files and request.files["ref_audio"].filename:
        ref_path = os.path.join(OUT_DIR, f"ref_{uuid.uuid4().hex[:8]}.wav")
        request.files["ref_audio"].save(ref_path)
        # Normalize the reference: decode via soundfile, force mono, rewrite as
        # WAV. Never cut it: the transcript is derived from the whole clip
        # below, and a chopped clip would transcribe chopped speech.
        try:
            try:
                data, sr = sf.read(ref_path, always_2d=True)
            except Exception:
                # m4a/aac: libsndfile can't open it, so convert with ffmpeg
                tmp_in = ref_path + ".in"
                os.replace(ref_path, tmp_in)
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp_in, "-ac", "1", "-ar", "24000", ref_path], check=True, capture_output=True)
                os.remove(tmp_in)
                data, sr = sf.read(ref_path, always_2d=True)
            if data.shape[1] > 1:
                data = data[:, :1]
            if sr != 24000:
                # Match the vocoder's native rate: a reference clip at a
                # foreign rate smears dense sibilant clusters during cloning.
                ref_t = torch.from_numpy(np.ascontiguousarray(data.T)).float()
                ref_t = torchaudio.functional.resample(ref_t, sr, 24000)
                data = ref_t.numpy().T
                sr = 24000
            if len(data) > int(sr * MAX_REF_SECONDS):
                return jsonify(error=f"reference audio is longer than {MAX_REF_SECONDS}s: trim the clip and record again"), 400
            sf.write(ref_path, data, sr)
        except Exception as e:
            return jsonify(error=f"cannot read reference audio ({e})"), 400
        # Derive ref_text from the clip itself: a client transcript that runs
        # past the recorded audio makes the model speak the uncovered tail
        # ("...easy to follow") into the first generated chunk.
        asr_text = transcribe_ref(ref_path)
        if asr_text:
            print(f"ref_text from ASR ({len(asr_text.split())} words): {asr_text[:80]}")
            ref_text = asr_text

    job_id = uuid.uuid4().hex[:12]
    JOBS[job_id] = {"status": "processing", "path": None, "duration": None, "error": None}

    def worker():
        try:
            path, duration = synthesize(
                mode, text, language, speaker, instruct,
                ref_path, ref_text, design_prompt, job_id, speed, loudness
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
