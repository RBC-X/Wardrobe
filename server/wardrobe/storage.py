from __future__ import annotations
import hashlib
import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

def timestamp():
    return datetime.now(timezone.utc).isoformat()

def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(",",":"),ensure_ascii=False)

class Store:
    def __init__(self, root: Path):
        self.root=root
        root.mkdir(parents=True,exist_ok=True)
        (root/"media").mkdir(exist_ok=True)
        (root/"jobs").mkdir(exist_ok=True)
        self.lock=threading.RLock()
        self.db=sqlite3.connect(root/"wardrobe.sqlite3",check_same_thread=False)
        self.db.row_factory=sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA busy_timeout=5000")
        self.db.executescript("""
        CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,tokenHash TEXT UNIQUE,name TEXT,createdAt TEXT,revoked INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS pairing(codeHash TEXT PRIMARY KEY,expires REAL,used INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS records(kind TEXT,id TEXT,revision INTEGER,payload TEXT,deleted INTEGER,updatedAt TEXT,PRIMARY KEY(kind,id));
        CREATE TABLE IF NOT EXISTS changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,changeId TEXT UNIQUE,fingerprint TEXT,kind TEXT,recordId TEXT);
        CREATE TABLE IF NOT EXISTS providers(id TEXT PRIMARY KEY,config TEXT NOT NULL,secret TEXT DEFAULT '');
        CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,requestId TEXT UNIQUE,kind TEXT,provider TEXT,status TEXT,request TEXT,result TEXT,error TEXT,createdAt TEXT,updatedAt TEXT,deviceId TEXT DEFAULT '');
        CREATE TABLE IF NOT EXISTS feed_items(id TEXT PRIMARY KEY,source TEXT,title TEXT,url TEXT,publishedAt TEXT,summary TEXT,collectedAt TEXT);
        CREATE TABLE IF NOT EXISTS trends(id TEXT PRIMARY KEY,payload TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS spend(id TEXT PRIMARY KEY,provider TEXT,month TEXT,amount REAL,status TEXT);
        """)
        # Existing 1.0.x databases predate per-device job ownership.
        if "deviceId" not in {row[1] for row in self.db.execute("PRAGMA table_info(jobs)")}:
            self.db.execute("ALTER TABLE jobs ADD COLUMN deviceId TEXT DEFAULT ''")
        self.db.commit()

    def query(self,sql,args=()):
        with self.lock:
            return [dict(r) for r in self.db.execute(sql,args).fetchall()]

    def execute(self,sql,args=()):
        with self.lock, self.db:
            self.db.execute(sql,args)

    def get_setting(self,key,default=None):
        rows=self.query("SELECT value FROM settings WHERE key=?",(key,))
        return json.loads(rows[0]["value"]) if rows else default

    def set_setting(self,key,value):
        self.execute("INSERT OR REPLACE INTO settings VALUES(?,?)",(key,canonical(value)))

    def records(self,kind):
        return [json.loads(r["payload"]) for r in self.query("SELECT payload FROM records WHERE kind=? AND deleted=0",(kind,))]

    def sync(self,request):
        from .models import Garment, Profile, Outfit
        prepared=[]
        for change in request.changes:
            data=change.payload
            if change.kind=="garment": data=Garment.model_validate(data).model_dump()
            elif change.kind=="profile": data=Profile.model_validate(data).model_dump()
            else:
                if len(canonical(data))>30000: raise ValueError("Outfit is too large")
                data=Outfit.model_validate(data).model_dump()
            if data.get("id") != change.recordId or data.get("revision")!=change.revision:
                raise ValueError("Record identity/revision mismatch")
            prepared.append((change,data,hashlib.sha256(canonical(change.model_dump()).encode()).hexdigest()))
        accepted=[]
        with self.lock, self.db:
            for change,data,fingerprint in prepared:
                seen=self.db.execute("SELECT fingerprint FROM changes WHERE changeId=?",(change.changeId,)).fetchone()
                if seen:
                    if seen[0]!=fingerprint: raise ValueError("A retry changed its original payload")
                    accepted.append(change.changeId)
                    continue
                old=self.db.execute("SELECT revision,payload,deleted FROM records WHERE kind=? AND id=?",(change.kind,change.recordId)).fetchone()
                if old and old[0]>=change.revision:
                    if old[0]==change.revision and (old[1]!=canonical(data) or bool(old[2])!=change.deleted):
                        raise ValueError("Conflicting record revision; refresh before editing")
                else:
                    self.db.execute("INSERT OR REPLACE INTO records VALUES(?,?,?,?,?,?)",(change.kind,change.recordId,change.revision,canonical(data),int(change.deleted),timestamp()))
                self.db.execute("INSERT INTO changes(changeId,fingerprint,kind,recordId) VALUES(?,?,?,?)",(change.changeId,fingerprint,change.kind,change.recordId))
                accepted.append(change.changeId)
            cursor=self.db.execute("SELECT COALESCE(MAX(seq),0) FROM changes").fetchone()[0]
            rows=self.db.execute("SELECT DISTINCT r.* FROM records r JOIN changes c ON c.kind=r.kind AND c.recordId=r.id WHERE c.seq>?",(request.cursor,)).fetchall()
            records=[{"kind":r["kind"],"recordId":r["id"],"revision":r["revision"],"deleted":bool(r["deleted"]),"payload":json.loads(r["payload"])} for r in rows]
        return {"accepted":accepted,"cursor":cursor,"records":records}

    def reserve(self,provider,job_id,amount,budget):
        month=datetime.now(timezone.utc).strftime("%Y-%m")
        with self.lock,self.db:
            spent=self.db.execute("SELECT COALESCE(SUM(amount),0) FROM spend WHERE provider=? AND month=? AND status!='released'",(provider,month)).fetchone()[0]
            if budget<=0 or spent+amount>budget:
                raise ValueError("Monthly budget reached or paid usage is disabled")
            self.db.execute("INSERT INTO spend VALUES(?,?,?,?,?)",(job_id,provider,month,amount,"reserved"))

    def close(self):
        # sqlite3.close is not safe concurrently with a native query on this connection.
        with self.lock:
            self.db.close()

