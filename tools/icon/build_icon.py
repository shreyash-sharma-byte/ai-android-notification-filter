#!/usr/bin/env python3
"""
Build the NotifyCleaner launcher icon from measured geometry, and prove it.

Everything about the icon lives here as numbers measured off the supplied 1024x1024
design (`img_af8faf6a0063.jpg`).  This script is the single source of truth and emits:

    res/drawable/ic_launcher_foreground.xml   adaptive foreground (108dp, 1024 viewport)
    res/drawable/ic_launcher_monochrome.xml   themed-icon silhouette
    docs/icon.svg                             1:1 legacy art, the design at 1024

With --proof it also rasterises the same pathData through headless Chrome at 1024x1024
and diffs it against the source JPEG, writing PNGs + a JSON report into docs/icon/.

    tools/icon/build_icon.py            # regenerate the three drawables
    tools/icon/build_icon.py --proof    # ... and measure the match

Why a 1024 viewport and a group: the measurements are pixel positions in the source
image, and keeping the pathData in those units means the shipped vector and the proof
render are literally the same numbers -- no second, hand-converted set of coordinates
that can drift from what was verified.

The group scales the 1024 design into the 72dp safe circle.  The design's content
occupies 60.5% of its 1024 tile; mapping the tile onto the 72dp mask reproduces that
composition exactly (content = 43.5dp of a 72dp face) and keeps every vertex inside the
safe zone, so a circular, squircle or teardrop mask all show the whole mark.
"""

import json
import math
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SOURCE = os.path.expanduser("~/.hermes/cache/images/img_af8faf6a0063.jpg")

# ---------------------------------------------------------------- measured geometry
# Every number below was measured on the 1024px source: edges are the 0.5-alpha
# crossings of the anti-aliased boundary (sub-pixel, ~0.1px repeatability), fills are
# per-element medians.
TILE = 1024.0

GROUND = "#11100C"   # (17, 16, 12)  plate
CREAM = "#F5EDE0"    # (245, 237, 224) rows 1 and 2
GREY = "#8E8A81"     # (142, 138, 129) row 3
DGREY = "#514E47"    # (81, 78, 71)   row 4
ACCENT = "#E4562E"   # (228, 86, 46)  the disc

AVATAR = 51.45                       # square avatar, all four rows
AVATAR_X = [202.44, 202.44, 250.29, 298.10]   # left edge; steps of 47.85
AVATAR_Y = [195.00, 320.22, 445.37, 570.45]   # top edge; pitch 125.13
BAR_H = 47.80                        # bars are 3.65 shorter than the avatars
BAR_X = 95.65                        # bar left edge, from the avatar's left edge
BAR_W = [423.25, 331.26, 423.25, None]        # row 4 is cut by the disc's clearance ring
ROW_FILL = [CREAM, CREAM, GREY, DGREY]

# Disc: least-squares fit to 284 boundary crossings taken along horizontal scanlines
# (the same convention as the row edges), residual sd 0.07px.
DISC_C = (669.73, 677.12)
DISC_R = 150.88
CLEAR_R = 181.00                     # concentric ring that cuts row 4's bar (gap 30.1)

# funnel, cut out of the disc: mouth is a horizontal edge, sides taper to a stem
FUNNEL_TOP_Y = 621.84
FUNNEL_MOUTH = (592.47, 747.11)      # mouth corners where the taper lines meet the top edge
FUNNEL_STEM_Y = 691.80               # taper -> stem
FUNNEL_STEM = (647.60, 691.72)
FUNNEL_BOTTOM_Y = 754.40

# design tile -> 108dp layer: the tile fills 72dp of the 108dp layer, centred
TILE_IN_LAYER = 72.0
LAYER = 108.0
SCALE = (TILE_IN_LAYER / LAYER)                      # 0.666667 viewport units per px
OFFSET = (TILE - TILE * SCALE) / 2.0                 # 170.667

# The numbers above are pixel-index positions: index i covers [i, i+1), so an edge
# measured halfway across pixel 202 is at x=202.44 in that space. Paint space puts
# coordinates on pixel corners, i.e. half a pixel to the right/down of the index.
# Convert once, here, rather than sprinkling 0.5 through the emitters.
HALF = 0.5
AVATAR_X = [v + HALF for v in AVATAR_X]
AVATAR_Y = [v + HALF for v in AVATAR_Y]
DISC_C = (DISC_C[0] + HALF, DISC_C[1] + HALF)
FUNNEL_TOP_Y += HALF
FUNNEL_STEM_Y += HALF
FUNNEL_BOTTOM_Y += HALF
FUNNEL_MOUTH = (FUNNEL_MOUTH[0] + HALF, FUNNEL_MOUTH[1] + HALF)
FUNNEL_STEM = (FUNNEL_STEM[0] + HALF, FUNNEL_STEM[1] + HALF)


