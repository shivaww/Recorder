#!/usr/bin/env bash
# ============================================================================
#  VIBEVOICE VOICE SERVER LAUNCHER (Kaggle GPU notebook)
#
#  Long voiceovers in ONE continuous take (no chunk seams), cloned from your
#  reference clip. Same HTTP API as the Qwen3-TTS server.
#
#  Paste ONE line into a FRESH Kaggle notebook session (GPU on):
#    !rm -rf /kaggle/working/V && git clone --depth 1 https://github.com/shivaww/Recorder.git /kaggle/working/V && cd /kaggle/working/V/vibevoice && bash run.sh
#
#  Do NOT run it in the same session as qwen3tts/run.sh: VibeVoice pins
#  transformers==4.51.3, which can break the Qwen server's packages.
# ============================================================================

PORT=5000
WORK="/kaggle/working"
CF_PATH="$WORK/cloudflared"
TUNNEL_LOG="$WORK/vv_tunnel.log"
FORK="$WORK/VibeVoice-fork"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_PID=""
TUNNEL_PID=""

step() { printf '\n%s\n  %s\n%s\n' "$(printf '=%.0s' {1..50})" "$1" "$(printf '=%.0s' {1..50})"; }
note() { printf '   | %s\n' "$1"; }

cleanup() {
  [ -n "$TUNNEL_PID" ] && kill "$TUNNEL_PID" 2>/dev/null
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
}
trap cleanup EXIT INT TERM

step "STEP 1/5: Installing VibeVoice (community fork)"
python -m pip install -q flask flask-cors soundfile "transformers==4.51.3" "accelerate==1.6.0" diffusers librosa ml-collections absl-py av scipy numba peft
rm -rf "$FORK"
git clone -q --depth 1 https://github.com/vibevoice-community/VibeVoice.git "$FORK" || { echo "  FATAL: fork clone failed."; exit 1; }
python -m pip install -q --no-deps -e "$FORK"
if ! python -c "import flask, flask_cors, soundfile; from vibevoice.modular.modeling_vibevoice_inference import VibeVoiceForConditionalGenerationInference; from vibevoice.processor.vibevoice_processor import VibeVoiceProcessor"; then
  echo "  FATAL: VibeVoice import failed - scroll up and paste the traceback."
  exit 1
fi
note "VibeVoice imports OK"
if ! command -v ffmpeg >/dev/null 2>&1; then
  note "ffmpeg missing - installing (converts voice samples)"
  apt-get update -qq >/dev/null 2>&1
  apt-get install -y -qq ffmpeg >/dev/null 2>&1
fi
command -v ffmpeg >/dev/null 2>&1 && note "ffmpeg ready" || note "WARNING: ffmpeg missing - voice sample conversion will fail"
if python -c "import torch, sys; sys.exit(0 if torch.cuda.is_available() else 1)"; then
  note "GPU: $(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -n 1)"
else
  note "WARNING: no CUDA GPU - generation will be very slow on CPU"
fi

step "STEP 2/5: Freeing port $PORT"
fuser -k "${PORT}/tcp" 2>/dev/null
pkill -f cloudflared 2>/dev/null
pkill -f "server.py" 2>/dev/null
sleep 2
note "port $PORT free"

step "STEP 3/5: Downloading cloudflared"
if [ ! -x "$CF_PATH" ]; then
  wget -q "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64" -O "$CF_PATH" \
    || { echo "  FATAL: cloudflared download failed."; exit 1; }
fi
chmod +x "$CF_PATH"
note "cloudflared ready"

step "STEP 4/5: Starting voice server + tunnel"
cd "$HERE"
PORT=$PORT python -u server.py &
SERVER_PID=$!
sleep 3
if ! kill -0 "$SERVER_PID" 2>/dev/null; then
  echo "  FATAL: server crashed - scroll up for the traceback."
  exit 1
fi
note "voice server up (VibeVoice loads on the first request)"
"$CF_PATH" tunnel --url "http://localhost:$PORT" > "$TUNNEL_LOG" 2>&1 &
TUNNEL_PID=$!

step "STEP 5/5: Waiting for public URL"
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
echo "  READY - VOICE SERVER (VibeVoice, long single-pass)"
echo "  Paste this BASE URL into Nexon Studio -> Voice studio:"
echo ""
echo "  BASE URL : $BASE_URL"
echo "============================================================"
echo ""
echo "  Server running - keep this cell alive."
echo "  Health check: $BASE_URL/health"
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
echo "  Server or tunnel exited - re-run the cell to restart."
