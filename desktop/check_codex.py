"""Explicit integration validation, uses the account's Codex allowance. Not automatic setup."""
from pathlib import Path
import argparse,json,sys,time,uuid
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'server'))
import httpx
from wardrobe.models import Garment

parser=argparse.ArgumentParser();parser.add_argument('--data',type=Path,required=True);args=parser.parse_args()
with httpx.Client(base_url='http://127.0.0.1:8765',timeout=40) as c:
    admin={'X-Wardrobe-Admin':(args.data/'dashboard-token').read_text()};device=None
    try:
        c.put('/admin/providers/codex',headers=admin,json={'enabled':True,'allowPhotos':False}).raise_for_status()
        response=c.post('/admin/providers/codex/test',headers=admin);response.raise_for_status();assert response.json()['text'];print('Authenticated text route; photos disabled',flush=True)
        code=c.post('/admin/pair',headers=admin,json={'url':'https://delivery-check.example.ts.net'}).json()['pairing']['code'];r=c.post('/v1/pair',json={'code':code,'deviceName':'Synthetic Codex validation'});r.raise_for_status();device=r.json();auth={'Authorization':'Bearer '+device['token']}
        changes=[];prefix='check-'+uuid.uuid4().hex[:12]
        for category,color in [('top','#eeeeee'),('bottom','#444444'),('shoes','#72734e')]:
            value=Garment(id=prefix+'-'+category,name='Synthetic '+category,category=category,color=color).model_dump();changes.append({'changeId':str(uuid.uuid4()),'kind':'garment','recordId':value['id'],'revision':1,'payload':value})
        c.post('/v1/sync',headers=auth,json={'changes':changes}).raise_for_status();ids=[x['recordId'] for x in changes]
        r=c.post('/v1/jobs',headers=auth,json={'kind':'style','provider':'codex','itemIds':ids,'requestId':str(uuid.uuid4())});r.raise_for_status();job=r.json();start=time.monotonic()
        while job['status'] in ('queued','running') and time.monotonic()-start<170:
            time.sleep(2);r=c.get('/v1/jobs/'+job['id'],headers=auth);r.raise_for_status();job=r.json()
        result={'status':job['status'],'error':job['error'],'result':job['result'],'elapsedSeconds':time.monotonic()-start,'syntheticDataOnly':True,'photosSent':False}
        (ROOT/'runtime/final-checks/codex-route.json').write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2),flush=True)
        assert job['status']=='completed' and set(job['result']['itemIds'])==set(ids) and job['result']['explanation']
    finally:
        if device:c.post('/admin/revoke/'+device['deviceId'],headers=admin)
        c.put('/admin/providers/codex',headers=admin,json={'enabled':False,'allowPhotos':False,'backgroundAnalysis':False})
