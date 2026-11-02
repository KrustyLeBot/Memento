from __future__ import annotations

import io
import sys
import traceback
from pathlib import Path


def _show_error(title: str, text: str) -> None:
    """A windowed exe has no console: an error nobody sees is an app that "does nothing"."""
    print(f"{title}\n{text}", file=sys.stderr)
    try:
        from PySide6.QtWidgets import QApplication, QMessageBox
        if QApplication.instance() is not None:
            box = QMessageBox(QMessageBox.Icon.Critical, "Scriptorium", title)
            box.setDetailedText(text)
            box.exec()
            return
    except Exception:  # Qt itself is what failed: fall back to the system's own message box below
        print(traceback.format_exc(), file=sys.stderr)
    if sys.platform == "win32":
        import ctypes
        ctypes.windll.user32.MessageBoxW(None, f"{title}\n\n{text[-1500:]}", "Scriptorium", 0x10)


def _excepthook(kind, value, tb) -> None:
    # Called for an exception escaping a Qt slot or a timer: shown, and the app keeps running.
    _show_error(f"Erreur inattendue : {value}", "".join(traceback.format_exception(kind, value, tb)))


def main() -> int:
    # A "windowed" executable has no console: some libraries crash without stdout/stderr.
    if sys.stdout is None:
        sys.stdout = io.StringIO()
    if sys.stderr is None:
        sys.stderr = io.StringIO()
    if "--selftest" in sys.argv:
        from .selftest import run
        rest = sys.argv[sys.argv.index("--selftest") + 1:]
        return run(rest[0] if rest else None)
    try:
        return _run()
    except Exception as error:
        _show_error(f"Scriptorium n'a pas pu démarrer : {error}", traceback.format_exc())
        return 1


def _run() -> int:
    import shiboken6
    from PySide6.QtGui import QIcon
    from PySide6.QtWidgets import QApplication, QMessageBox

    from .config import Config
    from .core.store import Store
    from .ui import theme
    from .services import maphost
    from .ui.main_window import MainWindow, ask_workdir

    maphost.register_scheme()  # before the QApplication, as Qt requires
    app = QApplication(sys.argv)
    app.setApplicationName("Scriptorium")
    app.setStyle("Fusion")
    app.setWindowIcon(QIcon(str(Path(__file__).parent / "assets" / "icon.png")))
    sys.excepthook = _excepthook

    config = Config()
    theme.apply_theme(app, config.theme)
    workdir = config.workdir
    if workdir is None or not workdir.is_dir() or not any(workdir.iterdir()):
        if workdir is not None:
            # A missing or emptied folder is usually one that moved (e.g. renamed by the sync): ask again.
            QMessageBox.warning(None, "Scriptorium", f"Le dossier {workdir} est introuvable ou vide. Choisis le dossier "
                                                     "de tes notes.")
        workdir = ask_workdir(None, first_run=config.workdir is None, start=workdir.parent if workdir else None)
        if workdir is None:
            return 0
        config.workdir = workdir

    window = MainWindow(config, Store(workdir))
    window.show()
    code = app.exec()
    # The map's pages must go before their web profile, and the profile before the QApplication: deleted
    # explicitly, since the window's signal connections keep it alive past a mere `del`.
    shiboken6.delete(window)
    maphost.release_profile()
    return code


if __name__ == "__main__":
    sys.exit(main())
