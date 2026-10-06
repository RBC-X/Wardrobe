import base64
import hashlib
import io
import json
import queue
import socket
import time
import zipfile
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import pytest
from fastapi.testclient import TestClient
from PIL import Image
from wardrobe.app import create_app,Worker
from wardrobe.engine import generate,score,valid,gaps
from wardrobe.models import Garment,Profile,ProviderConfig,SyncRequest,JobRequest
from wardrobe.providers import Providers
from wardrobe.security import protect,public_url,global_ip,pairing_url
from wardrobe.storage import Store,canonical
from wardrobe.webdata import safe_fetch,extract_product,collect_feeds

def garment(id_,category,color="#e8e5db",**kwargs):
    return Garment(id=id_,name=id_,category=category,color=color,**kwargs).model_dump()

def change(item,id_="request-1",deleted=False):
    return {"changeId":id_,"kind":"garment","recordId":item["id"],"revision":item["revision"],"deleted":deleted,"payload":item}

@pytest.fixture
def store(tmp_path):
    value=Store(tmp_path);yield value;value.close()

@pytest.fixture
def pc(tmp_path):
    app=create_app(tmp_path,background=False)
    with TestClient(app) as client:
        admin={"X-Wardrobe-Admin":app.state.admin_token}
        code=client.post("/admin/pair",headers=admin,json={"url":"https://wardrobe.example.ts.net"}).json()["pairing"]["code"]
        pairing=client.post("/v1/pair",json={"code":code}).json()
        headers={"Authorization":"Bearer "+pairing["token"]}
        yield client,app,admin,headers,pairing

def image_bytes():
    data=io.BytesIO();Image.new("RGB",(128,128),"blue").save(data,"JPEG");return data.getvalue()

def test_empty_install_has_no_placeholders(pc):
    client,app,_,headers,_=pc
    assert app.state.store.records("garment")==[]
    assert client.get("/v1/health").json()["app"]=="Wardrobe"
    assert client.post("/v1/sync",headers=headers,json={}).json()["records"]==[]

def test_private_routes_require_auth(pc):
    client,*_=pc
    for path in ("/admin/status","/admin/backup","/v1/capabilities","/v1/recommendations"):
        assert client.get(path).status_code==401

def test_pairing_single_use_expired_and_revoked(pc):
    client,app,admin,headers,pairing=pc
    response=client.post("/admin/pair",headers=admin,json={"url":"https://wardrobe.example.ts.net"})
    code=response.json()["pairing"]["code"]
    assert client.post("/v1/pair",json={"code":code}).status_code==200
    assert client.post("/v1/pair",json={"code":code}).status_code==403
    from wardrobe.security import digest
    code=client.post("/admin/pair",headers=admin,json={"url":"https://wardrobe.example.ts.net"}).json()["pairing"]["code"]
    app.state.store.execute("UPDATE pairing SET expires=? WHERE codeHash=?",(time.time()-1,digest(code)))
    assert client.post("/v1/pair",json={"code":code}).status_code==403
    client.post("/admin/revoke/"+pairing["deviceId"],headers=admin)
    assert client.get("/v1/capabilities",headers=headers).status_code==401

def test_pairing_tokens_are_only_stored_as_hashes(pc):
    _,app,_,_,pairing=pc
    rows=canonical(app.state.store.query("SELECT * FROM devices"))
    assert pairing["token"] not in rows

@pytest.mark.parametrize("url",["http://pc.ts.net","https://example.com","https://pc.ts.net/path","https://user@pc.ts.net","https://pc.ts.net:444"])
def test_pairing_url_restrictions(url):
    with pytest.raises(ValueError):pairing_url(url)

