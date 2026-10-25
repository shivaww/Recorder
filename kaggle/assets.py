# assets.py: download <img data-nobg> images (strip backgrounds) and #sfx audio URLs, rewrite to local files.
import hashlib
import json
import os
import re
import threading
import urllib.request
from io import BytesIO

MAX_BYTES = 20 * 1024 * 1024
IMG_TAG = re.compile(r"<img\b[^>]*\bdata-nobg\b[^>]*>", re.I)
SRC_ATTR = re.compile(r"\bsrc\s*=\s*([\"'])(.*?)\1", re.I | re.S)

SFX_BLOCK = re.compile(r"(<script\b[^>]*\bid\s*=\s*[\"']sfx[\"'][^>]*>)(.*?)(</script>)", re.I | re.S)
AUDIO_EXTS = ('.mp3', '.wav', '.ogg', '.oga', '.opus', '.m4a', '.aac', '.flac')

_lock = threading.Lock()
_session = [None]


def _remove_bg(img):
    from rembg import new_session, remove
    with _lock:
        if _session[0] is None:
            _session[0] = new_session('isnet-general-use')
        return remove(img, session=_session[0])


def _has_alpha(img):
    if img.mode != 'RGBA':
        return False
    hist = img.getchannel('A').histogram()
    return sum(hist[:250]) > 0.01 * img.width * img.height


def _download(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=25) as r:
        data = r.read(MAX_BYTES + 1)
        ctype = r.headers.get('Content-Type', '')
    if len(data) > MAX_BYTES:
        raise ValueError('file too large')
    return data, ctype


def _fetch_one(url, out_dir):
    from PIL import Image
    key = hashlib.sha1(url.encode()).hexdigest()[:12]
    data, ctype = _download(url)
    head = data.lstrip()[:300].lower()
    if 'svg' in ctype or b'<svg' in head or url.lower().split('?')[0].endswith('.svg'):
        name = key + '.svg'
        with open(os.path.join(out_dir, name), 'wb') as f:
            f.write(data)
        return name, 'svg'
    img = Image.open(BytesIO(data))
    img.load()
    img = img.convert('RGBA')
    mode = 'kept'
    if not _has_alpha(img):
        img = _remove_bg(img)
        box = img.getchannel('A').getbbox()
        if box:
            img = img.crop(box)
        mode = 'cutout'
    name = key + '.png'
    img.save(os.path.join(out_dir, name), 'PNG')
    return name, mode


def _fetch_audio(url, out_dir):
    key = hashlib.sha1(url.encode()).hexdigest()[:12]
    data, ctype = _download(url)
    head = data.lstrip()[:15].lower()
    if (not data or ctype.lower().startswith(('text/', 'image/'))
            or head.startswith((b'<!doctype', b'<html'))):
        raise ValueError('not audio (%s)' % (ctype or 'unknown type'))
    path = url.lower().split('?')[0].split('#')[0]
    ext = next((e for e in AUDIO_EXTS if path.endswith(e)), '.bin')
    name = key + ext
    with open(os.path.join(out_dir, name), 'wb') as f:
        f.write(data)
    return name


# Download each http(s) url in the #sfx JSON, rewrite it to assets/<file>.
def _fix_sfx(html, out_dir, stats):
    m = SFX_BLOCK.search(html)
    if not m:
        return html
    try:
        data = json.loads(m.group(2))
    except ValueError as e:
        stats['sfx_failed'].append('#sfx JSON invalid (%s)' % e)
        return html
    events = data if isinstance(data, list) else (data.get('events') if isinstance(data, dict) else None)
    if not isinstance(events, list):
        return html
    cache = {}
    for ev in events:
        url = ev.get('url') if isinstance(ev, dict) else None
        if not isinstance(url, str) or not url.lower().startswith(('http://', 'https://')):
            continue
        stats['sfx_found'] += 1
        if url not in cache:
            try:
                cache[url] = _fetch_audio(url, out_dir)
            except Exception as e:
                cache[url] = None
                stats['sfx_failed'].append('%s (%s: %s)' % (url[:80], type(e).__name__, e))
        if cache[url] is None:
            continue
        ev['url'] = 'assets/' + cache[url]
        stats['sfx_ok'] += 1
    if not stats['sfx_ok']:
        return html
    return html[:m.start(2)] + json.dumps(data) + html[m.end(2):]


def process_assets(html_path, job_dir):
    with open(html_path, 'r', encoding='utf-8', errors='replace') as f:
        html = f.read()
    tags = IMG_TAG.findall(html)
    stats = {'found': len(tags), 'ok': 0, 'failed': [],
             'sfx_found': 0, 'sfx_ok': 0, 'sfx_failed': []}
    if not tags and not SFX_BLOCK.search(html):
        return stats
    out_dir = os.path.join(job_dir, 'assets')
    os.makedirs(out_dir, exist_ok=True)
    cache = {}

    def fix(m):
        tag = m.group(0)
        sm = SRC_ATTR.search(tag)
        if not sm or not sm.group(2).lower().startswith(('http://', 'https://')):
            return tag
        url = sm.group(2)
        try:
            if url not in cache:
                cache[url] = _fetch_one(url, out_dir)
            name, mode = cache[url]
        except Exception as e:
            stats['failed'].append('%s (%s: %s)' % (url[:80], type(e).__name__, e))
            return tag
        stats['ok'] += 1
        return tag[:sm.start()] + 'src="assets/' + name + '"' + tag[sm.end():]

    html = IMG_TAG.sub(fix, html)
    html = _fix_sfx(html, out_dir, stats)
    if stats['ok'] or stats['sfx_ok']:
        with open(html_path, 'w', encoding='utf-8') as f:
            f.write(html)
    return stats
