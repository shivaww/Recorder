# test_media_resolve.py: media._download turns a web page into its real media file.
import http.server
import os
import sys
import tempfile
import threading

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'kaggle'))
import media  # noqa: E402

DATA = b'RIFF' + bytes(range(256)) * 8
FILES = {}


class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        item = FILES.get(self.path)
        if item is None:
            self.send_response(404)
            self.end_headers()
            return
        body, ctype = item
        self.send_response(200)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


srv = http.server.HTTPServer(('127.0.0.1', 0), H)
threading.Thread(target=srv.serve_forever, daemon=True).start()
BASE = 'http://127.0.0.1:%d' % srv.server_address[1]
BS = chr(92)


def page(body):
    return ('<html><head>' + body + '</head></html>').encode(), 'text/html; charset=utf-8'


ld_url = (BASE + '/clip').replace('/', BS + '/')
FILES['/clip'] = (DATA, 'application/octet-stream')
FILES['/a.mp3'] = (DATA, 'audio/mpeg')
FILES['/v.mp4'] = (DATA, 'video/mp4')
FILES['/s.wav'] = (DATA, 'audio/wav')
FILES['/ld'] = page('<script type="application/ld+json">{"contentUrl":"%s"}</script>' % ld_url)
FILES['/og'] = page('<meta property="og:audio" content="/a.mp3">')
FILES['/tag'] = page('<video><source src="v.mp4"></video>')
FILES['/bare'] = page('<script>var u = "%s/s.wav";</script>' % BASE)
FILES['/empty'] = page('<title>nothing here</title>')
FILES['/loop'] = page('<meta property="og:audio" content="/empty">')

tmp = tempfile.mkdtemp()
dest = os.path.join(tmp, 'out.bin')


def run(path, kind):
    if os.path.exists(dest):
        os.remove(dest)
    media._download(BASE + path, 1 << 20, dest, kind)
    with open(dest, 'rb') as f:
        return f.read()


assert run('/a.mp3', 'audio') == DATA, 'direct file'
assert run('/ld', 'video') == DATA, 'json-ld contentUrl without extension'
assert run('/og', 'audio') == DATA, 'og:audio relative'
assert run('/tag', 'video') == DATA, 'source tag relative'
assert run('/bare', 'audio') == DATA, 'bare link with extension'
FILES['/logo.svg'] = (b'<svg></svg>', 'image/svg+xml')
FILES['/imgpage'] = page('<meta property="og:image" content="/logo.svg">')
assert run('/imgpage', 'image') == b'<svg></svg>', 'og:image page'
assert media._download(BASE + '/imgpage', 1 << 20, dest, 'image') == BASE + '/logo.svg', 'returns resolved url'
assert media._download(BASE + '/a.mp3', 1 << 20, dest, 'audio') == BASE + '/a.mp3', 'returns direct url'
import assets  # noqa: E402
adata, areal = assets._download(BASE + '/imgpage', tmp)
assert adata == b'<svg></svg>' and areal == BASE + '/logo.svg', 'assets._download resolves page'
assert not [n for n in os.listdir(tmp) if n.endswith('.isrc')], 'temp file removed'
for bad in ('/empty', '/loop', '/missing-page'):
    try:
        run(bad, 'audio')
    except Exception:
        continue
    raise SystemExit('expected failure for ' + bad)
srv.shutdown()
print('test_media_resolve OK')