def test_sync_retry_and_manual_edits_preserved(pc):
    client,app,_,headers,_=pc
    item=garment("shirt","top",brand="My verified label",brandConfirmed=True,manualFields=["color","brand"])
    payload={"changes":[change(item)]}
    a=client.post("/v1/sync",headers=headers,json=payload).json()
    b=client.post("/v1/sync",headers=headers,json=payload).json()
    assert a["cursor"]==b["cursor"]==1
    assert b["records"][0]["payload"]["manualFields"]==["color","brand"]
    assert hashlib.sha256(a["rulesJson"].encode()).hexdigest()==a["rulesHash"]

def test_mutated_retry_and_revision_conflict_roll_back(store):
    first=garment("shirt","top")
    store.sync(SyncRequest(changes=[change(first)]))
    second=garment("pants","bottom")
    conflict=first|{"name":"Different"}
    with pytest.raises(ValueError):store.sync(SyncRequest(changes=[change(second,"request-2"),change(conflict,"request-1")]))
    assert len(store.records("garment"))==1
    with pytest.raises(ValueError):store.sync(SyncRequest(changes=[change(conflict,"request-3")]))
    assert store.records("garment")[0]["name"]=="shirt"

def test_stale_updates_and_deletions(store):
    item=garment("shirt","top")
    store.sync(SyncRequest(changes=[change(item)]))
    removed=item|{"revision":2,"deleted":True}
    store.sync(SyncRequest(cursor=1,changes=[change(removed,"delete-2",True)]))
    result=store.sync(SyncRequest(cursor=1,changes=[change(item,"late-1")]))
    assert store.records("garment")==[]
    assert result["records"][0]["deleted"]

def test_failed_media_transfer_does_not_commit_record(pc):
    client,app,_,headers,_=pc
    data=image_bytes();id_=hashlib.sha256(data).hexdigest()
    item=garment("shirt","top",imageIds=[id_]);payload={"changes":[change(item)]}
    assert client.put("/v1/media/"+id_,headers=headers,content=data[:-10]).status_code in (400,500)
    assert client.post("/v1/sync",headers=headers,json=payload).status_code==409
    assert app.state.store.records("garment")==[]
    assert client.put("/v1/media/"+id_,headers=headers,content=data).status_code==200
    assert client.put("/v1/media/"+id_,headers=headers,content=data).status_code==200
    assert client.post("/v1/sync",headers=headers,json=payload).status_code==200
    assert client.get("/v1/media/"+id_,headers=headers).content==data

def test_media_hash_path_and_bounds(pc):
    client,_,_,headers,_=pc
    assert client.put("/v1/media/"+"0"*64,headers=headers,content=image_bytes()).status_code==400
    assert client.get("/v1/media/not-a-hash",headers=headers).status_code==400
    assert client.put("/v1/media/"+"0"*64,headers=headers,content=b"x"*8_000_001).status_code==413

def test_unknown_brand_and_material_remain_honest():
    item=garment("shirt","top",texture="linen-like")
    assert not item["brandConfirmed"] and not item["compositionVerified"] and item["composition"]==""

def test_engine_offline_valid_diverse_and_unavailable():
    items=[garment("t1","top"),garment("t2","top","#141922"),garment("b1","bottom","#72734e"),garment("b2","bottom","#444c68"),garment("s","shoes"),garment("x","top",status="laundry")]
    output=generate(items,Profile().model_dump())
    assert 1<=len(output)<=5 and len({o["id"] for o in output})==len(output)
    by_id={g["id"]:g for g in items}
    assert all(valid([by_id[i] for i in o["itemIds"]]) and "x" not in o["itemIds"] for o in output)
    assert all("t2" in o["itemIds"] for o in generate(items,pinned="t2"))
    assert not generate(items,pinned="missing")

def test_formal_filter_precedes_slot_limits():
    items=[garment(f"a{i:03}","top",formality=0) for i in range(50)]+[garment("z","top",formality=2),garment("b","bottom",formality=2),garment("s","shoes",formality=2)]
    assert generate(items,occasion="formal")

