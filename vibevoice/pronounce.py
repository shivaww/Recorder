'''Custom pronunciations: respell tricky words from pronunciations.txt.'''
import os
import re

_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'pronunciations.txt')
_cache = {'mtime': None, 'rules': []}


def _load():
    try:
        mtime = os.path.getmtime(_PATH)
    except OSError:
        return []
    if _cache['mtime'] != mtime:
        rules = []
        with open(_PATH, encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#') or '=' not in line:
                    continue
                word, say = (p.strip() for p in line.split('=', 1))
                if word and say:
                    rx = re.compile('(?<![A-Za-z0-9_])' + re.escape(word) + '(?![A-Za-z0-9_])', re.I)
                    rules.append((len(word), rx, say))
        rules.sort(key=lambda r: -r[0])
        _cache.update(mtime=mtime, rules=rules)
    return _cache['rules']


def apply(text):
    for _n, rx, say in _load():
        text = rx.sub(lambda m, s=say: s, text)
    return text
