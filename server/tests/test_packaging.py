import importlib.util
from pathlib import Path


def test_delivery_selection_excludes_secrets_and_includes_controls():
    root=Path(__file__).resolve().parents[2]
    spec=importlib.util.spec_from_file_location("wardrobe_package",root/"desktop/package.py")
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    names={p.relative_to(root).as_posix() for p in module.source_files()}
    assert "Setup.cmd" in names and "Open Dashboard.cmd" in names and "desktop/control.py" in names
    assert not any("signing.properties" in n or "/runtime/" in n or n.endswith((".p12",".jks",".keystore")) for n in names)
    assert "mobile/gradlew" in names
