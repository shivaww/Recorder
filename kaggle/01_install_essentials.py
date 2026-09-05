import subprocess
import os

print("=== Installing Playwright ===")
subprocess.run(["pip", "install", "-q", "playwright"], check=True)
subprocess.run(["playwright", "install", "--with-deps", "chromium"], check=True)

print("\n=== Downloading FFmpeg (NVENC) ===")
ffmpeg_url = "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-linux64-gpl.tar.xz"
subprocess.run(["wget", "-q", ffmpeg_url, "-O", "ffmpeg.tar.xz"], check=True)
subprocess.run(["tar", "-xf", "ffmpeg.tar.xz"], check=True)
subprocess.run(["cp", "ffmpeg-master-latest-linux64-gpl/bin/ff*", "/usr/local/bin/"], check=True)

print("\n=== Verifying NVENC ===")
result = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True)
if "h264_nvenc" in result.stdout:
    print("h264_nvenc is available!")
else:
    print("WARNING: h264_nvenc not found!")

print("\n=== Setup Complete ===")