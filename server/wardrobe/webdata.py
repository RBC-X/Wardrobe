from __future__ import annotations
import email.utils
import hashlib
import http.client
import io
import json
import socket
import ssl
import urllib.robotparser
import xml.etree.ElementTree as ET
from urllib.parse import urljoin, urlsplit
from .security import public_url, global_ip
from .storage import timestamp

AGENT="Wardrobe/1.0 (personal wardrobe companion)"
FEEDS=[("Vogue","https://www.vogue.com/feed/rss"),("GQ","https://www.gq.com/feed/rss"),("Fashionista","https://fashionista.com/.rss/feed/28e21eb8-20ac-4617-a448-e845081591ca.xml")]

class PinnedHTTPS(http.client.HTTPSConnection):
    def __init__(self,host,address):
        super().__init__(host,timeout=18,context=ssl.create_default_context())
        self.address=address
    def connect(self):
        raw=socket.create_connection((self.address,443),self.timeout)
        self.sock=self._context.wrap_socket(raw,server_hostname=self.host)

def safe_fetch(url,limit=2_000_000,redirects=3,respect_robots=False):
    """Resolve and pin a global IP on each HTTPS hop, preserving TLS hostname checks."""
    initial=public_url(url)
    if respect_robots:
        robots_url=f"https://{initial.hostname}/robots.txt"
        try:
            body,_,status,_=safe_fetch(robots_url,200_000)
            if status==200:
                parser=urllib.robotparser.RobotFileParser()
                parser.parse(body.decode("utf-8",errors="replace").splitlines())
                if not parser.can_fetch(AGENT,url): raise ValueError("This site does not permit automated retrieval; enter its details manually")
            elif status not in (404,410):
                raise ValueError("This site's retrieval policy is unavailable")
        except (OSError,http.client.HTTPException):
            raise ValueError("This site's retrieval policy could not be checked")
    for hop in range(redirects+1):
        parsed=public_url(url)
        addresses={r[4][0] for r in socket.getaddrinfo(parsed.hostname,443,type=socket.SOCK_STREAM)}
        if not addresses or any(not global_ip(a) for a in addresses):
            raise ValueError("Private/reserved network destinations are blocked")
        conn=PinnedHTTPS(parsed.hostname,sorted(addresses)[0])
        try:
            path=parsed.path or "/"
            if parsed.query: path+="?"+parsed.query
            conn.request("GET",path,headers={"User-Agent":AGENT,"Accept-Encoding":"identity","Accept":"text/html,application/xml,image/*;q=0.8"})
            response=conn.getresponse()
            if response.status in (301,302,303,307,308):
                if hop==redirects: raise ValueError("Too many redirects")
                url=urljoin(url,response.getheader("Location",""))
                continue
            if int(response.getheader("Content-Length","0"))>limit: raise ValueError("Source is too large")
            data=response.read(limit+1)
            if len(data)>limit: raise ValueError("Source is too large")
            return data,response.getheader("Content-Type",""),response.status,url
        finally:
            conn.close()
    raise ValueError("Source could not be retrieved")

def extract_product(url):
    from bs4 import BeautifulSoup
    body,kind,status,final=safe_fetch(url,respect_robots=True)
    if status!=200 or "html" not in kind: raise ValueError("Product page unavailable; add the details manually")
    soup=BeautifulSoup(body,"html.parser")
    products=[]
    def collect(value):
        if isinstance(value,list):
            for entry in value: collect(entry)
        elif isinstance(value,dict):
            types=value.get("@type",[])
            if isinstance(types,str): types=[types]
            if "Product" in types or "ProductGroup" in types: products.append(value)
            for key in ("@graph","hasVariant","mainEntity"):
                if key in value: collect(value[key])
    for script in soup.find_all("script",type="application/ld+json")[:20]:
        try: collect(json.loads(script.get_text()))
        except (ValueError,RecursionError): continue
    def meta(name):
        element=soup.find("meta",attrs={"property":name}) or soup.find("meta",attrs={"name":name})
        return element.get("content","") if element else ""
    results=[]
    for product in products[:12] or [{}]:
        images=product.get("image") or meta("og:image")
        if isinstance(images,dict): images=images.get("url","")
        if isinstance(images,str): images=[images]
        good=[]
        for image in (images or [])[:12]:
            value=urljoin(final,image.get("url","") if isinstance(image,dict) else str(image))
            try: public_url(value); good.append(value)
            except ValueError: pass
        brand=product.get("brand","")
        if isinstance(brand,dict): brand=brand.get("name","")
        offer=product.get("offers",{})
        if isinstance(offer,list): offer=offer[0] if offer else {}
        if not isinstance(offer,dict): offer={}
        title=product.get("name") or meta("og:title") or (soup.title.get_text() if soup.title else "Imported product")
        material=product.get("material","")
        results.append({"name":str(title)[:200],"brand":str(brand)[:100],"color":str(product.get("color",""))[:100],"size":str(product.get("size",""))[:100],"composition":str(material)[:400],"images":good,"price":str(offer.get("price",offer.get("lowPrice","")))[:30],"currency":str(offer.get("priceCurrency",""))[:8],"availability":str(offer.get("availability",""))[:100],"url":final,"checkedAt":timestamp(),"needsConfirmation":True})
    return {"products":results,"sourceUrl":final,"checkedAt":timestamp()}

def collect_feeds(store,cancel=None):
    from bs4 import BeautifulSoup
    results=[]
    for source,url in FEEDS:
        if cancel and cancel(): return results
        try:
            body,_,status,_=safe_fetch(url)
            if cancel and cancel(): return results
            if status!=200: raise ValueError(f"Feed returned HTTP {status}")
            if b"<!DOCTYPE" in body.upper() or b"<!ENTITY" in body.upper(): raise ValueError("Unsafe feed XML")
            root=ET.fromstring(body)
            count=0
            for item in root.findall("./channel/item")[:40]:
                title=(item.findtext("title") or "")[:250]
                link=item.findtext("link") or ""
                try: public_url(link)
                except ValueError: continue
                published=item.findtext("pubDate") or ""
                try: published=email.utils.parsedate_to_datetime(published).isoformat()
                except (ValueError,TypeError): published=""
                summary=BeautifulSoup(item.findtext("description") or "","html.parser").get_text(" ",strip=True)[:600]
                id_=hashlib.sha256(link.encode()).hexdigest()
                store.execute("INSERT OR IGNORE INTO feed_items VALUES(?,?,?,?,?,?,?)",(id_,source,title,link,published,summary,timestamp()))
                count+=1
            results.append({"source":source,"entriesChecked":count,"status":"ok"})
        except Exception as error:
            if cancel and cancel(): return results
            results.append({"source":source,"status":"error","message":str(error)[:200]})
    store.set_setting("lastFeedCheck",timestamp())
    store.set_setting("feedStatus",results)
    return results

