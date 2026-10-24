# test_process_assets.py: assets.process_assets on a page with only <video> + #sfx urls.
# Run: python3 checks/test_process_assets.py < /dev/null   (needs ffmpeg)
import functools
import http.server
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'kaggle'))
import assets  # noqa: E402


def ff(*a):
    subprocess.run(['ffmpeg', '-nostdin', '-y', '-loglevel', 'error'] + list(a),
                   check=True, timeout=120)


def check(name, ok):
    print(('PASS ' if ok else 'FAIL ') + name, flush=True)
    if not ok:
        sys.exit(1)


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


tmp = tempfile.mkdtemp()
srv_dir, job = tmp + '/srv', tmp + '/job'
os.makedirs(srv_dir)
os.makedirs(job)
ff('-f', 'lavfi', '-i', 'testsrc=duration=1:size=320x180:rate=30', '-c:v', 'mpeg4', srv_dir + '/v.mp4')
ff('-f', 'lavfi', '-i', 'sine=frequency=660:duration=0.4', '-ar', '22050', '-c:a', 'pcm_s16le', srv_dir + '/s.wav')
srv = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Quiet, directory=srv_dir))
threading.Thread(target=srv.serve_forever, daemon=True).start()
base = 'http://127.0.0.1:%d' % srv.server_address[1]

html_path = job + '/page.html'
with open(html_path, 'w') as f:
    f.write('<video src="%s/v.mp4" data-start="1"></video>'
            '<script type="application/json" id="sfx">%s</script>'
            % (base, json.dumps({'events': [{'t': 1, 'url': base + '/s.wav', 'gain': 0.6}]})))

stats = assets.process_assets(html_path, job)
html = open(html_path).read()
print(stats, flush=True)
check('no img found, no early return', stats['found'] == 0)
check('video and sfx counted', (stats['video_ok'], stats['sfx_ok']) == (1, 1) and not stats['failed'])
check('html rewritten', 'assets/' in html and 'http://' not in html and 'data-start="1"' in html)
files = sorted(os.listdir(job + '/assets'))
print('assets on disk:', files, flush=True)
check('assets on disk: one webm, one wav', sorted(os.path.splitext(x)[1] for x in files) == ['.wav', '.webm'])
srv.shutdown()
shutil.rmtree(tmp, ignore_errors=True)
print('ALL PASS')
