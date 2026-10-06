from __future__ import annotations
import base64
import hashlib
import io
import json
import os
import queue
import secrets
import threading
import time
import uuid
import zipfile
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import FileResponse, Response, JSONResponse
from fastapi.staticfiles import StaticFiles
from .engine import RULES_PATH, generate, gaps
from .images import cutout, image_path, import_remote_image, save_image
from .models import JobRequest, PairRequest, ProviderConfig, SyncRequest
from .providers import Providers
from .security import digest, pairing_url
from .storage import Store, canonical, timestamp
from .webdata import FEEDS, collect_feeds, extract_product

ROOT=Path(__file__).resolve().parent

class Worker:
    def __init__(self,store,providers):
        self.store=store;self.providers=providers;self.pending=queue.Queue();self.stop=threading.Event()
        store.execute("UPDATE jobs SET status='failed',error='The server restarted; submit a new request',updatedAt=? WHERE status IN ('queued','running')",(timestamp(),))
        self.thread=threading.Thread(target=self.loop,daemon=True)
        self.thread.start()

    def loop(self):
        next_feed=0
        while not self.stop.is_set():
            try: id_=self.pending.get(timeout=1)
            except queue.Empty:
                if self.stop.is_set(): break
                if self.store.get_setting("collectFeeds",True) and time.monotonic()>=next_feed:
                    collect_feeds(self.store,cancel=self.stop.is_set)
                    if self.stop.is_set(): break
                    next_feed=time.monotonic()+6*3600
                    for provider in ("gemini","openai","codex","claude"):
                        config,_=self.providers.config(provider)
                        if config.get("backgroundAnalysis") and self.providers.capabilities(provider)["text"]:
                            try: self.enqueue(JobRequest(kind="trend_analysis",provider=provider,requestId="background-"+str(uuid.uuid4())))
                            except ValueError: break  # A full queue must not kill the worker.
                            break
                continue
            rows=self.store.query("SELECT * FROM jobs WHERE id=?",(id_,))
            if not rows or rows[0]["status"]!="queued": continue
            job=rows[0];request=JobRequest.model_validate_json(job["request"])
            self.store.execute("UPDATE jobs SET status='running',updatedAt=? WHERE id=?",(timestamp(),id_))
            def cancelled():
                return self.stop.is_set() or self.store.query("SELECT status FROM jobs WHERE id=?",(id_,))[0]["status"]=="cancelled"
            try:
                if request.kind=="cutout":
                    if not request.imageIds: raise ValueError("Choose an image first")
                    if request.garmentId:
                        garment=next((g for g in self.store.records("garment") if g["id"]==request.garmentId),None)
                        if not garment or garment["revision"]!=request.revision or request.imageIds[0] not in garment.get("imageIds",[]): raise ValueError("This item or its photo changed; request a fresh cutout")
                    result=cutout(self.store,request.imageIds[0])
                    result.update(garmentId=request.garmentId,revision=request.revision)
                else: result=self.providers.run(request,id_,cancelled)
                if not cancelled(): self.store.execute("UPDATE jobs SET status='completed',result=?,updatedAt=? WHERE id=?",(canonical(result),timestamp(),id_))
            except Exception as error:
                if not cancelled(): self.store.execute("UPDATE jobs SET status='failed',error=?,updatedAt=? WHERE id=?",(str(error)[:400],timestamp(),id_))

    def enqueue(self,request):
        # Duplicate submissions can overlap when a phone retries after a lost response.
        with self.store.lock:
            return self._enqueue_locked(request)

    def _enqueue_locked(self,request):
        existing=self.store.query("SELECT id,request FROM jobs WHERE requestId=?",(request.requestId,))
        if existing:
            if existing[0]["request"]!=canonical(request.model_dump()): raise ValueError("A request retry changed its payload")
            return existing[0]["id"]
        if self.pending.qsize()>=25: raise ValueError("Job queue is full; try again later")
        id_=str(uuid.uuid4())
        self.store.execute("INSERT INTO jobs VALUES(?,?,?,?,?,?,?,?,?,?)",(id_,request.requestId,request.kind,request.provider,"queued",canonical(request.model_dump()),"","",timestamp(),timestamp()))
        self.pending.put(id_)
        return id_

