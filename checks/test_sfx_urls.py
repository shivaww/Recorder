'''Local check for URL-based SFX (needs ffmpeg and numpy, no internet).

Serves a generated beep over localhost, runs assets.process_assets on an
HTML page with an #sfx block, then runs sfx.mix_audio on the rewritten events.
'''
import functools
import http.server
import json
import os
import subprocess
import sys
import tempfile
import threading
import types
import wave

import numpy as np

os.environ['no_proxy'] = '127.0.0.1'
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'kaggle'))
SR = 44100
cfg = types.ModuleType('config')  # stub: real config probes GPUs, makes /tmp dirs
cfg.SAMPLE_RATE = SR
cfg.MASTER_GAIN = 0.45
sys.modules['config'] = cfg

import assets  # noqa: E402
import sfx  # noqa: E402


class Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def read_wav(path):
    with wave.open(path, 'rb') as wf:
        return np.frombuffer(wf.readframes(wf.getnframes()), dtype=np.int16)


def main():
    tmp = tempfile.mkdtemp()
    srv = os.path.join(tmp, 'srv')
    job = os.path.join(tmp, 'job')
    os.makedirs(srv)
    os.makedirs(job)
    subprocess.run(['ffmpeg', '-nostdin', '-y', '-v', 'error', '-f', 'lavfi', '-i',
                    'sine=frequency=880:duration=0.5',
                    os.path.join(srv, 'beep.wav')], check=True,
                   stdin=subprocess.DEVNULL, timeout=30)
    httpd = http.server.HTTPServer(
        ('127.0.0.1', 0), functools.partial(Quiet, directory=srv))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = 'http://127.0.0.1:%d' % httpd.server_port

    events = [
        {'t': 0.5, 'url': base + '/beep.wav', 'gain': 0.8},
        {'t': 1.0, 'url': base + '/beep.wav'},
        {'t': 1.5, 'url': base + '/missing.wav'},
    ]
    html_path = os.path.join(job, 'index.html')
    head = '''<html><body><script type='application/json' id='sfx'>'''
    with open(html_path, 'w', encoding='utf-8') as f:
        f.write(head + json.dumps({'events': events}) + '</script></body></html>')

    stats = assets.process_assets(html_path, job)
    print(stats)
    assert stats['sfx_found'] == 3, stats
    assert stats['sfx_ok'] == 2, stats
    assert len(stats['sfx_failed']) == 1, stats

    with open(html_path, encoding='utf-8') as f:
        m = assets.SFX_BLOCK.search(f.read())
    out = json.loads(m.group(2))['events']
    assert out[0]['url'] == out[1]['url'], out
    assert out[0]['url'].startswith('assets/'), out
    assert os.path.isfile(os.path.join(job, out[0]['url'])), out
    assert out[2]['url'].startswith('http'), out
    assert out[0]['gain'] == 0.8 and out[0]['t'] == 0.5, out

    wav = os.path.join(job, 'audio.wav')
    assert sfx.mix_audio(out, None, 3.0, wav, job) is True
    pcm = read_wav(wav)
    assert len(pcm) >= 3 * SR, len(pcm)

    def peak(a, b):
        return int(np.abs(pcm[int(a * SR):int(b * SR)]).max())

    assert peak(0, 0.45) == 0, 'pre-roll not silent'
    assert peak(0.5, 1.0) > 500, 'first beep missing'
    assert peak(1.0, 1.5) > 500, 'second beep missing'
    assert peak(1.6, 2.9) == 0, 'failed event should be silent'

    bad = [
        {'t': 0.0, 'url': '../../etc/passwd'},
        {'t': 0.0, 'url': '/etc/passwd'},
        {'t': 0.0, 'url': base + '/beep.wav'},
        {'t': 0.0, 'url': ''},
    ]
    wav2 = os.path.join(job, 'audio2.wav')
    assert sfx.mix_audio(bad, None, 1.0, wav2, job) is False
    assert not os.path.exists(wav2)
    assert sfx.mix_audio([], None, 1.0, wav2, job) is False

    trim = [{'t': 0.5, 'url': out[0]['url'], 'dur': 0.2}]
    wav3 = os.path.join(job, 'audio3.wav')
    assert sfx.mix_audio(trim, None, 2.0, wav3, job) is True
    pcm = read_wav(wav3)
    assert peak(0.5, 0.6) > 500, 'trimmed beep missing'
    assert peak(0.71, 1.9) == 0, 'dur did not trim'

    httpd.shutdown()
    print('sfx url checks passed')


if __name__ == '__main__':
    main()