def f(v):
    s = f"{v:.2f}"
    return s.rstrip("0").rstrip(".") if "." in s else s


def rect(x, y, w, h):
    return f"M{f(x)},{f(y)}H{f(x + w)}V{f(y + h)}H{f(x)}Z"


def disc_path():
    cx, cy, r = DISC_C[0], DISC_C[1], DISC_R
    circle = (f"M{f(cx - r)},{f(cy)}"
              f"a{f(r)},{f(r)} 0 1 0 {f(2 * r)},0"
              f"a{f(r)},{f(r)} 0 1 0 {f(-2 * r)},0Z")
    funnel = (f"M{f(FUNNEL_MOUTH[0])},{f(FUNNEL_TOP_Y)}"
              f"H{f(FUNNEL_MOUTH[1])}"
              f"L{f(FUNNEL_STEM[1])},{f(FUNNEL_STEM_Y)}"
              f"V{f(FUNNEL_BOTTOM_Y)}"
              f"H{f(FUNNEL_STEM[0])}"
              f"V{f(FUNNEL_STEM_Y)}Z")
    return circle + funnel


def bar4_path():
    """Row 4's bar, ending on the disc's clearance ring r=181 about the disc centre."""
    cx, cy = DISC_C
    y0 = AVATAR_Y[3] + (AVATAR - BAR_H) / 2.0
    y1 = y0 + BAR_H
    x0 = AVATAR_X[3] + BAR_X
    xa = cx - math.sqrt(CLEAR_R ** 2 - (y0 - cy) ** 2)
    xb = cx - math.sqrt(CLEAR_R ** 2 - (y1 - cy) ** 2)
    # sweep 0: the arc bulges left, hugging the disc
    return (f"M{f(x0)},{f(y0)}H{f(xa)}"
            f"A{f(CLEAR_R)},{f(CLEAR_R)} 0 0 0 {f(xb)},{f(y1)}H{f(x0)}Z")


def shapes():
    """[(pathData, colour, evenOdd)] in paint order; row 4's bar is drawn before the disc."""
    out = []
    for i in range(4):
        out.append((rect(AVATAR_X[i], AVATAR_Y[i], AVATAR, AVATAR), ROW_FILL[i], False))
    for i in range(3):
        y = AVATAR_Y[i] + (AVATAR - BAR_H) / 2.0
        out.append((rect(AVATAR_X[i] + BAR_X, y, BAR_W[i], BAR_H), ROW_FILL[i], False))
    out.append((bar4_path(), ROW_FILL[3], False))
    # the funnel is a hole, not a painted shape: one disc path, evenOdd, two subpaths
    out.append((disc_path(), ACCENT, True))
    return out


