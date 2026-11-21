"""Renders Spatia.ico (16-256 px) from the design in spatia-icon.svg using Pillow.

Each size is drawn separately at 8x and downsampled, with heavier strokes at small sizes so the mark stays legible
in the taskbar and Explorer. Run:  python make-icon.py   (writes Spatia.ico next to this script)
"""
from pathlib import Path
from PIL import Image, ImageDraw

NAVY, TEAL, TEAL_LIGHT = (0x1B, 0x2A, 0x41, 255), (0x7F, 0xD1, 0xC7, 255), (0xB9, 0xE8, 0xE2, 255)
SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256]


def render(size: int) -> Image.Image:
    ss = 8
    n = size * ss
    k = n / 256.0  # design units -> pixels
    stroke = 26 if size <= 24 else 20 if size <= 48 else 16
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([8 * k, 8 * k, 248 * k, 248 * k], radius=52 * k, fill=NAVY)
    tiny = size <= 20
    dot = 28 if tiny else 20
    d.ellipse([(128 - dot) * k, (128 - dot) * k, (128 + dot) * k, (128 + dot) * k], fill=TEAL)
    # At 16-20 px one pair of wider-spaced arcs reads better than two crowded pairs.
    arcs = ((88, TEAL_LIGHT),) if tiny else ((60, TEAL), (100, TEAL_LIGHT))
    if tiny:
        stroke = 30
    for radius, colour in arcs:
        box = [(128 - radius) * k, (128 - radius) * k, (128 + radius) * k, (128 + radius) * k]
        w = int(round(stroke * k))
        d.arc(box, start=135, end=225, fill=colour, width=w)  # left
        d.arc(box, start=-45, end=45, fill=colour, width=w)   # right
        # round caps
        for ang in (135, 225, -45, 45):
            cx = 128 + (radius - stroke / 2) * math.cos(math.radians(ang))
            cy = 128 + (radius - stroke / 2) * math.sin(math.radians(ang))
            r = stroke / 2
            d.ellipse([(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k], fill=colour)
    return img.resize((size, size), Image.LANCZOS)


import math  # noqa: E402  (used inside render)


def main() -> None:
    out = Path(__file__).with_name("Spatia.ico")
    images = [render(s) for s in SIZES]
    images[-1].save(out, format="ICO", sizes=[(s, s) for s in SIZES], append_images=images[:-1])
    with Image.open(out) as ico:
        got = sorted(ico.info.get("sizes", set()))
    missing = [s for s in SIZES if (s, s) not in got]
    if missing:
        raise SystemExit(f"ICO is missing sizes: {missing}")
    render(256).save(out.with_name("spatia-icon-256.png"))
    print(f"wrote {out} with sizes {[s for s, _ in got]}")


if __name__ == "__main__":
    main()
