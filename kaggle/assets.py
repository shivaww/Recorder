# assets.py: download <img data-nobg> images, strip backgrounds, rewrite to local files.
import hashlib
import os
import re
import threading
import urllib.request
from io import BytesIO

MAX_BYTES = 20 * 1024 * 1024
IMG_TAG = re.compile(r"<img\b[^>]*\bdata-nobg\b[^>]*>", re.I)
SRC_ATTR = re.compile(r"\bsrc\s*=\s*([\"'])(.*?)\1", re.I | re.S)

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
        raise ValueError('image too large')
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


def process_assets(html_path, job_dir):
    with open(html_path, 'r', encoding='utf-8', errors='replace') as f:
        html = f.read()
    tags = IMG_TAG.findall(html)
    stats = {'found': len(tags), 'ok': 0, 'failed': []}
    if not tags:
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
    if stats['ok']:
        with open(html_path, 'w', encoding='utf-8') as f:
            f.write(html)
    return stats
