#!/usr/bin/env python3
"""Regression test for voice/server.py ITN + pause helpers (no torch needed).

Splices the pure-Python helper spans out of server.py by marker strings and
runs the transcript report's failing sentences through normalize_text.
"""
import os
import re
import sys

SRC = os.path.join(os.path.dirname(__file__), "..", "voice", "server.py")
src = open(SRC, encoding="utf-8").read().splitlines()


def span(start, stop=None):
    i = next(k for k, l in enumerate(src) if l.startswith(start))
    if stop is None:
        return src[i:i + 1]
    j = next(k for k, l in enumerate(src) if l.startswith(stop))
    return src[i:j]


code = "\n".join(
    span("MAX_CHUNK_CHARS")
    + span("SENT_SPLIT", "def generate_one")
    + span("def _pause_for", "_dev_locks =")
)
ns = {"re": re}
exec(code, ns)
nt = ns["normalize_text"]
pf = ns["_pause_for"]

CASES = [
    ("The temperature dropped to 4 \u00b0C then rose to 72 \u00b0F.",
     "The temperature dropped to four degrees Celsius then rose to seventy two degrees Fahrenheit."),
    ("Please call 550 551 134 or visit 42 Baker Street.",
     "Please call five five zero, five five one, one three four or visit forty two Baker Street."),
]
fail = 0
for raw, want in CASES:
    got = nt(raw)
    ok = got == want
    fail += 0 if ok else 1
    print("PASS" if ok else "FAIL", got)
    if not ok:
        print("  want:", want)
print("MONEY/TIME:", nt("On Tuesday, March 14, 2028 at 9:05 AM, I ordered 27 items for $1,349.87."))
print("pause_dot", pf("Hello.", 24000), "pause_comma", pf("Hello,", 24000))
sys.exit(1 if fail else 0)