def test_weather_and_structure_enforced():
    items=[garment("top","top"),garment("bottom","bottom"),garment("shoes","shoes")]
    assert not valid(items,weather="cold")
    assert valid(items+[garment("coat","outerwear",warmth=3)],weather="cold")
    assert not valid(items+[garment("coat","outerwear",warmth=3)],weather="warm")
    assert not valid(items+[garment("dress","onepiece")])

def test_disliked_combinations_and_trend_expiry():
    items=[garment("top","top",pattern="striped"),garment("bottom","bottom",pattern="checked"),garment("shoes","shoes")]
    base=score(items,Profile().model_dump())
    disliked=score(items,Profile(dislikedCombinations="striped + checked").model_dump())
    assert disliked["personal"]<base["personal"]
    trend={"tags":["casual"],"confidence":.9,"expiresAt":"2100-01-01T00:00:00+00:00","sources":["https://www.vogue.com/article/example"]}
    assert score(items,trends=[trend])["trend"]==90
    assert score(items,trends=[trend|{"expiresAt":"2000-01-01T00:00:00+00:00"}])["trend"]==0
    assert score(items,trends=[trend|{"sources":[]}])["trend"]==0

def test_gaps_do_not_invent_products():
    assert all(g["kind"]=="wardrobe_gap" and "price" not in g for g in gaps([garment("t","top")]))

def test_default_capabilities_locked(pc):
    client,_,_,headers,_=pc
    for provider in client.get("/v1/capabilities",headers=headers).json()["providers"]:
        assert not any(provider[k] for k in ("text","vision","webSearch","imageGeneration"))

def test_capabilities_independent_and_image_probe_required(store):
    providers=Providers(store)
    providers.configure("claude",ProviderConfig(enabled=True,allowPhotos=True));providers.update("claude",authenticated=True)
    caps=providers.capabilities("claude");assert caps["text"] and caps["vision"] and not caps["imageGeneration"] and not caps["webSearch"]
    providers.configure("codex",ProviderConfig(enabled=True));providers.update("codex",authenticated=True)
    caps=providers.capabilities("codex");assert caps["text"] and not caps["vision"] and not caps["imageGeneration"]
    providers.update("codex",imageVerified=True,allowPhotos=True);assert providers.capabilities("codex")["imageGeneration"]
    providers.configure("gemini",ProviderConfig(enabled=True,freeOnly=True));providers.update("gemini",authenticated=True)
    assert not providers.capabilities("gemini")["text"]
    providers.update("gemini",freeProjectConfirmed=True);assert providers.capabilities("gemini")["text"]
    assert not providers.capabilities("gemini")["imageGeneration"]

def test_api_secret_dpapi_and_backup_exclusion(pc):
    client,app,admin,headers,_=pc
    secret="test-key-not-a-real-credential"
    assert protect(protect(secret),decrypt=True)==secret
    client.put("/admin/providers/gemini",headers=admin,json={"enabled":False,"apiKey":secret})
    assert secret not in canonical(app.state.store.query("SELECT * FROM providers"))
    data=client.get("/admin/backup",headers=admin).content
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        all_data=b"".join(archive.read(n) for n in archive.namelist())
        assert secret.encode() not in all_data and b"tokenHash" not in all_data and b"dashboard-token" not in all_data

def test_budget_reservation_atomic_under_competing_jobs(store):
    def reserve(i):
        try:store.reserve("openai",str(i),1,3);return True
        except ValueError:return False
    with ThreadPoolExecutor(max_workers=4) as pool: results=list(pool.map(reserve,range(12)))
    assert sum(results)==3
    with pytest.raises(ValueError):store.reserve("openai","disabled",1,0)

def test_worker_retries_repeat_safe(store):
    worker=object.__new__(Worker);worker.store=store;worker.pending=queue.Queue()
    request=JobRequest(kind="cutout",provider="local",requestId="once")
    first=worker.enqueue(request);assert worker.enqueue(request)==first and worker.pending.qsize()==1
    with pytest.raises(ValueError):worker.enqueue(request.model_copy(update={"revision":2}))

