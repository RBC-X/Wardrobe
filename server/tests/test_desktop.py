import importlib.util
from pathlib import Path


def load_control():
    path=Path(__file__).resolve().parents[2]/"desktop/control.py"
    spec=importlib.util.spec_from_file_location("wardrobe_control",path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


def test_optional_startup_touches_only_wardrobe_entry(tmp_path,monkeypatch):
    module=load_control();monkeypatch.setenv("APPDATA",str(tmp_path));module.startup(True)
    folder=tmp_path/"Microsoft/Windows/Start Menu/Programs/Startup";entry=folder/"Wardrobe.cmd"
    assert entry.is_file() and "Start Server.cmd" in entry.read_text()
    unrelated=folder/"unrelated.cmd";unrelated.write_text("keep me")
    module.startup(False)
    assert not entry.exists() and unrelated.read_text()=="keep me"
