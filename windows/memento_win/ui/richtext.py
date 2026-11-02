"""WYSIWYG text: Markdown (**bold**, *italic*, # headings, - bullets) <-> a styled QTextDocument.

The stored text stays plain Markdown; the user only ever sees styled text.
"""
from __future__ import annotations

import re

from PySide6.QtGui import QFont, QTextBlockFormat, QTextCharFormat, QTextCursor, QTextDocument, QTextListFormat

HEADING_SCALE = {1: 1.6, 2: 1.35, 3: 1.15}
_HEADING_RE = re.compile(r"^(#{1,3}) (.*)$")
_BULLET_RE = re.compile(r"^[-*] (.*)$")


def _base_format(base: QTextCharFormat | None) -> QTextCharFormat:
    return QTextCharFormat(base) if base is not None else QTextCharFormat()


def inline_runs(text: str) -> list[tuple[str, bool, bool]]:
    """Splits a line into (text, bold, italic) runs. No nesting, like the Android editor."""
    runs: list[tuple[str, bool, bool]] = []
    buf: list[str] = []

    def flush():
        if buf:
            runs.append(("".join(buf), False, False))
            buf.clear()

    i, n = 0, len(text)
    while i < n:
        if text.startswith("**", i):
            close = text.find("**", i + 3)
            if close >= 0:
                flush()
                runs.append((text[i + 2:close], True, False))
                i = close + 2
                continue
        elif text[i] == "*" and i + 1 < n and text[i + 1] not in "* ":
            close = text.find("*", i + 2)
            if close >= 0 and text[close - 1] != " " and not text.startswith("**", close):
                flush()
                runs.append((text[i + 1:close], False, True))
                i = close + 1
                continue
        buf.append(text[i])
        i += 1
    flush()
    return runs


def set_markdown(doc: QTextDocument, markdown: str, base_font: QFont) -> None:
    doc.clear()
    doc.setDefaultFont(base_font)
    cursor = QTextCursor(doc)
    current_list = None
    for index, line in enumerate(markdown.split("\n") if markdown else []):
        level, bullet, text = 0, False, line
        if m := _HEADING_RE.match(line):
            level, text = len(m[1]), m[2]
        elif m := _BULLET_RE.match(line):
            bullet, text = True, m[1]
        block = QTextBlockFormat()
        if level:
            block.setHeadingLevel(level)
        if index > 0:
            cursor.insertBlock(block, QTextCharFormat())
        else:
            cursor.setBlockFormat(block)
        if bullet:
            if current_list is None:
                fmt = QTextListFormat()
                fmt.setStyle(QTextListFormat.Style.ListDisc)
                fmt.setIndent(1)
                current_list = cursor.createList(fmt)
            else:
                current_list.add(cursor.block())
        else:
            current_list = None
        for run, bold, italic in inline_runs(text):
            fmt = QTextCharFormat()
            if bold or level:
                fmt.setFontWeight(QFont.Weight.Bold)
            if italic:
                fmt.setFontItalic(True)
            if level:
                fmt.setFontPointSize(base_font.pointSizeF() * HEADING_SCALE[level])
            cursor.insertText(run, fmt)
    doc.setModified(False)


def to_markdown(doc: QTextDocument) -> str:
    lines: list[str] = []
    block = doc.begin()
    while block.isValid():
        level = block.blockFormat().headingLevel()
        runs: list[tuple[str, bool, bool]] = []
        it = block.begin()
        while not it.atEnd():
            frag = it.fragment()
            if frag.isValid():
                fmt = frag.charFormat()
                bold = fmt.fontWeight() >= 600 and not level
                runs.append((frag.text().replace(" ", " ").replace(" ", " "), bold, fmt.fontItalic()))
            it += 1
        # merge neighbours with the same style, then wrap each run, keeping spaces outside the markers
        merged: list[list] = []
        for text, bold, italic in runs:
            if merged and merged[-1][1] == bold and merged[-1][2] == italic:
                merged[-1][0] += text
            else:
                merged.append([text, bold, italic])
        out = []
        for text, bold, italic in merged:
            marker = "**" if bold else "*" if italic else ""
            if marker and text.strip():
                lead = text[: len(text) - len(text.lstrip())]
                trail = text[len(text.rstrip()):]
                out.append(f"{lead}{marker}{text.strip()}{marker}{trail}")
            else:
                out.append(text)
        line = "".join(out)
        if level:
            line = "#" * level + " " + line
        elif block.textList() is not None:
            line = "- " + line
        lines.append(line)
        block = block.next()
    while lines and not lines[-1].strip():
        lines.pop()
    return "\n".join(lines)
