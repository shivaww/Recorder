import re

CONTRACTIONS = {
    "he's": "he is", "she's": "she is", "it's": "it is", "that's": "that is",
    "what's": "what is", "there's": "there is", "here's": "here is",
    "who's": "who is", "where's": "where is", "how's": "how is",
}
_CONTR_RE = re.compile(r"\b(" + "|".join(CONTRACTIONS) + r")\b", re.IGNORECASE)


def _expand_contraction(m):
    out = CONTRACTIONS[m.group(1).lower()]
    return out.capitalize() if m.group(1)[0].isupper() else out


_ONES = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
         "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
         "seventeen", "eighteen", "nineteen"]
_TENS = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]
_SCALES = [(10 ** 9, "billion"), (10 ** 6, "million"), (1000, "thousand")]
_YEAR_CTX = re.compile(r"\b(in|since|from|until|by|before|after|year|circa)\s+(1[1-9]\d\d|20\d\d)\b", re.I)
_MONEY = re.compile(r"\$(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)(\s+(?:thousand|million|billion))?", re.I)
_PERCENT = re.compile(r"\b(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)\s*%")
_NUMBER = re.compile(r"\b(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?\b")
_TEMP = re.compile(r"\b(\d+)\s*(?:°\s*([CF])|degrees?\s+([CF]))\b", re.I)
_PHONE = re.compile(r"\b(\d{3})[\s.\-](\d{3})[\s.\-](\d{3,4})\b")
_ABBR = [
    (re.compile(r"\bDr\."), "Doctor"),
    (re.compile(r"\bMr\."), "Mister"),
    (re.compile(r"\bMrs\."), "Missus"),
    (re.compile(r"\bMs\."), "Miz"),
    (re.compile(r"\bvs\.?(?=\s)", re.I), "versus"),
    (re.compile(r"\be\.g\.", re.I), "for example"),
    (re.compile(r"\bi\.e\.", re.I), "that is"),
]


_ORD_IRREG = {"one": "first", "two": "second", "three": "third", "five": "fifth",
              "eight": "eighth", "nine": "ninth", "twelve": "twelfth"}
_ORDINAL = re.compile(r"\b(\d+)(?:st|nd|rd|th)\b", re.I)
_TIME = re.compile(r"\b(\d{1,2}):(\d{2})(?:\s*([ap])m\b)?", re.I)


def _ord_words(n: int) -> str:
    w = _int_words(n).split(" ")
    last = w[-1]
    if last in _ORD_IRREG:
        w[-1] = _ORD_IRREG[last]
    elif last.endswith("y"):
        w[-1] = last[:-1] + "ieth"
    else:
        w[-1] = last + "th"
    return " ".join(w)


def _time_words(m) -> str:
    h, mi = int(m.group(1)), int(m.group(2))
    if h > 23 or mi > 59:
        return m.group(0)
    out = _int_words(h)
    if mi == 0:
        if not m.group(3):
            out += " o'clock"
    elif mi < 10:
        out += " oh " + _int_words(mi)
    else:
        out += " " + _int_words(mi)
    if m.group(3):
        out += " " + m.group(3).upper() + " M"
    return out


def _int_words(n: int) -> str:
    if n < 20:
        return _ONES[n]
    if n < 100:
        t, o = divmod(n, 10)
        return _TENS[t] + (" " + _ONES[o] if o else "")
    if n < 1000:
        h, r = divmod(n, 100)
        return _ONES[h] + " hundred" + (" " + _int_words(r) if r else "")
    for v, name in _SCALES:
        if n >= v:
            q, r = divmod(n, v)
            return _int_words(q) + " " + name + (" " + _int_words(r) if r else "")
    return str(n)


def _num_words(s: str) -> str:
    s = s.replace(",", "")
    if "." in s:
        a, b = s.split(".", 1)
        return _int_words(int(a or 0)) + " point " + " ".join(_ONES[int(d)] for d in b)
    return _int_words(int(s))


def _year_words(y: int) -> str:
    if 2000 <= y <= 2009:
        return _int_words(y)
    hi, lo = divmod(y, 100)
    if lo == 0:
        return _int_words(hi) + " hundred"
    if lo < 10:
        return _int_words(hi) + " oh " + _int_words(lo)
    return _int_words(hi) + " " + _int_words(lo)


def _temp_words(m) -> str:
    unit = "Celsius" if (m.group(2) or m.group(3)).upper() == "C" else "Fahrenheit"
    return f"{_num_words(m.group(1))} degrees {unit}"


def _phone_words(m) -> str:
    groups = (m.group(1), m.group(2), m.group(3))
    return ", ".join(" ".join(_ONES[int(d)] for d in g) for g in groups)


def _money(m) -> str:
    amt, scale = m.group(1), (m.group(2) or "").strip()
    if scale:
        return f"{_num_words(amt)} {scale} dollars"
    if "." in amt:
        whole, frac = amt.replace(",", "").split(".", 1)
        if len(frac) == 2:
            w = int(whole)
            out = f"{_int_words(w)} {'dollar' if w == 1 else 'dollars'}"
            c = int(frac)
            if c:
                out += f" and {_int_words(c)} {'cent' if c == 1 else 'cents'}"
            return out
        return _num_words(amt) + " dollars"
    n = int(amt.replace(",", ""))
    return f"{_int_words(n)} {'dollar' if n == 1 else 'dollars'}"


def _expand_numbers(text: str) -> str:
    text = _PHONE.sub(_phone_words, text)
    text = _TEMP.sub(_temp_words, text)
    text = _TIME.sub(_time_words, text)
    text = _ORDINAL.sub(lambda m: _ord_words(int(m.group(1))), text)
    text = _YEAR_CTX.sub(lambda m: f"{m.group(1)} {_year_words(int(m.group(2)))}", text)
    text = _MONEY.sub(_money, text)
    text = _PERCENT.sub(lambda m: _num_words(m.group(1)) + " percent", text)
    return _NUMBER.sub(lambda m: _num_words(m.group(0)), text)


def normalize_text(text: str) -> str:
    """Clean text for TTS: the model does no normalization of its own."""
    text = text.replace("\u2019", "'").replace("\u2018", "'")
    text = text.replace("\u201c", '"').replace("\u201d", '"')
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    lines = [ln if ln[-1] in ".!?:;," else ln + "." for ln in lines]
    text = " ".join(lines)
    text = _CONTR_RE.sub(_expand_contraction, text)
    for rx, rep in _ABBR:
        text = rx.sub(rep, text)
    text = re.sub(r"\s*&\s*", " and ", text)
    text = _expand_numbers(text)
    text = re.sub(r"\s*[\u2014\u2013]\s*", ", ", text)
    text = text.replace("\u2026", ".")
    text = re.sub(r"[*#`_~^|<>\[\]{}]", " ", text)
    text = re.sub(r",\s*,", ",", text)
    text = re.sub(r"\.{2,}", ".", text)
    text = re.sub(r"\s+", " ", text)
    return re.sub(r"\s+([,.;:!?])", r"\1", text).strip()
