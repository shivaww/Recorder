#!/usr/bin/env bash
# ============================================================================
#  NEXON STUDIO - BREEZE2-TTS VOICE SERVER ONE-COMMAND LAUNCHER (Kaggle GPU)
#
#  Paste ONE line into a Kaggle cell (Accelerator: GPU required):
#    !rm -rf /kaggle/working/V && git clone --depth 1 https://github.com/shivaww/Recorder.git /kaggle/working/V && cd /kaggle/working/V/breeze2tts && bash run.sh
#
#  Safe to re-run: kills the previous server/tunnel and starts fresh.
#    1. Clones breezeblue-ai/breeze-tts + installs its requirements
#       (upstream forces eager attention - no flash-attn build, like qwen3tts)
#    2. Downloads the BreezeBlue/breeze-tts-2 checkpoint (~7 GB; cached, re-runs skip it)
#    3. Frees port 5001 (own port - a qwen3tts server on port 5000 is never touched)
#    4. Starts the Breeze2-TTS voice server (model loads on first request)
#    5. Starts a Cloudflare tunnel, prints the BASE URL + heartbeat
#
#  T4 note: upstream loads the model in bfloat16 (hard-coded in
#  breeze_infer/runtime.py). T4 (sm_75) has no native bf16 - PyTorch emulates
#  it, which usually works. If the model load hard-fails on T4, patch the
#  dtype and re-run this script:
#    sed -i 's/dtype=torch.bfloat16/dtype=torch.float16/' /kaggle/working/breeze-tts/breeze_infer/runtime.py
# ============================================================================

PORT=5001
WORK="/kaggle/working"
REPO="$WORK/breeze-tts"      # upstream code: breeze_infer + models packages
CKPT="$WORK/breeze-tts-2"    # BreezeBlue/breeze-tts-2 checkpoint (~7 GB)
CF_PATH="$WORK/cloudflared"
TUNNEL_LOG="$WORK/breeze_tunnel.log"
SERVER_PID=""
TUNNEL_PID=""

step() { printf '\n%s\n  %s\n%s\n' "$(printf '=%.0s' {1..50})" "$1" "$(printf '=%.0s' {1..50})"; }
note() { printf '   | %s\n' "$1"; }

cleanup() {
  [ -n "$TUNNEL_PID" ] && kill "$TUNNEL_PID" 2>/dev/null
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
}
trap cleanup EXIT INT TERM

step "STEP 1/6: Installing dependencies"
if [ ! -d "$REPO" ]; then
  git clone --depth 1 https://github.com/breezeblue-ai/breeze-tts "$REPO" \
    || { echo "  FATAL: breeze-tts clone failed - check the repo URL."; exit 1; }
fi
python -m pip install -q -r "$REPO/requirements.txt" \
  || echo "  WARNING: some pinned requirements failed - continuing"
# Kaggle preinstalls torchvision 0.25+cu128 (built for its torch 2.10); the
# torch 2.9.1 pin above leaves that torchvision broken (its compiled nms
# operator no longer exists), and transformers imports torchvision at module
# load. Install the build matching torch 2.9.1.
python -m pip install -q torchvision==0.24.1 \
  || echo "  WARNING: torchvision pin failed"
python -m pip install -q flask flask-cors
if ! python -c "import flask, flask_cors, soundfile, transformers, torchvision" 2>/dev/null; then
  echo "  FATAL: pip install failed - re-run the cell."
  exit 1
fi
note "breeze-tts requirements + flask ready"
if ! command -v ffmpeg >/dev/null 2>&1; then
  note "ffmpeg missing - installing (converts m4a/aac voice samples)"
  apt-get update -qq >/dev/null 2>&1
  apt-get install -y -qq ffmpeg >/dev/null 2>&1
fi
if command -v ffmpeg >/dev/null 2>&1; then
  note "ffmpeg ready"
else
  note "WARNING: ffmpeg install failed - only WAV/FLAC/OGG voice samples will work"
fi
if python -c "import torch, sys; sys.exit(0 if torch.cuda.is_available() else 1)"; then
  note "GPU: $(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -n 1)"
else
  note "WARNING: no CUDA GPU - generation will be very slow on CPU"
fi

step "STEP 2/6: Downloading the Breeze2 checkpoint (~7 GB, cached)"
python - <<'PYEOF' || { echo "  FATAL: checkpoint download failed."; exit 1; }
from huggingface_hub import snapshot_download
snapshot_download(repo_id="BreezeBlue/breeze-tts-2",
                  local_dir="/kaggle/working/breeze-tts-2")
print("checkpoint ready")
PYEOF
note "checkpoint at $CKPT"

step "STEP 3/6: Freeing port $PORT (breeze owns it; a qwen server on 5000 is untouched)"
fuser -k "${PORT}/tcp" 2>/dev/null
pkill -f cloudflared 2>/dev/null
sleep 2
note "port $PORT free"

step "STEP 4/6: Downloading cloudflared"
if [ ! -x "$CF_PATH" ]; then
  wget -q "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64" -O "$CF_PATH" \
    || { echo "  FATAL: cloudflared download failed."; exit 1; }
fi
chmod +x "$CF_PATH"
note "cloudflared ready"

step "STEP 5/6: Starting breeze voice server + tunnel"
BREEZE_REPO="$REPO" BREEZE_CKPT="$CKPT" TTS_NO_TUNNEL=1 PORT=$PORT \
  PYTHONPATH="$REPO" python -u server.py &
SERVER_PID=$!
sleep 15
if ! kill -0 "$SERVER_PID" 2>/dev/null; then
  echo "  FATAL: server crashed - scroll up for the traceback."
  exit 1
fi
note "breeze voice server up (Breeze2 model loads on first request)"
"$CF_PATH" tunnel --url "http://localhost:$PORT" > "$TUNNEL_LOG" 2>&1 &
TUNNEL_PID=$!

step "STEP 6/6: Waiting for public URL"
BASE_URL=""
for _ in $(seq 1 60); do
  BASE_URL=$(grep -o 'https://[a-zA-Z0-9-]*\.trycloudflare\.com' "$TUNNEL_LOG" 2>/dev/null | head -n 1)
  [ -n "$BASE_URL" ] && break
  sleep 1
done
if [ -z "$BASE_URL" ]; then
  echo "  Failed to get tunnel URL. Last tunnel log lines:"
  tail -n 5 "$TUNNEL_LOG"
  exit 1
fi

echo ""
echo "============================================================"
echo "  READY - VOICE SERVER (BREEZE2-TTS)"
echo "  Paste this BASE URL into Nexon Studio -> Voice studio:"
echo ""
echo "  BASE URL : $BASE_URL"
echo "============================================================"
echo ""
echo "  Server running - keep this cell alive."
echo "  Health check: $BASE_URL/health"
echo ""
echo "  License: Breeze2 weights are research / non-commercial; code Apache-2.0."
echo ""

START=$(date +%s)
LAST=0
while kill -0 "$SERVER_PID" 2>/dev/null && kill -0 "$TUNNEL_PID" 2>/dev/null; do
  sleep 2
  NOW=$(date +%s)
  if [ $((NOW - LAST)) -ge 30 ]; then
    UP=$((NOW - START))
    echo "  [alive] $(date '+%H:%M:%S') - uptime $((UP / 60))m$((UP % 60))s - URL still: $BASE_URL"
    LAST=$NOW
  fi
done
