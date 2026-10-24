# test_media.py: offline check of kaggle/media.py and kaggle/sfx.py (needs ffmpeg + numpy).
# Run: python3 checks/test_media.py < /dev/null
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
import types
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'kaggle'))
cfg = types.ModuleType('config')
cfg.SAMPLE_RATE, cfg.MASTER_GAIN = 44100, 0.45
sys.modules['config'] = cfg

import media  # noqa: E402
import sfx  # noqa: E402


def ff(*args):
    subprocess.run(['ffmpeg', '-nostdin', '-y', '-loglevel', 'error'] + list(args),
                   check=True, timeout=120)


def check(name, ok):
    print(('PASS ' if ok else 'FAIL ') + name, flush=True)
    if not ok:
        sys.exit(1)


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


tmp = tempfile.mkdtemp()
ff('-f', 'lavfi', '-i', 'testsrc=duration=1:size=320x180:rate=30', '-c:v', 'mpeg4', tmp + '/v.mp4')
ff('-f', 'lavfi', '-i', 'sine=frequency=880:duration=0.5', '-ar', '48000', '-c:a', 'aac', tmp + '/s.m4a')
srv = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Quiet, directory=tmp))
threading.Thread(target=srv.serve_forever, daemon=True).start()
base = 'http://127.0.0.1:%d' % srv.server_address[1]

events = [{'t': 0.2, 'url': base + '/s.m4a', 'gain': 0.8}, {'t': 1, 'url': base + '/nope.mp3'}]
html = ("<video id='a' src='%s/v.mp4' autoplay loop data-loop></video>"
        "<video src='%s/nope.mp4'></video>"
        "<script type='application/json' id='sfx'>%s</script>"
        ) % (base, base, json.dumps({'events': events}))
out = tmp + '/assets'
os.makedirs(out)
stats = {}
res = media.process_media(html, out, stats)

m = re.search(r'src=.assets/([0-9a-f]{12})[.]webm', res)
check('video rewritten to webm', bool(m))
ff('-i', out + '/' + m.group(1) + '.webm', '-f', 'null', '-')
check('webm decodes', True)
check('autoplay/loop dropped, data-loop kept', ' autoplay' not in res and ' loop' not in res and 'data-loop' in res)
check('muted/playsinline/preload added', 'muted playsinline preload=' in res)
check('failed video url left as-is', base + '/nope.mp4' in res)
blk = json.loads(media.SFX_BLOCK.search(res).group(2))
u0, u1 = blk['events'][0]['url'], blk['events'][1]['url']
check('sfx url rewritten', re.fullmatch(r'assets/[0-9a-f]{12}[.]wav', u0) is not None)
check('failed sfx url kept', u1.startswith('http'))
check('stats', (stats['video_found'], stats['video_ok'], stats['sfx_found'], stats['sfx_ok'], len(stats['failed'])) == (2, 1, 2, 1, 2))
with wave.open(tmp + '/' + u0, 'rb') as w:
    check('wav is 44.1k mono s16', (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (44100, 1, 2))

ev = [{'t': 0.2, 'url': u0, 'gain': 0.8}, {'t': 0.1, 'id': 'slam', 'gain': 1},
      {'t': 0.5, 'url': '../../etc/passwd'}, {'t': 0.5, 'url': 'assets/missing.wav'},
      {'t': None, 'url': u0}]
sfx.mix_audio(ev, None, 2, tmp + '/mix.wav', tmp)
with wave.open(tmp + '/mix.wav', 'rb') as w:
    n = w.getnframes()
    a = np.frombuffer(w.readframes(n), dtype=np.int16)
sr = 44100
check('mix length', n == 2 * sr + sr // 4)
check('clip audible at 0.2s', np.abs(a[int(0.25 * sr):int(0.65 * sr)]).max() > 300)
check('silent before clip (id events ignored)', np.abs(a[:int(0.19 * sr)]).max() == 0)
check('silent after clip', np.abs(a[int(0.9 * sr):]).max() == 0)
srv.shutdown()
shutil.rmtree(tmp, ignore_errors=True)
print('ALL PASS')
