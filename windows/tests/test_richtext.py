import pytest
from PySide6.QtGui import QFont, QTextDocument
from PySide6.QtWidgets import QApplication

from scriptorium_win.ui import richtext


@pytest.fixture(scope="module")
def app():
    return QApplication.instance() or QApplication([])


@pytest.mark.parametrize("markdown", [
    "Bonjour **gras** et *italique* fin",
    "# Titre\n\nTexte\n- a\n- b\n\nAprès",
    "## Sous\n- **x** y",
    "a\n\n\nb",
    "",
])
def test_markdown_round_trips_through_the_styled_document(app, markdown):
    doc = QTextDocument()
    richtext.set_markdown(doc, markdown, QFont("Sans", 11))
    assert richtext.to_markdown(doc) == markdown


def test_markers_are_not_shown_to_the_user(app):
    doc = QTextDocument()
    richtext.set_markdown(doc, "# Titre\n- **gras** et *ital*", QFont("Sans", 11))
    assert doc.toPlainText() == "Titre\ngras et ital"