def test_ai_rejects_unknown_ids_and_stale_analysis(store):
    provider=Providers(store);provider.configure("codex",ProviderConfig(enabled=True,allowPhotos=True));provider.update("codex",authenticated=True)
    with pytest.raises(ValueError,match="unknown garment"):provider.run(JobRequest(kind="style",provider="codex",itemIds=["fake"],requestId="x"),"x")
    item=garment("top","top",revision=2);store.sync(SyncRequest(changes=[change(item)]))
    with pytest.raises(ValueError,match="changed"):provider.run(JobRequest(kind="analysis",provider="codex",garmentId="top",revision=1,consentPhotos=True,requestId="stale"),"stale")

def test_ai_substitutions_and_duplicates_rejected(store,monkeypatch):
    from wardrobe import providers as module
    for i,category in enumerate(("top","bottom","shoes")):store.sync(SyncRequest(changes=[change(garment(category,category),str(i))]))
    class FakeBridge:
        def __init__(self,*args):pass
        def close(self):pass
        def run(self,*args):return {"itemIds":["top","bottom","missing"],"explanation":"Fake outfit"}
    monkeypatch.setattr(module,"CodexBridge",FakeBridge)
    p=Providers(store);p.configure("codex",ProviderConfig(enabled=True));p.update("codex",authenticated=True)
    with pytest.raises(ValueError,match="unknown, duplicate"):p.run(JobRequest(kind="style",provider="codex",itemIds=["top","bottom","shoes"],requestId="ids"),"ids")

@pytest.mark.parametrize("url",["http://example.com","https://127.0.0.1","https://localhost","https://host.ts.net","https://example.com:444","https://user:pass@example.com"])
def test_safe_url_blocks_private_destinations(url,monkeypatch):
    monkeypatch.setattr(socket,"getaddrinfo",lambda *a,**k:[(2,1,6,"",("127.0.0.1",443))])
    with pytest.raises(ValueError):safe_fetch(url)

def test_product_variants_require_confirmation(monkeypatch):
    from wardrobe import webdata
    body=b'''<html><script type="application/ld+json">{"@type":"ProductGroup","name":"Shirt","hasVariant":[{"@type":"Product","name":"Blue shirt","color":"Blue","brand":{"name":"Example"},"material":"100% cotton","image":"https://example.com/blue.jpg","offers":{"price":"20","priceCurrency":"USD"}},{"@type":"Product","name":"Red shirt","color":"Red","image":"https://example.com/red.jpg"}]}</script></html>'''
    monkeypatch.setattr(webdata,"safe_fetch",lambda *a,**k:(body,"text/html",200,"https://example.com/shirt"))
    result=extract_product("https://example.com/shirt")
    assert len(result["products"])==3 and all(p["needsConfirmation"] for p in result["products"])
    assert result["products"][1]["color"]=="Blue" and result["products"][2]["price"]==""

def test_feed_dedup_and_original_rules_protected(store,monkeypatch):
    from wardrobe import webdata,engine
    before=engine.RULES_PATH.read_bytes()
    body=b'<rss><channel><item><title>Colors</title><link>https://www.vogue.com/article/colors</link><pubDate>Mon, 05 Oct 2026 12:00:00 GMT</pubDate><description>Brief clothing information.</description></item></channel></rss>'
    monkeypatch.setattr(webdata,"safe_fetch",lambda *a,**k:(body,"application/xml",200,"https://www.vogue.com/feed/rss"))
    collect_feeds(store);collect_feeds(store)
    assert len(store.query("SELECT * FROM feed_items"))==1
    assert engine.RULES_PATH.read_bytes()==before

def test_backup_restore_media_records_and_no_credentials(pc):
    client,app,admin,headers,_=pc
    data=image_bytes();id_=hashlib.sha256(data).hexdigest();client.put("/v1/media/"+id_,headers=headers,content=data)
    item=garment("shirt","top",imageIds=[id_]);client.post("/v1/sync",headers=headers,json={"changes":[change(item)]})
    backup=client.get("/admin/backup",headers=admin).content
    item["name"]="Changed";item["revision"]=2;client.post("/v1/sync",headers=headers,json={"changes":[change(item,"two")]})
    result=client.post("/admin/restore",headers=admin,content=backup)
    assert result.status_code==200 and not result.json()["credentialsRestored"]
    restored=app.state.store.records("garment")[0];assert restored["name"]=="shirt" and restored["revision"]==3