def create_app(root: Path | None=None,background=True):
    data=root or Path(os.environ.get("WARDROBE_DATA",str(Path(os.environ.get("LOCALAPPDATA",str(Path.home()))) / "WardrobeServer")))
    store=Store(data);providers=Providers(store)
    admin_token=secrets.token_urlsafe(32)
    # Only the launcher reads this file; it is never served or included in backups.
    (data/"dashboard-token").write_text(admin_token,encoding="utf-8")
    worker=Worker(store,providers) if background else None

    @asynccontextmanager
    async def lifespan(app):
        yield
        if worker:
            worker.stop.set();worker.thread.join(timeout=25)
        # An in-flight cloud request can finish after shutdown; never close its DB underneath it.
        if not worker or not worker.thread.is_alive(): store.close()

    app=FastAPI(title="Wardrobe private companion",version="1.0.1",docs_url=None,redoc_url=None,openapi_url=None,lifespan=lifespan)
    app.state.store=store;app.state.providers=providers;app.state.admin_token=admin_token;app.state.worker=worker
    limits={}

    @app.middleware("http")
    async def security_headers(request,call_next):
        maximum=100_000_000 if request.url.path=="/admin/restore" else 12_000_000
        if request.headers.get("content-length","").isdigit() and int(request.headers["content-length"])>maximum:
            return JSONResponse({"detail":"Request is too large"},status_code=413)
        if request.url.path=="/v1/pair":
            address=request.client.host if request.client else "unknown"
            count,start=limits.get(address,(0,time.monotonic()))
            if time.monotonic()-start>60: count,start=0,time.monotonic()
            limits[address]=(count+1,start)
            if count>=10: return JSONResponse({"detail":"Too many pairing attempts; wait a minute"},status_code=429)
        response=await call_next(request)
        response.headers["Cache-Control"]="no-store"
        response.headers["X-Content-Type-Options"]="nosniff"
        response.headers["Referrer-Policy"]="no-referrer"
        response.headers["Content-Security-Policy"]="default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'"
        return response

    def admin(x_wardrobe_admin:Annotated[str|None,Header()]=None):
        if not x_wardrobe_admin or not secrets.compare_digest(x_wardrobe_admin,admin_token): raise HTTPException(401,"Open the PC dashboard using Open Dashboard.cmd")

    def device(authorization:Annotated[str|None,Header()]=None):
        token=(authorization or "").removeprefix("Bearer ")
        rows=store.query("SELECT id FROM devices WHERE tokenHash=? AND revoked=0",(digest(token),))
        if not token or not rows: raise HTTPException(401,"Pair this phone again on your PC")
        return rows[0]["id"]

    def job_record(id_):
        rows=store.query("SELECT id,kind,provider,status,result,error,createdAt,updatedAt FROM jobs WHERE id=?",(id_,))
        if not rows: raise HTTPException(404,"Job not found")
        row=rows[0];row["result"]=json.loads(row["result"]) if row["result"] else None
        return row

    @app.exception_handler(ValueError)
    async def invalid(request,error):
        return JSONResponse({"detail":str(error)},status_code=400)

    @app.get("/")
    def dashboard(): return FileResponse(ROOT/"static"/"index.html")
    app.mount("/static",StaticFiles(directory=ROOT/"static"),name="static")

    @app.get("/v1/health")
    def health(): return {"app":"Wardrobe","version":"1.0.1","schemaVersion":1,"online":True}

    @app.get("/admin/status",dependencies=[Depends(admin)])
    def status():
        return {"devices":store.query("SELECT id,name,createdAt,revoked FROM devices"),"serverUrl":store.get_setting("serverUrl",""),"lastFeedCheck":store.get_setting("lastFeedCheck",""),"feedStatus":store.get_setting("feedStatus",[]),"collectFeeds":store.get_setting("collectFeeds",True),"feedCount":store.query("SELECT COUNT(*) AS count FROM feed_items")[0]["count"],"providers":[providers.capabilities(p) for p in ("codex","claude","gemini","openai")],"jobs":[job_record(r["id"]) for r in store.query("SELECT id FROM jobs ORDER BY createdAt DESC LIMIT 10")]}

    @app.post("/admin/shutdown",dependencies=[Depends(admin)])
    def shutdown(request:Request):
        if not request.client or request.client.host not in ("127.0.0.1","::1","testclient"): raise HTTPException(403,"Stop the server from this PC")
        stop=getattr(app.state,"shutdown",None)
        if not stop: raise HTTPException(503,"Shutdown control is unavailable in this host")
        stop();return {"stopping":True}

    @app.post("/admin/pair",dependencies=[Depends(admin)])
    def new_pair(body:dict):
        import qrcode
        from qrcode.image.svg import SvgPathImage
        url=pairing_url(str(body.get("url","")))
        code=secrets.token_urlsafe(32)
        store.execute("DELETE FROM pairing WHERE expires<? OR used=1",(time.time(),))
        store.execute("INSERT INTO pairing VALUES(?,?,0)",(digest(code),time.time()+300))
        store.set_setting("serverUrl",url)
        payload={"version":1,"url":url,"code":code}
        image=qrcode.make(canonical(payload),image_factory=SvgPathImage)
        output=io.BytesIO();image.save(output)
        return {"pairing":payload,"qr":"data:image/svg+xml;base64,"+base64.b64encode(output.getvalue()).decode(),"expiresIn":300}

    @app.post("/v1/pair")
    def pair(request:PairRequest):
        with store.lock,store.db:
            row=store.db.execute("SELECT expires,used FROM pairing WHERE codeHash=?",(digest(request.code),)).fetchone()
            if not row or row[0]<time.time() or row[1]: raise HTTPException(403,"Pairing code expired or already used")
            token=secrets.token_urlsafe(32);id_=str(uuid.uuid4())
            store.db.execute("UPDATE pairing SET used=1 WHERE codeHash=?",(digest(request.code),))
            store.db.execute("INSERT INTO devices VALUES(?,?,?,?,0)",(id_,digest(token),request.deviceName,timestamp()))
        return {"token":token,"deviceId":id_,"schemaVersion":1}

    @app.post("/admin/revoke/{id_}",dependencies=[Depends(admin)])
    def revoke(id_:str):
        store.execute("UPDATE devices SET revoked=1 WHERE id=?",(id_,));return {"revoked":True}

    @app.post("/admin/settings",dependencies=[Depends(admin)])
    def settings(body:dict):
        store.set_setting("collectFeeds",bool(body.get("collectFeeds",True)))
        return {"saved":True}

    @app.put("/admin/providers/{id_}",dependencies=[Depends(admin)])
    def configure(id_:str,config:ProviderConfig):
        providers.configure(id_,config);return providers.capabilities(id_)

    @app.post("/admin/providers/{id_}/test",dependencies=[Depends(admin)])
    def test_provider(id_:str): return providers.test(id_)

    @app.post("/admin/providers/{id_}/preview-probe",dependencies=[Depends(admin)])
    def preview_probe(id_:str):
        if not worker: raise ValueError("Worker is unavailable")
        if not providers.capabilities(id_).get("imageProbe"): raise ValueError("This provider cannot probe image generation with its current usage settings")
        return {"id":worker.enqueue(JobRequest(kind="preview_probe",provider=id_,requestId=str(uuid.uuid4())))}

    @app.get("/v1/capabilities",dependencies=[Depends(device)])
    def capabilities():
        return {"providers":[providers.capabilities(p) for p in ("codex","claude","gemini","openai")],"cutout":(ROOT.parents[1]/"models"/"u2netp.onnx").exists()}

    @app.post("/v1/sync",dependencies=[Depends(device)])
    def sync(request:SyncRequest):
        for change in request.changes:
            if change.deleted: continue
            images=list(change.payload.get("imageIds",[]))
            for key in ("cutoutId","referenceImageId","previewImageId"):
                if change.payload.get(key): images.append(change.payload[key])
            for id_ in images:
                if not image_path(store,id_).exists(): raise HTTPException(409,"Upload the referenced images before refreshing wardrobe records")
        result=store.sync(request)
        rules=RULES_PATH.read_text(encoding="utf-8")
        result.update(rulesJson=rules,rulesHash=hashlib.sha256(rules.encode()).hexdigest(),trends=[json.loads(r["payload"]) for r in store.query("SELECT payload FROM trends")],feedItems=store.query("SELECT source,title,url,publishedAt FROM feed_items ORDER BY publishedAt DESC LIMIT 40"),serverTime=timestamp(),jobs=[job_record(r["id"]) for r in store.query("SELECT id FROM jobs ORDER BY createdAt DESC LIMIT 50")])
        return result

    @app.put("/v1/media/{id_}",dependencies=[Depends(device)])
    async def upload(id_:str,request:Request):
        data=bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data)>8_000_000: raise HTTPException(413,"Image exceeds 8 MB")
        return {"imageId":save_image(store,bytes(data),id_)}

    @app.get("/v1/media/{id_}",dependencies=[Depends(device)])
    def download(id_:str):
        path=image_path(store,id_)
        if not path.is_file(): raise HTTPException(404,"Image not found")
        return FileResponse(path,media_type="application/octet-stream")

    @app.post("/v1/products/import",dependencies=[Depends(device)])
    def product(body:dict): return extract_product(str(body.get("url","")))

    @app.post("/v1/products/image",dependencies=[Depends(device)])
    def product_image(body:dict): return {"imageId":import_remote_image(store,str(body.get("url","")))}

    @app.get("/v1/recommendations",dependencies=[Depends(device)])
    def recommendations(): return {"gaps":gaps(store.records("garment"))}

    @app.post("/v1/jobs",dependencies=[Depends(device)])
    def submit(request:JobRequest):
        if not worker: raise ValueError("Worker is unavailable")
        if request.kind in ("trend_analysis","preview_probe"): raise HTTPException(403,"Use the PC dashboard for background analysis and capability probes")
        if request.kind!="cutout":
            capability={"analysis":"vision","style":"text","preview":"imageGeneration","search":"webSearch"}[request.kind]
            if not providers.capabilities(request.provider).get(capability): raise HTTPException(403,"Feature locked; configure a supporting AI connection on your PC")
        return job_record(worker.enqueue(request))

    @app.get("/v1/jobs/{id_}",dependencies=[Depends(device)])
    def get_job(id_:str): return job_record(id_)

    @app.post("/v1/jobs/{id_}/cancel",dependencies=[Depends(device)])
    def cancel_job(id_:str):
        job=job_record(id_)
        if job["status"] in ("queued","running"):
            store.execute("UPDATE jobs SET status='cancelled',updatedAt=? WHERE id=?",(timestamp(),id_))
        return job_record(id_)

    @app.get("/admin/backup",dependencies=[Depends(admin)])
    def backup():
        # Deliberately export user data, never the live database with its credentials.
        output=io.BytesIO()
        with store.lock:
            records=store.query("SELECT kind,id,revision,payload,deleted FROM records")
            exported={"schemaVersion":1,"records":records,"trends":store.query("SELECT payload FROM trends"),"rules":json.loads(RULES_PATH.read_text(encoding="utf-8"))}
            with zipfile.ZipFile(output,"w",zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("wardrobe.json",canonical(exported))
                for path in (data/"media").iterdir():
                    if path.is_file() and len(path.name)==64: archive.write(path,"media/"+path.name)
        return Response(output.getvalue(),media_type="application/zip",headers={"Content-Disposition":"attachment; filename=Wardrobe-backup.zip"})

    @app.post("/admin/restore",dependencies=[Depends(admin)])
    async def restore(request:Request):
        body=await request.body()
        if len(body)>100_000_000: raise ValueError("Backup is too large")
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            if sum(i.file_size for i in archive.infolist())>300_000_000: raise ValueError("Backup expands beyond the limit")
            value=json.loads(archive.read("wardrobe.json"))
            if value.get("schemaVersion")!=1: raise ValueError("Unsupported backup version")
            changes=[]
            for row in value.get("records",[]):
                payload=json.loads(row["payload"])
                old=store.query("SELECT revision FROM records WHERE kind=? AND id=?",(row["kind"],row["id"]))
                revision=max(row["revision"],old[0]["revision"]+1 if old else 1)
                payload["revision"]=revision
                changes.append({"changeId":str(uuid.uuid4()),"kind":row["kind"],"recordId":row["id"],"revision":revision,"deleted":bool(row["deleted"]),"payload":payload})
            for info in archive.infolist():
                if info.filename.startswith("media/"):
                    id_=info.filename.removeprefix("media/")
                    if "/" in id_ or "\\" in id_: raise ValueError("Invalid backup image path")
                    save_image(store,archive.read(info),id_)
            for start in range(0,len(changes),300): store.sync(SyncRequest(changes=changes[start:start+300]))
            for trend in value.get("trends",[]):
                payload=json.loads(trend["payload"])
                store.execute("INSERT OR REPLACE INTO trends VALUES(?,?)",(payload["id"],canonical(payload)))
        return {"restored":len(changes),"credentialsRestored":False}

    return app
