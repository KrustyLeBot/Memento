"""The spec's file filter, against the names of the Windows wheels (PySide6 6.12: WebEngine's ANGLE is inside
Qt6WebEngineCore.dll, there is no libEGL/libGLESv2/d3dcompiler to keep)."""
from pathlib import Path

import pytest

pytest.importorskip("PyInstaller")

SPEC = Path(__file__).resolve().parent.parent / "memento.spec"


@pytest.fixture(scope="module")
def keep():
    text = SPEC.read_text(encoding="utf-8")
    scope: dict = {}
    exec(text[:text.index("\na = Analysis(")], scope)  # the constants and keep(), not the build itself
    return scope["keep"]


@pytest.mark.parametrize("dest", [
    "PySide6/Qt6WebEngineCore.dll", "PySide6/Qt6WebEngineWidgets.dll", "PySide6/QtWebEngineProcess.exe",
    "PySide6/Qt6Positioning.dll", "PySide6/Qt6WebChannel.dll", "PySide6/Qt6Core.dll",
    "PySide6/resources/icudtl.dat", "PySide6/resources/qtwebengine_resources.pak",
    "PySide6/resources/qtwebengine_resources_100p.pak", "PySide6/resources/qtwebengine_resources_200p.pak",
    "PySide6/resources/v8_context_snapshot.bin",
    "PySide6/translations/qtwebengine_locales/fr.pak", "PySide6/translations/qtwebengine_locales/en-US.pak",
    "PySide6/plugins/platforms/qwindows.dll", "PySide6/plugins/position/qtposition_winrt.dll",
    "PySide6/plugins/tls/qschannelbackend.dll", "PySide6/plugins/imageformats/qjpeg.dll",
    "PySide6/msvcp140.dll", "PySide6/vcruntime140.dll", "tzdata/zoneinfo/Europe/Paris",
])
def test_kept(keep, dest):
    assert keep(dest)


@pytest.mark.parametrize("dest", [
    "PySide6/resources/qtwebengine_devtools_resources.pak",
    "PySide6/resources/qtwebengine_devtools_resources.debug.pak",  # 76 MB, extracted at every start
    "PySide6/resources/qtwebengine_resources.debug.pak", "PySide6/resources/v8_context_snapshot.debug.bin",
    "PySide6/translations/qtwebengine_locales/de.pak", "PySide6/Qt6Quick3D.dll", "PySide6/Qt6Multimedia.dll",
    "PySide6/plugins/multimedia/ffmpegmediaplugin.dll", "PySide6/plugins/imageformats/qtiff.dll",
])
def test_dropped(keep, dest):
    assert not keep(dest)
