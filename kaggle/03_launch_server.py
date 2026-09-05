import subprocess
import sys
import os
import time

PORT = 8000
CF_PATH = "/kaggle/working/cloudflared"
SERVER_SCRIPT = "/kaggle/working/server.py"

def main():
    if not os.path.exists(CF_PATH):
        print("Error: cloudflared not found. Run 02_download_cloudflared.py first.")
        return
    if not os.path.exists(SERVER_SCRIPT):
        print(f"Error: {SERVER_SCRIPT} not found. Make sure server.py is in /kaggle/working/")
        return

    print("Starting BrollRender Server...")
    # Start the HTTP server in the background
    server_proc = subprocess.Popen([sys.executable, SERVER_SCRIPT])
    time.sleep(2) # Give server a moment to boot

    print("Starting Cloudflare Tunnel...")
    # Start cloudflared and capture output to find the URL
    cf_proc = subprocess.Popen(
        [CF_PATH, "tunnel", "--url", f"http://localhost:{PORT}"],
        stderr=subprocess.PIPE,
        stdout=subprocess.PIPE,
        text=True
    )

    print("\n" + "=" * 60)
    print("WAITING FOR PUBLIC URL...")
    print("=" * 60 + "\n")

    try:
        while True:
            line = cf_proc.stderr.readline()
            if not line:
                break
            # Cloudflared prints the URL in its startup logs
            if "trycloudflare.com" in line:
                parts = line.split()
                for p in parts:
                    if p.startswith("https://"):
                        print("\n" + "=" * 60)
                        print(f"PUBLIC BASE URL: {p}")
                        print("=" * 60)
                        print("\nEnter this URL and your API key in the Android app.\n")
                        print("Server is running. Press Ctrl+C or stop the cell to quit.")
                        
            # Keep the process alive
            if cf_proc.poll() is not None or server_proc.poll() is not None:
                print("A process exited unexpectedly. Shutting down.")
                break
    except KeyboardInterrupt:
        print("\nShutting down...")
    finally:
        cf_proc.terminate()
        server_proc.terminate()

if __name__ == "__main__":
    main()
