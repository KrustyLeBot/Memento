from __future__ import annotations

import io
import sys
from pathlib import Path


def main() -> int:
    # A "windowed" executable has no console: some libraries crash without stdout/stderr.
    if sys.stdout is None:
        sys.stdout = io.StringIO()
    if sys.stderr is None:
        sys.stderr = io.StringIO()

    from PySide6.QtGui import QIcon
    from PySide6.QtWidgets import QApplication, QMessageBox

    from .config import Config
    from .core.store import Store
    from .ui import theme
    from .ui.main_window import MainWindow, ask_workdir

    app = QApplication(sys.argv)
    app.setApplicationName("Scriptorium")
    app.setStyle("Fusion")
    app.setWindowIcon(QIcon(str(Path(__file__).parent / "assets" / "icon.png")))

    config = Config()
    theme.apply_theme(app, config.theme)
    workdir = config.workdir
    if workdir is None or not workdir.is_dir():
        if workdir is not None:
            QMessageBox.warning(None, "Scriptorium", f"Le dossier {workdir} est introuvable. Choisis-en un autre.")
        workdir = ask_workdir(None, first_run=workdir is None)
        if workdir is None:
            return 0
        config.workdir = workdir

    window = MainWindow(config, Store(workdir))
    window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
