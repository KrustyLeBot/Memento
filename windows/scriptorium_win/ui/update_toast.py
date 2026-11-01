from __future__ import annotations

import threading

from PySide6.QtCore import QTimer, QUrl, Qt
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import QFrame, QHBoxLayout, QLabel, QProgressBar, QPushButton, QVBoxLayout

from ..services import updater
from ..services.async_util import run_async


class UpdateToast(QFrame):
    def __init__(self, main, release: updater.Release):
        super().__init__(main)
        self.main, self.release = main, release
        self._progress = (0, 0)
        self._lock = threading.Lock()
        self.setObjectName("toast")  # styled in the stylesheet (QFrame#toast)
        self.setFixedWidth(380)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(16, 14, 12, 14)
        layout.setSpacing(6)

        head = QHBoxLayout()
        title = QLabel("Mise à jour disponible")
        title.setStyleSheet("font-weight: 700; font-size: 11pt;")
        close = QPushButton("×")
        close.setFixedWidth(30)
        close.clicked.connect(self.hide)
        head.addWidget(title)
        head.addStretch()
        head.addWidget(close)
        layout.addLayout(head)

        self.text = QLabel(f"Scriptorium build <b>{release.build}</b> est disponible "
                           f"(tu as le build {updater.current_build()}).")
        self.text.setWordWrap(True)
        self.text.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.text.setTextFormat(Qt.TextFormat.RichText)
        layout.addWidget(self.text)

        self.bar = QProgressBar()
        self.bar.setTextVisible(False)
        self.bar.setFixedHeight(4)
        self.bar.hide()
        layout.addWidget(self.bar)

        buttons = QHBoxLayout()
        buttons.addStretch()
        self.later = QPushButton("Plus tard")
        self.later.clicked.connect(self.hide)
        self.update_button = QPushButton("Mettre à jour" if updater.running_exe() else "Ouvrir la page de téléchargement")
        self.update_button.setObjectName("primary")
        self.update_button.clicked.connect(self.start)
        buttons.addWidget(self.later)
        buttons.addWidget(self.update_button)
        layout.addLayout(buttons)

        self.poll = QTimer(self)  # the download thread only stores its progress; the UI polls it
        self.poll.setInterval(150)
        self.poll.timeout.connect(self._show_progress)
        self.adjustSize()
        self.place()

    def _fit(self) -> None:
        """Grows the card to show the whole (possibly long) message, then keeps it in its corner."""
        self.adjustSize()
        self.place()

    def place(self) -> None:
        parent = self.parentWidget()
        self.move(parent.width() - self.width() - 24, 24)
        self.raise_()

    def start(self) -> None:
        exe = updater.running_exe()
        if not exe:  # running from source: nothing to replace
            QDesktopServices.openUrl(QUrl(updater.REPO_URL))
            self.hide()
            return
        self.update_button.setEnabled(False)
        self.later.setEnabled(False)
        self.text.setText(f"Téléchargement de Scriptorium build {self.release.build}…")
        self.bar.show()
        self.poll.start()

        def progress(done: int, total: int) -> None:
            with self._lock:  # called on the download thread
                self._progress = (done, total)

        run_async(lambda: updater.download(self.release, progress),
                  lambda path: self._downloaded(exe, path),
                  lambda err: self._downloaded(exe, None, err))

    def _show_progress(self) -> None:
        with self._lock:
            done, total = self._progress
        if total:
            self.bar.setRange(0, total)
            self.bar.setValue(done)
        else:
            self.bar.setRange(0, 0)

    def _downloaded(self, exe: str, path: str | None, error: Exception | None = None) -> None:
        self.poll.stop()
        if error is not None:
            self.text.setText(f"<span style='color:#E5484D'>Mise à jour impossible : {error}</span>")
            self.bar.hide()
            self.update_button.setText("Ouvrir la page de téléchargement")
            self.update_button.setEnabled(True)
            self.update_button.clicked.disconnect()
            self.update_button.clicked.connect(lambda: QDesktopServices.openUrl(QUrl(updater.REPO_URL)))
            self.later.setEnabled(True)
            self._fit()
            return
        try:
            updater.install(exe, path)
            updater.relaunch(exe)
        except OSError as exc:
            self.text.setText(f"<span style='color:#E5484D'>Impossible de remplacer l'exécutable : {exc}</span>")
            self.bar.hide()
            self.update_button.setEnabled(True)
            self.later.setEnabled(True)
            self._fit()
            return
        self.text.setText("Redémarrage sur la nouvelle version…")
        QTimer.singleShot(300, self.main.close)