# ---------------------------------------------------------------- emitters
HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!--
  Launcher icon: a notification list being funnelled clean.

  GENERATED by tools/icon/build_icon.py. Do not hand-edit. The pathData are pixel
  positions measured off the 1024px design, kept in a 1024 viewport (group-scaled below)
  so the shipped vector and the verified proof render are the same numbers.

  Ground is the plate colour (#11100C, res/values/colors.xml ic_launcher_background);
  this layer draws only the mark. Four rows fade cream -> cream -> grey -> dark grey and
  step 47.85 right per row; the vermilion disc at lower right carries a funnel cut out
  with evenOdd, so the hole shows the plate through it.

  The group maps the 1024 design onto the centred 72dp safe circle: every vertex lands
  within 31.2 of the centre, so no launcher mask clips the mark.
-->
"""


def vector_doc(paths, white=False):
    body = []
    for data, colour, even_odd in paths:
        fill = "#FFFFFF" if white else colour
        eo = ' android:fillType="evenOdd"' if even_odd else ""
        body.append(f'    <path\n        android:fillColor="{fill}"{eo}\n'
                    f'        android:pathData="{data}" />')
    return (HEADER +
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{f(LAYER)}dp"\n'
            f'    android:height="{f(LAYER)}dp"\n'
            f'    android:viewportWidth="{f(TILE)}"\n'
            f'    android:viewportHeight="{f(TILE)}">\n\n'
            f'    <group\n        android:scaleX="{SCALE:.6f}"\n'
            f'        android:scaleY="{SCALE:.6f}"\n'
            f'        android:translateX="{f(OFFSET)}"\n'
            f'        android:translateY="{f(OFFSET)}">\n\n' +
            "\n\n".join(body) +
            "\n\n    </group>\n</vector>\n")


def svg_doc(scale=1.0):
    """1:1 art for docs, and (with the same pathData) the proof render."""
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{f(TILE * scale)}" '
             f'height="{f(TILE * scale)}" viewBox="0 0 {f(TILE)} {f(TILE)}">',
             f'<rect width="{f(TILE)}" height="{f(TILE)}" rx="220" fill="{GROUND}"/>']
    for data, colour, even_odd in shapes():
        eo = ' fill-rule="evenodd"' if even_odd else ""
        parts.append(f'<path d="{data}" fill="{colour}"{eo}/>')
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def write(path, text):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as fh:
        fh.write(text)
    return path


def generate():
    paths = shapes()
    out = [
        write("res/drawable/ic_launcher_foreground.xml", vector_doc(paths)),
        write("res/drawable/ic_launcher_monochrome.xml", vector_doc(paths, white=True)),
        write("docs/icon.svg", svg_doc()),
    ]
    for p in out:
        print("wrote", p)


# ---------------------------------------------------------------- proof
def render(svg_path, png_path, size=1024):
    html = svg_path.replace(".svg", ".html")
    with open(html, "w", encoding="utf-8") as fh:
        fh.write("<!doctype html><meta charset=utf-8>"
                 "<style>html,body{margin:0;padding:0;background:#000}"
                 "svg{display:block}</style>"
                 f'<img src="{os.path.basename(svg_path)}" width="{size}" height="{size}">')
    subprocess.run([
        "google-chrome", "--headless=new", "--disable-gpu", "--hide-scrollbars",
        "--force-device-scale-factor=1", "--force-color-profile=srgb",
        "--default-background-color=00000000",
        f"--window-size={size},{size}", f"--screenshot={png_path}",
        "file://" + html,
    ], check=True, capture_output=True, timeout=180)


def proof():
    from PIL import Image, ImageChops
    import numpy as np

    outdir = os.path.join(ROOT, "docs", "icon")
    os.makedirs(outdir, exist_ok=True)
    svg = os.path.join(outdir, "proof.svg")
    with open(svg, "w", encoding="utf-8") as fh:
        fh.write(svg_doc())
    png = os.path.join(outdir, "proof-render.png")
    render(svg, png)

    src = Image.open(SOURCE).convert("RGB")
    ren = Image.open(png).convert("RGB")
    if ren.size != src.size:
        ren = ren.resize(src.size, Image.LANCZOS)
    S = np.asarray(src).astype(np.int16)
    R = np.asarray(ren).astype(np.int16)
    d = np.abs(S - R)
    dmax = d.max(axis=2)

    # the source is a rounded-square tile on black: judge the artwork inside the tile,
    # and report the corner ring (mask + JPEG edge) separately rather than as a shape error
    tile = (S.sum(axis=2) > 40)
    inner = np.zeros_like(tile)
    inner[6:-6, 6:-6] = tile[6:-6, 6:-6]

    def stats(mask, label, tol):
        m = mask & (d.max(axis=2) <= tol)
        print(f"  {label:28s} tol {tol:>2}: {100.0 * m.sum() / mask.sum():6.2f}%")
        return 100.0 * m.sum() / mask.sum()

    print(f"match vs source ({src.size[0]}x{src.size[1]}):")
    res = {}
    for tol in (8, 16, 32):
        res[f"tile_tol{tol}"] = round(stats(inner, "inside tile", tol), 2)
    res["tile_tol0_exact"] = round(stats(inner, "inside tile (exact)", 0), 2)
    res["full_tol16"] = round(stats(np.ones_like(tile), "full frame", 16), 2)
    res["mean_abs_err_tile"] = round(float(d[inner].mean()), 2)

    # worst regions: 32px blocks, ranked by mean abs error
    bs = 32
    worst = []
    for by in range(0, 1024, bs):
        for bx in range(0, 1024, bs):
            blk = d[by:by + bs, bx:bx + bs]
            mk = inner[by:by + bs, bx:bx + bs]
            if mk.sum() < 200:
                continue
            worst.append((float(blk.mean()), bx, by, float(blk[mk].mean())))
    worst.sort(reverse=True)
    boxes = []
    for mean, bx, by, innermean in worst[:6]:
        boxes.append({"box": [bx, by, bx + bs, by + bs], "mean_abs_err": round(innermean, 2),
                      "max_abs_err": int(d[by:by + bs, bx:bx + bs].max())})
    print("  worst 32px blocks by mean abs error:", json.dumps(boxes[:4]))

    # images: amplified difference + side by side
    diff_img = Image.fromarray(np.clip(d * 6, 0, 255).astype(np.uint8))
    diff_img.save(os.path.join(outdir, "proof-diff.png"))
    side = Image.new("RGB", (2048 + 16, 1024), (0, 0, 0))
    side.paste(src, (0, 0)); side.paste(ren, (1024 + 16, 0))
    side.save(os.path.join(outdir, "proof-side-by-side.png"))
    ren.save(os.path.join(outdir, "proof-render.png"))

    report = {"source": SOURCE, "render": png, "match": res, "worst_regions": boxes}
    with open(os.path.join(outdir, "proof.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    generate()
    if "--proof" in sys.argv:
        proof()
