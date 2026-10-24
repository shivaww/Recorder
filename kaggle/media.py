# media.py: download remote <video> and #sfx audio URLs, transcode with ffmpeg,
# and rewrite them to local files under assets/. Called from assets.process_assets.
import hashlib
import json
import os
import re
import subprocess
import urllib.parse
import urllib.request

MAX_VIDEO_BYTES = 100 * 1024 * 1024
MAX_AUDIO_BYTES = 25 * 1024 * 1024
MAX_VIDEO_SEC = 45
MAX_SFX_SEC = 20
SAMPLE_RATE = 44100  # must equal config.SAMPLE_RATE (sfx.py checks it)

VIDEO_TAG = re.compile(r'<video\b[^>]*>', re.I)
SRC_ATTR = re.compile(r'\bsrc\s*=\s*([\x22\x27])(.*?)\1', re.I | re.S)
DROP_ATTR = re.compile(
    r'\s(?:autoplay|loop)(?:\s*=\s*(?:\x22[^\x22]*\x22|\x27[^\x27]*\x27|[^\s>]+))?(?=[\s>/])', re.I)
SFX_BLOCK = re.compile(
    r'(<script\b[^>]*\bid\s*=\s*[\x22\x27]sfx[\x22\x27][^>]*>)(.*?)(</script>)', re.I | re.S)

VP9 = ['-c:v', 'libvpx-vp9', '-crf', '30', '-b:v', '0',
       '-deadline', 'realtime', '-cpu-used', '6', '-row-mt', '1', '-g', '30']
VP8 = ['-c:v', 'libvpx', '-crf', '12', '-b:v', '4M',
       '-deadline', 'realtime', '-cpu-used', '8', '-g', '30']


def _key(url):
    return hashlib.sha1(url.encode()).hexdigest()[:12]


def _rm(path):
    try:
        os.remove(path)
    except OSError:
        pass


def _is_remote(u):
    return isinstance(u, str) and u.lower().startswith(('http://', 'https://'))


MAX_PAGE_BYTES = 2 * 1024 * 1024
MAX_CANDIDATES = 8
MEDIA_EXT = {
    'video': ('.mp4', '.webm', '.mov', '.m4v'),
    'audio': ('.mp3', '.wav', '.ogg', '.m4a', '.aac', '.flac'),
    'image': ('.png', '.webp', '.jpg', '.jpeg', '.svg'),
}
OG_KEYS = {
    'video': ('video', 'video:url', 'video:secure_url'),
    'audio': ('audio', 'audio:url', 'audio:secure_url'),
    'image': ('image', 'image:url', 'image:secure_url'),
}
HTML_SNIFF = re.compile(rb'<(?:!doctype\s+html|html)\b', re.I)
CONTENT_URL = re.compile(r'\x22contentUrl\x22\s*:\s*\x22([^\x22]+)\x22')
EMBED_URL = re.compile(r'\x22embedUrl\x22\s*:\s*\x22([^\x22]+)\x22')
META_TAG = re.compile(r'<meta\b[^>]*>', re.I)
META_KEY = re.compile(r'\b(?:property|name)\s*=\s*[\x22\x27]og:([a-z:_]+)[\x22\x27]', re.I)
META_VAL = re.compile(r'\bcontent\s*=\s*([\x22\x27])(.*?)\1', re.I | re.S)
MEDIA_TAG = re.compile(r'<(?:source|video|audio)\b[^>]*>', re.I)
BARE_URL = re.compile(r'https?://[^\s\x22\x27<>\x5c)]+')


def _get(url, max_bytes, dest):
    # Streams url to dest. If the body is an HTML page, returns (text, final_url) and writes nothing.
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=30) as r:
        ctype = (r.headers.get('Content-Type') or '').lower()
        first = r.read(1 << 20)
        if 'html' in ctype or HTML_SNIFF.search(first[:1024]):
            body = first + r.read(MAX_PAGE_BYTES)
            return body.decode('utf-8', 'replace'), r.geturl()
        size = len(first)
        if size > max_bytes:
            raise ValueError('file too large')
        with open(dest, 'wb') as f:
            f.write(first)
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                size += len(chunk)
                if size > max_bytes:
                    raise ValueError('file too large')
                f.write(chunk)
    return None


def _candidates(page, base, kind):
    # Real media URLs found on a web page, best first.
    text = page.replace(chr(92) + '/', '/')
    found = []

    def add(u):
        u = u.strip().rstrip('.,;').replace('&amp;', '&')
        if not u or u.startswith(('data:', 'blob:', 'javascript:', '#')):
            return
        u = urllib.parse.urljoin(base, u)
        if _is_remote(u) and u not in found:
            found.append(u)

    for rx in (CONTENT_URL, EMBED_URL):
        for m in rx.finditer(text):
            add(m.group(1))
    keys = OG_KEYS[kind]
    for tag in META_TAG.findall(text):
        k, v = META_KEY.search(tag), META_VAL.search(tag)
        if k and v and k.group(1).lower() in keys:
            add(v.group(2))
    for tag in MEDIA_TAG.findall(text):
        m = SRC_ATTR.search(tag)
        if m:
            add(m.group(2))
    exts = MEDIA_EXT[kind]
    for m in BARE_URL.finditer(text):
        u = m.group(0).rstrip('.,;')
        if urllib.parse.urlparse(u).path.lower().endswith(exts):
            add(u)
    return found[:MAX_CANDIDATES]