def test_sync_many_changes_in_batches_and_retry(store):
    items=[garment(f"shirt-{i}","top") for i in range(601)]
    cursor=0
    for start in range(0,len(items),300):
        request=SyncRequest(cursor=cursor,changes=[change(item,f"change-{start+i}") for i,item in enumerate(items[start:start+300])])
        result=store.sync(request)
        retry=store.sync(request)
        assert result==retry
        cursor=result["cursor"]
    assert cursor==601 and len(store.records("garment"))==601

def test_season_preference_and_duplicate_gaps():
    items=[garment("t","top",season="winter"),garment("b","bottom",season="winter"),garment("s","shoes",season="winter")]
    assert generate(items,season="summer")
    assert score(items,season="winter")["foundation"]>score(items,season="summer")["foundation"]
    complete=[garment("t","top","#f3eadb"),garment("b","bottom","#72734e"),garment("s","shoes","#f4f1e8"),garment("o","outerwear","#9b7957")]
    assert not gaps(complete)

def test_old_or_malformed_trend_is_not_influential():
    items=[garment("t","top"),garment("b","bottom"),garment("s","shoes")]
    trend={"tags":["casual"],"confidence":.9,"sources":["https://www.vogue.com/article/example"],"observedAt":"2000-01-01T00:00:00Z","expiresAt":"2100-01-01T00:00:00Z"}
    assert score(items,trends=[trend])["trend"]==0
    assert score(items,trends=[trend|{"observedAt":"invalid"}])["trend"]==0

def test_cutout_result_tied_to_item_revision(store,monkeypatch):
    from wardrobe import app as module
    item=garment("t","top",imageIds=["1"*64]);store.sync(SyncRequest(changes=[change(item)]))
    monkeypatch.setattr(module,"cutout",lambda *_:{"imageId":"0"*64,"sourceImageId":"1"*64})
    worker=Worker(store,Providers(store))
    try:
        id_=worker.enqueue(JobRequest(kind="cutout",provider="local",garmentId="t",revision=1,imageIds=["1"*64],requestId="revision-cutout"))
        end=time.monotonic()+3
        while time.monotonic()<end:
            row=store.query("SELECT status,result FROM jobs WHERE id=?",(id_,))[0]
            if row["status"]=="completed":break
            time.sleep(.02)
        result=json.loads(row["result"])
        assert result["garmentId"]=="t" and result["revision"]==1
    finally:
        worker.stop.set();worker.thread.join(timeout=3)

def test_shutdown_requires_admin_and_host_callback(pc):
    client,app,admin,*_=pc
    assert client.post("/admin/shutdown").status_code==401
    assert client.post("/admin/shutdown",headers=admin).status_code==503
    called=[];app.state.shutdown=lambda:called.append(True)
    assert client.post("/admin/shutdown",headers=admin).json()=={"stopping":True}
    assert called==[True]

def test_api_route_preserves_manual_fields_and_uses_selected_data(store,monkeypatch):
    from wardrobe import providers as module
    item=garment("t","top",manualFields=["color"],color="#112233");store.sync(SyncRequest(changes=[change(item)]))
    p=Providers(store);p.configure("openai",ProviderConfig(enabled=True,apiKey="fake-not-a-real-key",freeOnly=False,monthlyBudget=3,allowPhotos=True));p.update("openai",authenticated=True)
    seen=[]
    def post(url,**kwargs):
        seen.append((url,kwargs["json"]))
        result={"category":"top","color":"#ff0000","texture":"knit"}
        return __import__('httpx').Response(200,json={"output":[{"content":[{"type":"output_text","text":canonical(result)}]}]})
    monkeypatch.setattr(module.httpx,"post",post)
    result=p.run(JobRequest(kind="analysis",provider="openai",garmentId="t",revision=1,consentPhotos=True,requestId="mocked-api"),"mocked-api")
    assert result["garmentId"]=="t" and not result["brandConfirmed"]
    assert store.records("garment")[0]["color"]=="#112233"
    assert seen[0][0]=="https://api.openai.com/v1/responses" and "tools" not in seen[0][1]
    assert seen[0][1]["text"]["format"]["strict"]
    assert store.query("SELECT status FROM spend")[0]["status"]=="committed"

