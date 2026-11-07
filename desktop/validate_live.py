"""Local integration/resource checks. No cloud API calls or feed collection."""
from pathlib import Path
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/"server"))
import httpx
from PIL import Image,ImageDraw
from wardrobe.models import Garment
from wardrobe.storage import Store


def main():
    root=Path(tempfile.mkdtemp(prefix="delivery-check-",dir=ROOT/"runtime"))
    store=Store(root);store.set_setting("collectFeeds",False);store.close()
    env=os.environ.copy();env["WARDROBE_DATA"]=str(root)
    records=[]
    with (root/"server.log").open("wb") as log:
        process=subprocess.Popen([sys.executable,str(ROOT/"server/run.py"),"--port","8766"],env=env,cwd=ROOT,stdout=log,stderr=log)
    try:
        with httpx.Client(base_url="http://127.0.0.1:8766",timeout=30) as c:
            for _ in range(80):
                try:
                    if c.get("/v1/health").status_code==200:break
                except httpx.TransportError:pass
                time.sleep(.25)
            else:raise RuntimeError("Validation server did not start")
            admin={"X-Wardrobe-Admin":(root/"dashboard-token").read_text()}
            def check(name,condition,details=""):
                records.append({"check":name,"passed":bool(condition),"details":details})
                print(("PASS " if condition else "FAIL ")+name+" "+details,flush=True)
            check("admin authentication",c.get("/admin/status").status_code==401)
            code=c.post("/admin/pair",headers=admin,json={"url":"https://delivery-check.example.ts.net"}).json()["pairing"]["code"]
            body={"code":code,"deviceName":"Delivery validation"}
            pair=c.post("/v1/pair",json=body);pair.raise_for_status();device=pair.json();auth={"Authorization":"Bearer "+device["token"]}
            check("single-use pairing",c.post("/v1/pair",json=body).status_code==403)
            image=Image.new("RGB",(320,320),"white");draw=ImageDraw.Draw(image);draw.polygon([(80,60),(130,40),(190,40),(240,60),(285,125),(235,155),(215,125),(215,285),(105,285),(105,125),(85,155),(35,125)],fill="#3d6699")
            out=io.BytesIO();image.save(out,"JPEG");data=out.getvalue();id_=hashlib.sha256(data).hexdigest()
            check("truncated photo rejected",c.put("/v1/media/"+id_,headers=auth,content=data[:-20]).status_code>=400)
            check("repeat photo upload",all(c.put("/v1/media/"+id_,headers=auth,content=data).status_code==200 for _ in range(2)))
            items=[Garment(id=f"item-{i}",name=f"Clothing {i}",category="top",imageIds=[id_] if i==0 else []).model_dump() for i in range(601)]
            cursor=0;start=time.perf_counter()
            for batch in range(0,601,300):
                changes=[{"changeId":f"change-{batch+j}","kind":"garment","recordId":v["id"],"revision":v["revision"],"payload":v} for j,v in enumerate(items[batch:batch+300])]
                payload={"cursor":cursor,"changes":changes}
                a=c.post("/v1/sync",headers=auth,json=payload);a.raise_for_status();b=c.post("/v1/sync",headers=auth,json=payload);b.raise_for_status()
                check(f"batch {batch//300+1} interrupted-response retry",all(a.json()[key]==b.json()[key] for key in ("accepted","cursor","records","rulesHash")))
                cursor=a.json()["cursor"]
            check("601 changes accepted",cursor==601,f"{time.perf_counter()-start:.3f}s")
            check("photo hash roundtrip",hashlib.sha256(c.get("/v1/media/"+id_,headers=auth).content).hexdigest()==id_)
            crop_start=time.perf_counter();framed=c.post("/v1/photos/suggest-crop",headers=auth,content=data)
            box=framed.json() if framed.status_code==200 else {}
            check("automatic photo framing",framed.status_code==200 and box.get("found") is True and all(0<=box.get(key,1)<=.4 for key in ("left","top","right","bottom")) and box.get("left",0)>.01 and box.get("top",0)>.01,f"{time.perf_counter()-crop_start:.3f}s; "+str({key:box.get(key) for key in ("left","top","right","bottom")}))
            check("unconfigured AI locked",c.post("/v1/jobs",headers=auth,json={"kind":"style","provider":"openai","requestId":"locked"}).status_code==403)
            start=time.perf_counter();r=c.post("/v1/jobs",headers=auth,json={"kind":"cutout","provider":"local","imageIds":[id_],"garmentId":"item-0","revision":1,"requestId":"cutout-check"});r.raise_for_status();job=r.json()
            end=time.monotonic()+60
            while job["status"] in ("queued","running") and time.monotonic()<end:
                time.sleep(.25);job=c.get("/v1/jobs/"+job["id"],headers=auth).json()
            elapsed=time.perf_counter()-start
            check("real local cutout",job["status"]=="completed",f"{elapsed:.3f}s; "+job.get("error",""))
            if job["status"]=="completed":
                result=c.get("/v1/media/"+job["result"]["imageId"],headers=auth);result.raise_for_status()
                (root/"cutout.png").write_bytes(result.content)
                with Image.open(io.BytesIO(result.content)) as png:check("alpha PNG and revision",png.mode=="RGBA" and job["result"]["revision"]==1)
            detect_start=time.perf_counter()
            r=c.post("/v1/jobs",headers=auth,json={"kind":"detect","provider":"local","imageIds":[id_],"garmentId":"item-0","revision":1,"requestId":"detect-check"});r.raise_for_status();job=r.json()
            end=time.monotonic()+90
            while job["status"] in ("queued","running") and time.monotonic()<end:
                time.sleep(.25);job=c.get("/v1/jobs/"+job["id"],headers=auth).json()
            detection=job.get("result") or {}
            check("background-free detection",job["status"]=="completed" and detection.get("backgroundExcluded") is True and detection.get("category") in ("top","bottom","onepiece","outerwear","shoes","accessory"),f"{time.perf_counter()-detect_start:.3f}s; "+job.get("error",""))
            if job["status"]=="completed":
                color=detection.get("color","#000000")
                check("detection read garment colour and shape",bool(detection.get("shape")) and all(abs(int(color[position:position+2],16)-target)<=45 for position,target in ((1,0x3d),(3,0x66),(5,0x99))),str(color)+" · "+str(detection.get("shape")))
                preview=c.get("/v1/media/"+detection["cutoutId"],headers=auth);preview.raise_for_status()
                (root/"detection.png").write_bytes(preview.content)
                with Image.open(io.BytesIO(preview.content)) as png:check("trimmed transparent detection",png.mode=="RGBA" and png.width<320 and png.height<320,f"{png.width}x{png.height}")
            if os.name=="nt":
                saved=c.put("/v1/providers/openai",headers=auth,json={"enabled":False,"apiKey":"delivery-check-placeholder","freeOnly":True})
                check("AI key entered from the phone",saved.status_code==200 and saved.json().get("hasKey") is True and "delivery-check-placeholder" not in saved.text)
                check("agent providers remain PC-only",c.put("/v1/providers/codex",headers=auth,json={"enabled":True}).status_code==403)
            backup=c.get("/admin/backup",headers=admin);backup.raise_for_status()
            with zipfile.ZipFile(io.BytesIO(backup.content)) as z:
                check("backup excludes credentials",not any("token" in n or "sqlite" in n for n in z.namelist()))
            restored=c.post("/admin/restore",headers=admin,content=backup.content);restored.raise_for_status();check("restore 601 records",restored.json()=={"restored":601,"credentialsRestored":False})
            c.post("/admin/revoke/"+device["deviceId"],headers=admin).raise_for_status()
            check("revoked token denied",c.get("/v1/capabilities",headers=auth).status_code==401)
            if os.name=="nt":
                ps=subprocess.run(["powershell.exe","-NoProfile","-Command",f"$children=Get-CimInstance Win32_Process -Filter 'ParentProcessId = {process.pid}'; $ids=@({process.pid})+@($children.ProcessId); Get-Process -Id $ids | Select-Object Id,WorkingSet64,PeakWorkingSet64,PrivateMemorySize64 | ConvertTo-Json -Compress"],capture_output=True,text=True,check=True)
                memory=json.loads(ps.stdout);records.append({"check":"server memory bytes","metrics":memory});print("MEMORY",memory)
            c.post("/admin/shutdown",headers=admin).raise_for_status()
            process.wait(timeout=35);check("graceful stop",process.returncode==0)
        output=ROOT/"runtime/final-checks/live-validation.json";output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps({"checks":records,"dataDirectory":str(root)},indent=2))
        print("Saved",output)
        if any(r.get("passed") is False for r in records):sys.exit(1)
    finally:
        if process.poll() is None:process.terminate();process.wait(timeout=10)

if __name__=="__main__":main()
