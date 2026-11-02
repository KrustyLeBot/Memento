from __future__ import annotations

from dataclasses import dataclass

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QGuiApplication, QPalette

# id -> (light, dark), same palette as the Android app
NOTE_COLORS = {
    "red": ("#F28B82", "#77172E"),
    "orange": ("#FBBC04", "#692B17"),
    "yellow": ("#FFF475", "#7C4A03"),
    "green": ("#CCFF90", "#264D3B"),
    "teal": ("#A7FFEB", "#0C625D"),
    "blue": ("#CBF0F8", "#256377"),
    "darkblue": ("#AECBFA", "#284255"),
    "purple": ("#D7AEFB", "#472E5B"),
    "pink": ("#FDCFE8", "#6C394F"),
    "brown": ("#E6C9A8", "#4B443A"),
    "gray": ("#E8EAED", "#232427"),
}


@dataclass(frozen=True)
class Scheme:
    background: str
    surface_container: str
    surface_high: str
    outline: str
    outline_variant: str
    on_surface: str
    on_surface_variant: str
    primary: str
    on_primary: str
    primary_container: str
    on_primary_container: str
    secondary_container: str
    on_secondary_container: str
    error: str
    error_container: str
    on_error_container: str


LIGHT = Scheme("#FFF8F8", "#FCE9EC", "#F7E3E7", "#88767A", "#C1ADB1", "#3C2F32", "#6B5B5E", "#912C52", "#FFFFFF",
               "#FAAFC3", "#622D3E", "#FFD9E1", "#674A51", "#A8364B", "#F97386", "#6E0523")
DARK = Scheme("#130D0E", "#211719", "#281D1F", "#837175", "#534447", "#F6E0E4", "#BAA6AA", "#FFB1C6", "#640330",
              "#734552", "#FFDAE2", "#4E343B", "#DBB6BE", "#F97386", "#871C34", "#FF97A3")


def is_dark() -> bool:
    app = QGuiApplication.instance()
    return app is not None and app.palette().color(QPalette.ColorRole.Window).lightness() < 128


def scheme() -> Scheme:
    return DARK if is_dark() else LIGHT


def note_background(color_id: str) -> str:
    """Page background of an open note: its colour, or the plain window colour for the default."""
    pair = NOTE_COLORS.get(color_id)
    return (pair[1] if is_dark() else pair[0]) if pair else scheme().background


def card_background(color_id: str) -> str:
    pair = NOTE_COLORS.get(color_id)
    return (pair[1] if is_dark() else pair[0]) if pair else scheme().surface_container


def note_foreground(color_id: str) -> str:
    if color_id not in NOTE_COLORS:
        return scheme().on_surface
    return "#E8EAED" if is_dark() else "#202124"


def apply_theme(app, mode: str) -> None:
    """mode: 'system' | 'light' | 'dark'. Builds an explicit palette so both modes look the same everywhere."""
    hints = app.styleHints()
    if hasattr(hints, "setColorScheme"):
        hints.setColorScheme({"light": Qt.ColorScheme.Light, "dark": Qt.ColorScheme.Dark}.get(mode, Qt.ColorScheme.Unknown))
    dark = (mode == "dark") or (mode == "system" and hints.colorScheme() == Qt.ColorScheme.Dark)
    s = DARK if dark else LIGHT
    palette = QPalette()
    for role, value in [
        (QPalette.ColorRole.Window, s.background), (QPalette.ColorRole.WindowText, s.on_surface),
        (QPalette.ColorRole.Base, s.background), (QPalette.ColorRole.AlternateBase, s.surface_container),
        (QPalette.ColorRole.Text, s.on_surface), (QPalette.ColorRole.Button, s.surface_container),
        (QPalette.ColorRole.ButtonText, s.on_surface), (QPalette.ColorRole.ToolTipBase, s.surface_high),
        (QPalette.ColorRole.ToolTipText, s.on_surface), (QPalette.ColorRole.Highlight, s.primary),
        (QPalette.ColorRole.HighlightedText, s.on_primary), (QPalette.ColorRole.PlaceholderText, s.on_surface_variant),
        (QPalette.ColorRole.Link, s.primary), (QPalette.ColorRole.Mid, s.outline_variant),
    ]:
        palette.setColor(role, QColor(value))
    app.setPalette(palette)
    app.setStyleSheet(stylesheet(s))


def stylesheet(s: Scheme) -> str:
    return f"""
    QMainWindow, QStackedWidget {{ background: {s.background}; }}
    QToolTip {{ background: {s.surface_high}; color: {s.on_surface}; border: 1px solid {s.outline_variant}; }}
    QFrame#toast {{ background: {s.surface_high}; border: 1px solid {s.outline_variant}; border-radius: 16px; }}
    QFrame#banner {{ background: {s.primary_container}; border-radius: 16px; }}
    QFrame#banner QLabel {{ color: {s.on_primary_container}; }}
    QFrame#snack {{ background: #322F2B; border-radius: 8px; }}
    QFrame#snack QLabel {{ color: #F4EFE8; }}
    QFrame#pill {{ background: {s.surface_high}; border-radius: 26px; }}
    QFrame#pill QLineEdit {{ border: none; background: transparent; font-size: 11pt; }}
    QPushButton {{ border: none; border-radius: 18px; padding: 8px 16px; background: transparent; color: {s.primary}; }}
    QPushButton:hover {{ background: {s.surface_container}; }}
    QPushButton#primary {{ background: {s.primary}; color: {s.on_primary}; }}
    QPushButton#chip {{ border: 1px solid {s.outline_variant}; border-radius: 8px; padding: 6px 12px; color: {s.on_surface}; }}
    QPushButton#chip:checked {{ background: {s.secondary_container}; color: {s.on_secondary_container}; border-color: transparent; }}
    QPushButton#fab {{ background: {s.primary_container}; color: {s.on_primary_container}; border-radius: 16px;
                       padding: 16px 22px; font-size: 11pt; font-weight: 600; }}
    QPushButton#fab:hover {{ background: {s.primary_container}; border: 2px solid {s.primary}; }}
    QPushButton#fabSmall {{ background: {s.secondary_container}; color: {s.on_secondary_container}; border-radius: 12px;
                            padding: 12px; }}
    QToolButton {{ border: none; border-radius: 20px; padding: 8px; background: transparent; }}
    QToolButton:hover {{ background: rgba(128, 128, 128, 0.2); }}
    QToolButton:checked {{ background: rgba(128, 128, 128, 0.3); }}
    QProgressBar {{ background: {s.outline_variant}; border: none; border-radius: 2px; }}
    QProgressBar::chunk {{ background: {s.primary}; border-radius: 2px; }}
    QScrollArea {{ border: none; background: transparent; }}
    QScrollBar:vertical {{ width: 10px; background: transparent; }}
    QScrollBar::handle:vertical {{ background: {s.outline_variant}; border-radius: 5px; min-height: 30px; }}
    QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; }}
    QMenu {{ background: {s.surface_high}; border: 1px solid {s.outline_variant}; padding: 4px; }}
    QMenu::item {{ padding: 6px 18px; border-radius: 6px; }}
    QMenu::item:selected {{ background: {s.secondary_container}; color: {s.on_secondary_container}; }}
    """
