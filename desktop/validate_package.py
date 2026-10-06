"""Verify a fresh Windows distribution in an isolated folder and database.

Uses public dependency downloads but never cloud AI or the owner's server records.
"""
from pathlib import Path
import hashlib,json,os,subprocess,sys,tempfile,time,zipfile
import urllib.request

ROOT=Path(__file__).resolve().parents[1]

def main():
    archive=ROOT.parents[1]/"outputs/Wardrobe-Windows.zip"
    run=Path(tempfile.mkdtemp(prefix="codex-package-",dir=ROOT/"runtime"))
    with zipfile.ZipFile(archive) as z:z.extractall(run)
    folder=run/"Wardrobe";data=run/"data";data.mkdir()
    environment=os.environ.copy();environment["WARDROBE_DATA"]=str(data)
    # Skip external feed collection for this local install check.
    sys.path.insert(0,str(folder/"server"))
    from wardrobe.storage import Store
    store=Store(data);store.set_setting("collectFeeds",False);store.close()
    controls=[]
    def execute(command,label,timeout=120):
        with (run/(label+".log")).open("w",encoding="utf-8") as log:
            result=subprocess.run(command,cwd=folder,env=environment,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,timeout=timeout)
        if result.returncode:raise RuntimeError(label+" failed; see "+str(run/(label+".log")))
    # Run the shipped setup, without a test-only venv link or global site packages.
    execute(["cmd.exe","/d","/c","Setup.cmd"],"setup",240)
    python=folder/".venv/Scripts/python.exe"
    execute([str(python),"-c","import sys; assert sys.prefix != sys.base_prefix"],"venv")
    try:
        for action in ("start","status"):
            execute([str(python),"desktop/control.py",action],action);controls.append(action)
        with urllib.request.urlopen("http://127.0.0.1:8765/v1/health",timeout=5) as response:
            health=json.load(response)
        assert health["app"]=="Wardrobe" and health["version"]=="1.0.1"
        token=(data/"dashboard-token").read_text()
        request=urllib.request.Request("http://127.0.0.1:8765/admin/status",headers={"X-Wardrobe-Admin":token})
        with urllib.request.urlopen(request,timeout=5) as response:status=json.load(response)
        assert not status["devices"] and not status["collectFeeds"]
        assert all(not provider["text"] for provider in status["providers"])
        execute([str(python),"desktop/control.py","stop"],"stop");controls.append("stop")
        execute([str(python),"desktop/control.py","status"],"stopped");controls.append("status after stop")
        assert "stopped" in (run/"stopped.log").read_text()
        selected={str(path.relative_to(folder)).replace("\\","/"):hashlib.sha256(path.read_bytes()).hexdigest() for top in ("server","desktop","shared","models") for path in (folder/top).rglob("*") if path.is_file() and "__pycache__" not in path.parts}
        report={"passed":True,"freshDependencyInstall":True,"controls":controls,"version":health["version"],"isolatedData":str(data),"packageSourceHashes":selected}
        (ROOT/"runtime/final-checks/codex-package.json").write_text(json.dumps(report,indent=2))
        print("Fresh Setup.cmd, model loading, start, authenticated status, locked AI, stop and stopped status passed.")
    finally:
        # Only the authenticated server using this test data can receive this shutdown.
        execute([str(python),"desktop/control.py","stop"],"cleanup")

if __name__=="__main__":main()
