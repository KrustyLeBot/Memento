"""Where a list was left when a detail opened from it: its scroll position and the row that was opened.

Back on the list, the position comes back, and the opened row is scrolled into view only when it is no longer
fully visible (the list changed meanwhile: an edit moved it, a sync added rows above it).
"""
from __future__ import annotations

from PySide6.QtCore import QModelIndex, QPoint
from PySide6.QtWidgets import QAbstractItemView, QScrollArea, QWidget


class ScrollMemory:
    def __init__(self):
        self.value: int | None = None
        self.key = None

    def remember(self, value: int, key) -> None:
        self.value, self.key = value, key

    def take(self) -> tuple[int | None, object]:
        value, key = self.value, self.key
        self.value = self.key = None
        return value, key

    @property
    def pending(self) -> bool:
        return self.value is not None


def fully_visible(top: int, bottom: int, view_height: int) -> bool:
    """Whether a row spanning [top, bottom) in viewport coordinates is entirely inside a viewport this high."""
    return top >= 0 and bottom <= view_height


def restore_area(area: QScrollArea, value: int | None, row: QWidget | None) -> None:
    """A QScrollArea of row widgets: back to [value], then [row] brought into view if it is not entirely shown."""
    host = area.widget()
    if host is not None and host.layout() is not None:
        host.layout().activate()  # the rows were rebuilt while hidden: their geometry is not computed yet
        if area.widgetResizable():
            host.resize(area.viewport().width(), max(host.sizeHint().height(), area.viewport().height()))
    if value is not None:
        area.verticalScrollBar().setValue(value)
    if row is None:
        return
    top = row.mapTo(area.viewport(), QPoint(0, 0)).y()
    if not fully_visible(top, top + row.height(), area.viewport().height()):
        area.ensureWidgetVisible(row, 0, 0)


def restore_view(view: QAbstractItemView, value: int | None, index: QModelIndex | None) -> None:
    """A list view: back to [value], then the row at [index] brought into view if it is not entirely shown."""
    view.doItemsLayout()  # rows reset while hidden: the scroll range is computed on the next layout otherwise
    if value is not None:
        view.verticalScrollBar().setValue(value)
    if index is None or not index.isValid():
        return
    rect = view.visualRect(index)
    if not fully_visible(rect.top(), rect.bottom() + 1, view.viewport().height()):
        view.scrollTo(index, QAbstractItemView.ScrollHint.EnsureVisible)