def _download(url, max_bytes, dest, kind):
    # Direct file URLs download as-is. A web page is searched for the real file, one level deep.
    page = _get(url, max_bytes, dest)
    if page is None:
        return url
    cands = _candidates(page[0], page[1], kind)
    last = ''
    for u in cands:
        try:
            if _get(u, max_bytes, dest) is None:
                return u
        except Exception as e:
            last = '%s: %s' % (type(e).__name__, e)
    raise ValueError('web page has no usable %s file (%d links tried%s)'
                     % (kind, len(cands), '; last ' + last if last else ''))


def _ffmpeg(args, timeout):
    cmd = ['ffmpeg', '-nostdin', '-y', '-hide_banner', '-loglevel', 'error'] + args
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError((r.stderr or 'ffmpeg failed').strip()[-200:])


def _fetch_video(url, out_dir):
    # Chromium from Playwright has no H.264, so always transcode to silent WebM.
    key = _key(url)
    src = os.path.join(out_dir, key + '.vsrc')
    name = key + '.webm'
    err = None
    try:
        _download(url, MAX_VIDEO_BYTES, src, 'video')
        for codec in (VP9, VP8):
            try:
                _ffmpeg(['-i', src, '-t', str(MAX_VIDEO_SEC), '-an',
                         '-vf', "scale='min(1920,iw)':-2", '-pix_fmt', 'yuv420p']
                        + codec + [os.path.join(out_dir, name)], 600)
                return name
            except RuntimeError as e:
                err = e
        raise err
    finally:
        _rm(src)


def _fetch_sfx(url, out_dir):
    # Any audio container in, 44.1k mono 16-bit WAV out (sfx.py reads it with wave+numpy).
    key = _key(url)
    src = os.path.join(out_dir, key + '.asrc')
    name = key + '.wav'
    try:
        _download(url, MAX_AUDIO_BYTES, src, 'audio')
        _ffmpeg(['-i', src, '-t', str(MAX_SFX_SEC), '-vn', '-ar', str(SAMPLE_RATE),
                 '-ac', '1', '-c:a', 'pcm_s16le', os.path.join(out_dir, name)], 120)
    finally:
        _rm(src)
    return name


def process_media(html, out_dir, stats):
    # Rewrite remote <video src> and #sfx event urls to local assets/ files.
    for k in ('video_found', 'video_ok', 'sfx_found', 'sfx_ok'):
        stats.setdefault(k, 0)
    failed = stats.setdefault('failed', [])
    vcache, acache = {}, {}

    def fix_video(m):
        # autoplay/loop are dropped: the renderer seeks each video per frame.
        tag = DROP_ATTR.sub('', m.group(0))
        sm = SRC_ATTR.search(tag)
        if not sm or not _is_remote(sm.group(2)):
            return tag
        url = sm.group(2)
        stats['video_found'] += 1
        try:
            if url not in vcache:
                vcache[url] = _fetch_video(url, out_dir)
        except Exception as e:
            failed.append('video %s (%s: %s)' % (url[:80], type(e).__name__, e))
            return tag
        stats['video_ok'] += 1
        tag = tag[:sm.start()] + 'src="assets/' + vcache[url] + '"' + tag[sm.end():]
        extra = ''
        if not re.search(r'\bmuted\b', tag, re.I):
            extra += ' muted'
        if not re.search(r'\bplaysinline\b', tag, re.I):
            extra += ' playsinline'
        if not re.search(r'\bpreload\s*=', tag, re.I):
            extra += ' preload="auto"'
        return re.sub(r'\s*/?>\Z', lambda _m: extra + '>', tag)

    def fix_sfx(m):
        try:
            data = json.loads(m.group(2))
        except ValueError:
            return m.group(0)
        evs = data.get('events') if isinstance(data, dict) else data
        if not isinstance(evs, list):
            return m.group(0)
        for e in evs:
            url = e.get('url') if isinstance(e, dict) else None
            if not _is_remote(url):
                continue
            stats['sfx_found'] += 1
            try:
                if url not in acache:
                    acache[url] = _fetch_sfx(url, out_dir)
            except Exception as ex:
                failed.append('sfx %s (%s: %s)' % (url[:80], type(ex).__name__, ex))
                continue
            stats['sfx_ok'] += 1
            e['url'] = 'assets/' + acache[url]
        return m.group(1) + json.dumps(data) + m.group(3)

    html = VIDEO_TAG.sub(fix_video, html)
    return SFX_BLOCK.sub(fix_sfx, html)
