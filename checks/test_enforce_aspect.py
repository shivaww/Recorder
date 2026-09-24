"""check: enforce_target_aspect geometry for 16:9 and 9:16 jobs.

Extracts the REAL function from kaggle/renderer.py (AST, no copy), then
runs portrait/landscape/letterbox cases: every clip must stay inside the
viewport, fully contain the detected bounds, and keep the viewport's aspect
ratio (within integer rounding).
Run: python3 checks/test_enforce_aspect.py
"""
import ast
import os
import sys

RENDERER = os.path.join(os.path.dirname(__file__), "..", "kaggle", "renderer.py")


def load_fn():
    with open(RENDERER, "r", encoding="utf-8") as fh:
        tree = ast.parse(fh.read())
    fn = next(n for n in ast.walk(tree)
              if isinstance(n, ast.FunctionDef) and n.name == "enforce_target_aspect")
    mod = ast.Module(body=[fn], type_ignores=[])
    ns = {}
    exec(compile(mod, "renderer_extract", "exec"), ns)
    return ns["enforce_target_aspect"]


def check(fn, name, vw, vh, bounds):
    clip = fn(bounds, vw, vh)
    x, y, w, h = clip["x"], clip["y"], clip["w"], clip["h"]
    ok_inside = 0 <= x and 0 <= y and x + w <= vw and y + h <= vh
    ok_contains = (x <= bounds["x"] and y <= bounds["y"]
                   and x + w >= bounds["x"] + bounds["w"]
                   and y + h >= bounds["y"] + bounds["h"])
    ratio = w / h
    target = vw / vh
    ok_ratio = abs(ratio - target) <= 2.0 / min(vw, vh) + 1e-9
    status = "PASS" if (ok_inside and ok_contains and ok_ratio) else "FAIL"
    print("%s %s clip=%s ratio=%.4f target=%.4f" % (status, name, clip, ratio, target))
    return status == "PASS"


def main():
    fn = load_fn()
    cases = [
        ("portrait-full-fill", 1080, 1920, {"x": 0, "y": 0, "w": 1080, "h": 1920}),
        ("portrait-undersized-centered", 1080, 1920, {"x": 60, "y": 107, "w": 960, "h": 1706}),
        ("portrait-near-full", 1080, 1920, {"x": 10, "y": 20, "w": 1060, "h": 1884}),
        ("portrait-offcenter", 1080, 1920, {"x": 0, "y": 200, "w": 1080, "h": 1700}),
        ("landscape-full-fill", 1920, 1080, {"x": 0, "y": 0, "w": 1920, "h": 1080}),
        ("landscape-undersized", 1920, 1080, {"x": 320, "y": 180, "w": 1280, "h": 720}),
        ("landscape-page-in-portrait-job", 1080, 1920, {"x": 0, "y": 656, "w": 1080, "h": 607}),
    ]
    results = [check(fn, *c) for c in cases]
    failed = results.count(False)
    print("%d/%d checks passed" % (len(results) - failed, len(results)))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
