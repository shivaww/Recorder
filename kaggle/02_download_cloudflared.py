import os
import sys
import urllib.request
from pathlib import Path
import stat

VERSION = "2026.8.2"
FILENAME = "cloudflared-linux-amd64"
URL = f"https://github.com/cloudflare/cloudflared/releases/download/{VERSION}/{FILENAME}"

DEST_DIR = Path("/kaggle/working")
BINARY_PATH = DEST_DIR / "cloudflared"

def download_with_progress(url: str, dest: Path):
    print(f"Downloading: {url}")
    print(f"Saving to  : {dest}")
    dest.parent.mkdir(parents=True, exist_ok=True)

    def reporthook(block_num, block_size, total_size):
        downloaded = block_num * block_size
        if total_size > 0:
            percent = min(100, downloaded * 100 // total_size)
            mb = downloaded / (1024 * 1024)
            total_mb = total_size / (1024 * 1024)
            sys.stdout.write(f"\r[{percent:3d}%] {mb:.1f} / {total_mb:.1f} MB")
            sys.stdout.flush()
        else:
            sys.stdout.write(f"\rDownloaded: {downloaded / (1024*1024):.1f} MB")
            sys.stdout.flush()

    urllib.request.urlretrieve(url, dest, reporthook)
    print("\nDownload complete.")

def make_executable(path: Path):
    path.chmod(path.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    print(f"Made executable: {path}")

def main():
    print("=" * 60)
    print("Downloading cloudflared for Cloudflare Quick Tunnel")
    print("=" * 60)

    if BINARY_PATH.exists():
        print(f"Binary already exists: {BINARY_PATH}")
        make_executable(BINARY_PATH)
    else:
        download_with_progress(URL, BINARY_PATH)
        make_executable(BINARY_PATH)

    print("\nVerifying binary...")
    try:
        result = os.popen(f'"{BINARY_PATH}" --version').read().strip()
        print(f"Version: {result}")
    except Exception as e:
        print(f"Could not run version check: {e}")

    print("\n" + "=" * 60)
    print("Ready!")
    print(f"Binary location: {BINARY_PATH}")
    print("=" * 60)

if __name__ == "__main__":
    main()