def test_changed_analysis_after_provider_response_rejected(store,monkeypatch):
    from wardrobe import providers as module
    item=garment("t","top");store.sync(SyncRequest(changes=[change(item)]))
    class FakeBridge:
        def __init__(self,*args):pass
        def close(self):pass
        def run(self,*args):
            store.sync(SyncRequest(changes=[change(item|{"revision":2},"new-edit")]))
            return {"category":"top","color":"#eeeeee"}
    monkeypatch.setattr(module,"CodexBridge",FakeBridge)
    p=Providers(store);p.configure("codex",ProviderConfig(enabled=True,allowPhotos=True));p.update("codex",authenticated=True)
    with pytest.raises(ValueError,match="changed during analysis"):
        p.run(JobRequest(kind="analysis",provider="codex",garmentId="t",revision=1,consentPhotos=True,requestId="new-analysis"),"new-analysis")

def test_bad_generated_image_cannot_unlock_provider(store,monkeypatch):
    from wardrobe import providers as module
    class FakeBridge:
        def __init__(self,*args):pass
        def close(self):pass
        def run(self,*args):return b"not-an-image"
    monkeypatch.setattr(module,"CodexBridge",FakeBridge)
    p=Providers(store);p.configure("codex",ProviderConfig(enabled=True));p.update("codex",authenticated=True)
    with pytest.raises(Exception):p.run(JobRequest(kind="preview_probe",provider="codex",requestId="bad-image"),"bad-image")
    assert not p.capabilities("codex")["imageGeneration"]

def test_python_kotlin_shared_styling_fixture():
    value=json.loads((Path(__file__).resolve().parents[2]/"shared/styling-parity.json").read_text())
    assert generate(value["garments"],weather="cold",season="winter")==value["outfits"]

def test_analysis_cannot_send_unselected_image(store):
    item=garment("t","top");store.sync(SyncRequest(changes=[change(item)]))
    p=Providers(store);p.configure("codex",ProviderConfig(enabled=True,allowPhotos=True));p.update("codex",authenticated=True)
    with pytest.raises(ValueError,match="belong to the selected item"):
        p.run(JobRequest(kind="analysis",provider="codex",garmentId="t",revision=1,imageIds=["0"*64],consentPhotos=True,requestId="wrong-photo"),"wrong-photo")

def test_codex_isolation_failure_keeps_capability_locked(store,monkeypatch):
    from wardrobe import providers as module
    class FakeBridge:
        def __init__(self,*args):pass
        def account(self):pass
        def start_thread(self,*args):raise ValueError("Restricted sandbox unavailable")
        def close(self):pass
    monkeypatch.setattr(module,"CodexBridge",FakeBridge)
    p=Providers(store);p.configure("codex",ProviderConfig(enabled=True))
    with pytest.raises(ValueError,match="sandbox unavailable"):p.test("codex")
    assert not p.capabilities("codex")["text"] and not p.capabilities("codex")["authenticated"]

def test_backup_path_traversal_rejected(pc):
    client,_,admin,*_=pc
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,"w") as archive:
        archive.writestr("wardrobe.json",canonical({"schemaVersion":1,"records":[]}));archive.writestr("media/../escape",b"bad")
    assert client.post("/admin/restore",headers=admin,content=buffer.getvalue()).status_code==400
