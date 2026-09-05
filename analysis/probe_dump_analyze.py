#!/usr/bin/env python3
"""Pure-stdlib PNG analyzer for BrollRender probe dumps (hw vs ref).
No PIL/numpy needed: chunk parse + zlib inflate + scanline unfilter.
Prints the facts that discriminate WHY the GPU fast path failed:
  - dominant color + coverage (uniform? page-void? our void fill?)
  - content bounding box (squeezed into a corner = layout/scale bug)
  - hw-vs-ref luma diff profile
"""
import struct
import sys
import zlib


def load_png(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("not a PNG: " + path)
    pos = 8
    idat = b""
    w = h = bd = ct = None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bd, ct, _c, _f, _i = struct.unpack(">IIBBBBB", chunk)
        elif typ == b"IDAT":
            idat += chunk
        pos += 12 + ln
    if bd != 8 or ct not in (2, 6):
        raise SystemExit("unsupported PNG (bitdepth=%d colortype=%d)" % (bd, ct))
    raw = zlib.decompress(idat)
    bpp = 3 if ct == 2 else 4
    stride = w * bpp
    out = bytearray(w * h * bpp)
    prev = bytearray(stride)
    ptr = 0
    for y in range(h):
        f = raw[ptr]
        ptr += 1
        line = bytearray(raw[ptr:ptr + stride])
        ptr += stride
        if f == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, bpp, bytes(out)


def px(img, x, y):
    w, h, bpp, d = img
    o = (y * w + x) * bpp
    return d[o], d[o + 1], d[o + 2]


def luma(p):
    return 0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2]


def analyze(name, img, dom=None):
    w, h, bpp, d = img
    counts = {}
    for y in range(0, h, 16):
        for x in range(0, w, 16):
            p = px(img, x, y)
            counts[p] = counts.get(p, 0) + 1
    total = sum(counts.values())
    top = sorted(counts.items(), key=lambda kv: -kv[1])[:4]
    dom = top[0][0]
    print("%s: %dx%d, sampled dominant RGB=%d,%d,%d (%.1f%%)" %
          (name, w, h, dom[0], dom[1], dom[2], 100.0 * top[0][1] / total))
    for p, n in top[1:]:
        print("   next RGB=%d,%d,%d (%.2f%%)" % (p[0], p[1], p[2], 100.0 * n / total))
    minX, minY, maxX, maxY, ncontent = w, h, -1, -1, 0
    nsamp = 0
    for y in range(0, h, 4):
        for x in range(0, w, 4):
            p = px(img, x, y)
            nsamp += 1
            if (abs(p[0] - dom[0]) + abs(p[1] - dom[1]) + abs(p[2] - dom[2])) > 30:
                ncontent += 1
                if x < minX: minX = x
                if y < minY: minY = y
                if x > maxX: maxX = x
                if y > maxY: maxY = y
    if ncontent:
        print("   content: %.2f%% of samples, bbox x[%d..%d] y[%d..%d] "
              "(covers %.0f%% width, %.0f%% height)" %
              (100.0 * ncontent / nsamp, minX, maxX, minY, maxY,
               100.0 * (maxX - minX) / w, 100.0 * (maxY - minY) / h))
    else:
        print("   content: NONE (uniform)")
    return dom


def compare(hw, ref):
    w, h, _b, _d = hw
    diffs = []
    for y in range(0, h, 8):
        for x in range(0, w, 8):
            diffs.append(abs(luma(px(hw, x, y)) - luma(px(ref, x, y))))
    diffs.sort()
    mean = sum(diffs) / len(diffs)
    print("hw vs ref luma |diff|: mean=%.1f median=%.1f p90=%.1f max=%.1f" %
          (mean, diffs[len(diffs) // 2], diffs[int(0.9 * len(diffs))], diffs[-1]))


if __name__ == "__main__":
    hw = load_png(sys.argv[1])
    ref = load_png(sys.argv[2])
    analyze("HW  ", hw)
    analyze("REF ", ref)
    if hw[0] == ref[0] and hw[1] == ref[1]:
        compare(hw, ref)
    else:
        print("size mismatch: hw %dx%d vs ref %dx%d" % (hw[0], hw[1], ref[0], ref[1]))
