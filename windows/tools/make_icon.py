"""Renders the Mémento icon (branding/memento.svg, the same as the Android launcher icon) as icon.png and icon.ico.

Usage: python tools/make_icon.py   (needs PySide6 and Pillow)
"""
import os
import sys
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PIL import Image
from PySide6.QtGui import QGuiApplication, QImage, QPainter
from PySide6.QtSvg import QSvgRenderer

MASTER = Path(__file__).resolve().parents[2] / "branding" / "memento.svg"
SIZES = [16, 24, 32, 48, 64, 128, 256]

out = Path(__file__).resolve().parent.parent / "scriptorium_win" / "assets"
out.mkdir(parents=True, exist_ok=True)
app = QGuiApplication(sys.argv)
renderer = QSvgRenderer(str(MASTER))


def render(size: int) -> Image.Image:
    image = QImage(size, size, QImage.Format.Format_RGBA8888)
    image.fill(0)
    painter = QPainter(image)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing)
    renderer.render(painter)
    painter.end()
    return Image.frombuffer("RGBA", (size, size), bytes(image.constBits()), "raw", "RGBA", image.bytesPerLine(), 1).copy()


# Each size is drawn from the vector master rather than scaled down from 256 px, so 16 px stays crisp.
images = {size: render(size) for size in SIZES}
images[256].save(out / "icon.png")
images[256].save(out / "icon.ico", sizes=[(s, s) for s in SIZES], append_images=[images[s] for s in SIZES[:-1]])
print("written", out)
