import queue
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
import pytest
from wardrobe.app import Worker
from wardrobe.models import JobRequest,SyncRequest,ProviderConfig
from wardrobe.providers import Providers
from wardrobe.storage import Store
from wardrobe.images import save_image
from test_contracts import garment,change,image_bytes

@pytest.fixture
def store(tmp_path):
    value=Store(tmp_path)
    yield value
    value.close()

def test_worker_stop_during_idle_never_starts_feeds(store,monkeypatch):
    from wardrobe import app
    calls=[]
    monkeypatch.setattr(app,"collect_feeds",lambda *args,**kwargs:calls.append(True))
    worker=Worker(store,Providers(store))
    worker.stop.set();worker.thread.join(timeout=3)
    assert not worker.thread.is_alive() and calls==[]

def test_stop_during_feed_fetch_prevents_more_fetches_or_writes(store,monkeypatch):
    from wardrobe import webdata
    started=threading.Event();release=threading.Event();calls=[]
    def fetch(*args,**kwargs):
        calls.append(args[0]);started.set();assert release.wait(5)
        return b'<rss><channel><item><title>Example</title><link>https://example.com/clothing</link></item></channel></rss>',"application/xml",200,args[0]
    monkeypatch.setattr(webdata,"safe_fetch",fetch)
    worker=Worker(store,Providers(store))
    try:
        assert started.wait(3)
        worker.stop.set();release.set();worker.thread.join(timeout=3)
        assert not worker.thread.is_alive() and len(calls)==1
        assert store.query("SELECT * FROM feed_items")==[]
        assert store.get_setting("lastFeedCheck") is None
    finally:
        worker.stop.set();release.set();worker.thread.join(timeout=5)

def test_close_waits_for_existing_database_operation(store):
    attempted=threading.Event()
    def close():attempted.set();store.close()
    with ThreadPoolExecutor(max_workers=1) as pool:
        with store.lock:
            future=pool.submit(close);assert attempted.wait(1)
            assert not future.done()
        future.result(timeout=2)
    with pytest.raises(sqlite3.ProgrammingError):store.query("SELECT 1")

def test_simultaneous_duplicate_jobs_share_one_record(store):
    worker=object.__new__(Worker);worker.store=store;worker.pending=queue.Queue()
    request=JobRequest(kind="cutout",provider="local",requestId="same-request")
    with ThreadPoolExecutor(max_workers=8) as pool:ids=list(pool.map(lambda _:worker.enqueue(request),range(40)))
    assert len(set(ids))==1 and worker.pending.qsize()==1
    assert len(store.query("SELECT * FROM jobs"))==1

def test_concurrent_photo_retries_leave_one_complete_image(store):
    data=image_bytes()
    with ThreadPoolExecutor(max_workers=8) as pool:ids=list(pool.map(lambda _:save_image(store,data),range(40)))
    assert len(set(ids))==1
    assert (store.root/"media"/ids[0]).read_bytes()==data
    assert len(list((store.root/"media").iterdir()))==1

def test_changed_outfit_rejected_after_ai_response(store,monkeypatch):
    from wardrobe import providers
    for category in ("top","bottom","shoes"):store.sync(SyncRequest(changes=[change(garment(category,category),category)]))
    class Bridge:
        def __init__(self,*args):pass
        def close(self):pass
        def run(self,*args):
            store.sync(SyncRequest(changes=[change(garment("top","top",revision=2),"edit")]))
            return {"itemIds":["top","bottom","shoes"],"explanation":"Old advice"}
    monkeypatch.setattr(providers,"CodexBridge",Bridge)
    p=Providers(store);p.configure("codex",ProviderConfig(enabled=True));p.update("codex",authenticated=True)
    with pytest.raises(ValueError,match="changed during this request"):
        p.run(JobRequest(kind="style",provider="codex",itemIds=["top","bottom","shoes"],requestId="stale-outfit"),"stale-outfit")

def test_paid_disabled_status_and_photo_locks_are_explained(store):
    p=Providers(store);p.configure("openai",ProviderConfig(enabled=True,allowPhotos=True));p.update("openai",authenticated=True)
    caps=p.capabilities("openai")
    assert not caps["text"] and "paid allowance" in caps["reason"]
    p.configure("codex",ProviderConfig(enabled=True));p.update("codex",authenticated=True,imageVerified=True)
    caps=p.capabilities("codex")
    assert not caps["vision"] and not caps["imageGeneration"] and "photo sharing" in caps["reason"]
