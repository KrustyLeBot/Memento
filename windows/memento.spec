# PyInstaller build of Memento.exe: one file, with Qt WebEngine (the activity map, web/map), and only the
# parts of Qt the app uses. PySide6's hooks bring the whole of Qt's QML world, every translation and the
# developer tools along with WebEngine: they are filtered out below.
#   pyinstaller --noconfirm memento.spec
import re

from PyInstaller.utils.hooks import collect_data_files, collect_submodules

# Qt libraries the widgets and WebEngine really load (from their imports); the rest of Qt goes.
QT_LIBS = {
    "Core", "Gui", "Widgets", "Network", "Svg", "OpenGL", "OpenGLWidgets", "PrintSupport", "Positioning",
    "Qml", "QmlMeta", "QmlModels", "QmlWorkerScript", "Quick", "QuickWidgets", "WebChannel",
    "WebEngineCore", "WebEngineWidgets",
    "DBus", "XcbQpa", "WaylandClient", "EglFSDeviceIntegration",  # Linux only, for local runs
}
# Qt plugins: the platform, the image formats of photos, TLS and network state, the location service.
PLUGINS = {"platforms", "imageformats", "iconengines", "tls", "networkinformation", "platformthemes", "position",
           "xcbglintegrations", "egldeviceintegrations", "generic", "platforminputcontexts", "styles"}
IMAGE_FORMATS = ("qjpeg", "qgif", "qico", "qwebp", "qsvg")
LANGUAGES = ("fr", "en", "en-US")
# The map page and its vendored MapLibre (web/map/dev is a developer tool, not shipped).
MAP_FILES = ("index.html", "map.js", "map.css", "maplibre-gl.js", "maplibre-gl.css", "LICENSE-maplibre.txt")


def keep(dest: str) -> bool:
    path = dest.replace("\\", "/")
    name = path.rsplit("/", 1)[-1]
    if "PySide6/" not in path and not path.startswith("PySide6"):
        return True
    if "/qml/" in path or path.endswith("/qml"):
        return False  # no QML in Mémento: WebEngineWidgets does not need the QML modules
    lib = re.match(r"^(?:lib)?Qt6([A-Za-z0-9]+)\.(?:dll|so\.6|so)", name)
    if lib:
        return lib.group(1) in QT_LIBS
    if "/plugins/" in path:
        kind = path.split("/plugins/", 1)[1].split("/", 1)[0]
        if kind not in PLUGINS:
            return False
        if kind == "imageformats":
            return name.startswith(tuple(f"{f}." for f in IMAGE_FORMATS)) or any(name.startswith(f"lib{f}.") for f in IMAGE_FORMATS)
        return True
    if "/qtwebengine_locales/" in path:
        return name.rsplit(".", 1)[0] in LANGUAGES
    if "/translations/" in path:
        return bool(re.search(r"_(fr)\.qm$", name))
    if name.startswith("qtwebengine_devtools_resources"):
        return False  # only for the web inspector (its .debug.pak alone is 76 MB on Windows)
    if ".debug." in name:
        return False  # *.debug.pak / *.debug.bin: Chromium's resources for debug builds of Qt, never loaded here
    if name == "opengl32sw.dll":
        return False  # software OpenGL fallback: widgets draw in software anyway, WebEngine has its own
    return True


a = Analysis(
    ["run.py"],
    # tzdata: Windows has no time zone database, zoneinfo reads this package's files (resolved as packages:
    # tzdata.zoneinfo.Europe...). Without it every zone of the agenda fails; `--selftest` checks it.
    datas=[("memento_win/assets", "memento_win/assets")] + [(f"../web/map/{f}", "web/map") for f in MAP_FILES]
    + collect_data_files("tzdata"),
    hiddenimports=collect_submodules("tzdata"),
    excludes=["tkinter", "lxml", "cryptography", "OpenSSL", "numpy", "PIL", "html5lib", "chardet", "brotli",
              "unittest", "pydoc", "xmlrpc", "lib2to3", "test",
              # Python bindings nothing imports (their Qt libraries stay, WebEngine loads them).
              "PySide6.QtOpenGL", "PySide6.QtQml", "PySide6.QtQuick", "PySide6.QtQuickWidgets", "PySide6.QtDBus"],
    noarchive=False,
)
before = len(a.binaries) + len(a.datas)
a.binaries = [x for x in a.binaries if keep(x[0])]
a.datas = [x for x in a.datas if keep(x[0])]
print(f"memento.spec: kept {len(a.binaries) + len(a.datas)} of {before} files")

pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, a.binaries, a.datas, [],
    name="Memento",  # the exe file name: ASCII
    icon="memento_win/assets/icon.ico",
    console=False,
    upx=False,  # UPX breaks Qt libraries and alarms antivirus software
    strip=False,
)
