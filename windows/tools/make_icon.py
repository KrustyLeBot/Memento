"""Draws the Scriptorium icon, the same as the Android launcher icon (amber gradient, white note sheet), as icon.png and icon.ico.

Usage: python tools/make_icon.py   (needs PySide6 and Pillow)
"""
import os
import sys
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PIL import Image
from PySide6.QtCore import QByteArray
from PySide6.QtGui import QGuiApplication, QImage, QPainter
from PySide6.QtSvg import QSvgRenderer

# The launcher icon's 108-unit grid, cropped to its visible 72-unit square.
SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72">
  <defs><linearGradient id="g" x1="10" y1="0" x2="98" y2="108" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#FFC547"/><stop offset="1" stop-color="#E07B00"/></linearGradient></defs>
  <rect x="18" y="18" width="72" height="72" rx="16" fill="url(#g)"/>
  <g transform="translate(54 54) scale(0.92) translate(-54 -54)">
    <path fill="#FFFFFF" fill-rule="evenodd" d="M34,30h40a4,4 0 0 1 4,4v32l-12,12H34a4,4 0 0 1 -4,-4V34a4,4 0 0 1 4,-4z M40,42h28v3H40z M40,50h28v3H40z M40,58h18v3H40z"/>
    <path fill="#FFE2A6" d="M66,78v-8a4,4 0 0 1 4,-4h8z"/>
  </g>
</svg>"""

out = Path(__file__).resolve().parent.parent / "scriptorium_win" / "assets"
out.mkdir(parents=True, exist_ok=True)
app = QGuiApplication(sys.argv)
image = QImage(256, 256, QImage.Format.Format_ARGB32)
image.fill(0)
painter = QPainter(image)
painter.setRenderHint(QPainter.RenderHint.Antialiasing)
QSvgRenderer(QByteArray(SVG.encode())).render(painter)
painter.end()
image.save(str(out / "icon.png"))
Image.open(out / "icon.png").save(out / "icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("written", out)
