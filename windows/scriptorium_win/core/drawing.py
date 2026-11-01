"""A sketch: pen strokes on an endless page, stored as a plain SVG (same format as the Android app)."""
from __future__ import annotations

import math
import re
from dataclasses import dataclass


@dataclass(frozen=True)
class Stroke:
    rgb: int                      # 0xRRGGBB
    width: float
    points: tuple[tuple[float, float], ...]

    def distance_to(self, x: float, y: float) -> float:
        if not self.points:
            return math.inf
        if len(self.points) == 1:
            return math.hypot(x - self.points[0][0], y - self.points[0][1])
        best = math.inf
        for (ax, ay), (bx, by) in zip(self.points, self.points[1:]):
            dx, dy = bx - ax, by - ay
            len2 = dx * dx + dy * dy
            t = 0.0 if len2 == 0 else max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy) / len2))
            best = min(best, math.hypot(x - (ax + t * dx), y - (ay + t * dy)))
        return best


def bounds(strokes: list[Stroke]) -> tuple[float, float, float, float] | None:
    """min x, min y, max x, max y of the strokes, their width included."""
    if not strokes:
        return None
    xs0, ys0, xs1, ys1 = [], [], [], []
    for s in strokes:
        r = s.width / 2
        for x, y in s.points:
            xs0.append(x - r)
            xs1.append(x + r)
            ys0.append(y - r)
            ys1.append(y + r)
    return min(xs0), min(ys0), max(xs1), max(ys1)


def stroke_at(strokes: list[Stroke], x: float, y: float, radius: float) -> int:
    """The topmost stroke within radius of the point, or -1."""
    for i in reversed(range(len(strokes))):
        if strokes[i].distance_to(x, y) <= radius + strokes[i].width / 2:
            return i
    return -1


def _num(v: float) -> str:
    return f"{v:.1f}"


def to_svg(strokes: list[Stroke]) -> str:
    b = bounds(strokes) or (0.0, 0.0, 100.0, 100.0)
    margin = 24.0
    x, y = b[0] - margin, b[1] - margin
    w, h = b[2] - b[0] + 2 * margin, b[3] - b[1] + 2 * margin
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" data-scriptorium="1" viewBox="{_num(x)} {_num(y)} {_num(w)} {_num(h)}" '
           f'width="{_num(w)}" height="{_num(h)}">',
           f'<rect x="{_num(x)}" y="{_num(y)}" width="{_num(w)}" height="{_num(h)}" fill="#ffffff"/>']
    for s in strokes:
        if not s.points:
            continue
        pts = list(s.points)
        if len(pts) == 1:
            pts.append(pts[0])
        d = "M" + " L".join(f"{_num(px)} {_num(py)}" for px, py in pts)
        out.append(f'<path d="{d}" fill="none" stroke="#{s.rgb & 0xFFFFFF:06x}" stroke-width="{_num(s.width)}" '
                   'stroke-linecap="round" stroke-linejoin="round"/>')
    out.append("</svg>")
    return "\n".join(out) + "\n"


_PATH = re.compile(r"<path\b([^>]*?)/?>")
_D = re.compile(r'\bd="([^"]*)"')
_STROKE = re.compile(r'\bstroke="#([0-9a-fA-F]{6})"')
_WIDTH = re.compile(r'\bstroke-width="([0-9.]+)"')
_NUMBER = re.compile(r"-?[0-9]+(?:\.[0-9]+)?")


def parse(svg: str) -> list[Stroke]:
    """Reads what to_svg wrote; anything else in the file is ignored."""
    strokes = []
    for m in _PATH.finditer(svg):
        attrs = m.group(1)
        d, color = _D.search(attrs), _STROKE.search(attrs)
        if not d or not color:
            continue
        width = _WIDTH.search(attrs)
        nums = [float(n) for n in _NUMBER.findall(d.group(1))]
        pts = tuple(zip(nums[0::2], nums[1::2]))
        if not pts:
            continue
        if len(pts) == 2 and pts[0] == pts[1]:  # a dot
            pts = (pts[0],)
        strokes.append(Stroke(int(color.group(1), 16), float(width.group(1)) if width else 3.0, pts))
    return strokes


def looks_like_drawing(svg: str) -> bool:
    return "data-scriptorium" in svg or "data-griffonote" in svg  # older drawings carry the former app name


def is_drawing(path: str) -> bool:
    return path.startswith("drawings/") and path.endswith(".svg")


def new_path(generate_id) -> str:
    return f"drawings/dessin-{generate_id()}.svg"
