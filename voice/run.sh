#!/usr/bin/env bash
# ============================================================================
#  NEXON STUDIO — VOICE SERVER ONE-COMMAND LAUNCHER (Kaggle GPU notebook)
#
#  Paste ONE line into a Kaggle cell (Accelerator: GPU required):
#    !rm -rf /kaggle/working/V && git clone --depth 1 https://github.com/shivaww/Recorder.git /kaggle/working/V && cd /kaggle/working/V/voice && bash run.sh
#
#  Safe to re-run: kills the previous server/tunnel and starts fresh.
#    1. Installs dependencies (flask, flask-cors, soundfile, qwen-tts)
#    2. Frees port 5000
#    3. Starts the Qwen3-TTS voice server (models load on first request)
#    4. Starts a Cloudflare tunnel
#    5. Prints the BASE URL for the Nexon Studio app + heartbeat
# ============================================================================

PORT=5000
WORK="/kaggle/working"
CF_PATH="$WORK/cloudflared"
TUNNEL_LOG="$WORK/tts_tunnel.log"
SERVER_PID=""
TUNNEL_PID=""

step() { printf '\n%s\n  %s\n%s\n' "$(printf '=%.0s' {1..50})" "$1" "$(printf '=%.0s' {1..50})"; }
note() { printf '   | %s\n' "$1"; }

cleanup() {
  [ -n "$TUNNEL_PID" ] && kill "$TUNNEL_PID" 2>/dev/null
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
}
trap cleanup EXIT INT TERM

step "STEP 1/5: Installing dependencies"
python -m pip install -q flask flask-cors soundfile qwen-tts
if ! python -c "import flask, flask_cors, soundfile, qwen_tts" 2>/dev/null; then
  echo "  FATAL: pip install failed - re-run the cell."
  exit 1
fi
note "flask, flask-cors, soundfile, qwen-tts ready"
if python -c "import torch, sys; sys.exit(0 if torch.cuda.is_available() else 1)"; then
  note "GPU: $(nvidia-smi --query-gpu=name --format=csv,noheader 2>/dev/null | head -n 1)"
else
  note "WARNING: no CUDA GPU - generation will be very slow on CPU"
fi
python -m pip install -q flash-attn --no-build-isolation 2>/dev/null \
  && note "flash-attn installed" \
  || note "flash-attn skipped (optional)"

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
TTS_NO_TUNNEL=1 PORT=$PORT python -u server.py &
SERVER_PID=$!
sleep 3
if ! kill -0 "$SERVER_PID" 2>/dev/null; then
  echo "  FATAL: server crashed - scroll up for the traceback."
  exit 1
fi
note "voice server up (Qwen3-TTS models load on first request)"
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
echo "  READY - VOICE SERVER (Qwen3-TTS)"
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
