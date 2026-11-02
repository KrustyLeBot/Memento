"""The map page is found in the image's shallow layout (/app/memento_web/modules) as in a checkout."""
from pathlib import Path

from memento_web.modules.maphost import default_map_dir


def _layout(root: Path, package: Path) -> Path:
    module = package / "modules" / "maphost.py"
    module.parent.mkdir(parents=True)
    module.write_text("")
    page = root / "web" / "map" / "index.html"
    page.parent.mkdir(parents=True)
    page.write_text("<html></html>")
    return module


def test_image_layout(tmp_path):
    # /app/memento_web/modules/maphost.py and /app/web/map: only 3 levels, the crash seen on the NAS
    module = _layout(tmp_path, tmp_path / "memento_web")
    assert default_map_dir(module) == tmp_path / "web" / "map"


def test_checkout_layout(tmp_path):
    module = _layout(tmp_path, tmp_path / "webapp" / "server" / "memento_web")
    assert default_map_dir(module) == tmp_path / "web" / "map"
