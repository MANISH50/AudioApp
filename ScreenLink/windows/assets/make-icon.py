"""Renders ScreenLink.ico (16-256 px): a phone beside a laptop screen, on a dark rounded square.

Each size is drawn at 8x and downsampled. Run:  python make-icon.py   (writes ScreenLink.ico next to this script)
"""
from pathlib import Path
from PIL import Image, ImageDraw

INK, BLUE, LIGHT, WHITE = (0x14, 0x21, 0x3D, 255), (0x2F, 0x6F, 0xDB, 255), (0x9D, 0xBC, 0xFF, 255), (255, 255, 255, 255)
SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256]


def render(size: int) -> Image.Image:
    ss = 8
    n = size * ss
    k = n / 256.0
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([8 * k, 8 * k, 248 * k, 248 * k], radius=52 * k, fill=INK)
    stroke = 16 if size <= 32 else 10
    # laptop screen and base
    d.rounded_rectangle([96 * k, 64 * k, 220 * k, 150 * k], radius=8 * k, outline=LIGHT, width=int(stroke * k))
    d.rounded_rectangle([82 * k, 162 * k, 234 * k, 176 * k], radius=6 * k, fill=LIGHT)
    # phone
    d.rounded_rectangle([36 * k, 88 * k, 92 * k, 196 * k], radius=10 * k, fill=BLUE)
    if size > 20:
        d.rectangle([46 * k, 102 * k, 82 * k, 178 * k], fill=WHITE)
    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    out = Path(__file__).with_name("ScreenLink.ico")
    images = [render(s) for s in SIZES]
    images[-1].save(out, format="ICO", sizes=[(s, s) for s in SIZES], append_images=images[:-1])
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
