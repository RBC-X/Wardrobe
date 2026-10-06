"""Build delivery archives from verified release artifacts; never ship runtime secrets."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import zipfile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT.parents[1]/"outputs"


def source_files():
    for top in ("server","shared","models","desktop","mobile"):
        for path in (ROOT/top).rglob("*"):
            relative=path.relative_to(ROOT)
            if not path.is_file() or any(p in {"build",".gradle","__pycache__",".pytest_cache","runtime","signing"} for p in relative.parts):continue
            if path.name in {"signing.properties","local.properties"} or path.suffix in {".log",".p12",".jks",".keystore",".pyc"}:continue
            yield path
    yield ROOT/"README.md"
    yield ROOT/"VALIDATION.md"
    yield from ROOT.glob("*.cmd")
    yield ROOT/".gitignore"


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    apk=ROOT/"mobile/app/build/outputs/apk/release/app-release.apk"
    if not apk.is_file():raise RuntimeError("Build and verify the signed release APK first")
    metadata=json.loads((apk.parent/"output-metadata.json").read_text())
    build=(ROOT/"mobile/app/build.gradle.kts").read_text()
    expected_code=int(re.search(r"versionCode\s*=\s*(\d+)",build).group(1))
    expected_name=re.search(r'versionName\s*=\s*"([^"]+)"',build).group(1)
    release=metadata["elements"][0]
    if release["versionCode"]!=expected_code or release["versionName"]!=expected_name:
        raise RuntimeError("The APK is from an older build. Rebuild the current release before packaging.")
    with zipfile.ZipFile(apk) as z:
        if z.testzip() is not None:raise RuntimeError("APK ZIP integrity failed")
    files=list(source_files())
    shutil.copyfile(apk,OUT/"Wardrobe.apk")
    shutil.copyfile(ROOT/"README.md",OUT/"Wardrobe-Setup.md")
    shutil.copyfile(ROOT/"VALIDATION.md",OUT/"Wardrobe-Validation.md")
    for name,mobile in (("Wardrobe-Windows.zip",False),("Wardrobe-Source.zip",True)):
        with zipfile.ZipFile(OUT/name,"w",zipfile.ZIP_DEFLATED) as z:
            for path in files:
                rel=path.relative_to(ROOT)
                if not mobile and rel.parts[0]=="mobile":continue
                z.write(path,"Wardrobe/"+rel.as_posix())
        with zipfile.ZipFile(OUT/name) as z:
            assert z.testzip() is None
            assert not any(p in n.lower() for n in z.namelist() for p in ["signing.properties","dashboard-token","wardrobe.sqlite3","auth.json",".p12"])
    with zipfile.ZipFile(OUT/"Wardrobe-signing-private.zip","w",zipfile.ZIP_DEFLATED) as z:
        z.write(ROOT/"mobile/signing.properties","mobile/signing.properties")
        for p in (ROOT/"mobile/signing").iterdir():
            if p.is_file():z.write(p,p.relative_to(ROOT).as_posix())
        z.writestr("KEEP-PRIVATE.txt","Contains your APK release key AND password. Keep offline/private. Restore these paths into the source tree for compatible APK upgrades. Never share or publish this archive.")
    artifacts=[OUT/name for name in ["Wardrobe.apk","Wardrobe-Windows.zip","Wardrobe-Source.zip","Wardrobe-Setup.md","Wardrobe-Validation.md","Wardrobe-signing-private.zip"]]
    (OUT/"SHA256SUMS.txt").write_text("".join(hashlib.sha256(p.read_bytes()).hexdigest()+"  "+p.name+"\n" for p in artifacts))
    print(json.dumps({p.name:p.stat().st_size for p in artifacts},indent=2))

if __name__=="__main__":main()
