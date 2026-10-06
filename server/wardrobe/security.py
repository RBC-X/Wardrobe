from __future__ import annotations
import base64
import ctypes
import hashlib
import ipaddress
import os
import re
import secrets
from urllib.parse import urlsplit

def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()

def require_media_id(value):
    if not re.fullmatch(r"[a-f0-9]{64}",value): raise ValueError("Invalid image ID")
    return value

def pairing_url(value):
    p=urlsplit(value)
    if p.scheme!="https" or not p.hostname or not p.hostname.endswith(".ts.net") or p.username or p.password or p.port not in (None,443) or p.query or p.fragment or p.path not in ("","/"):
        raise ValueError("Use your private https://PC.tailnet.ts.net Tailscale Serve address")
    return value.rstrip("/")

class _Blob(ctypes.Structure):
    _fields_=[("length",ctypes.c_uint32),("data",ctypes.POINTER(ctypes.c_ubyte))]

def protect(value, decrypt=False):
    """Windows DPAPI: credentials are bound to the current Windows user."""
    if os.name!="nt": raise RuntimeError("Persistent credentials require Windows DPAPI")
    data=base64.b64decode(value) if decrypt else value.encode()
    buffer=ctypes.create_string_buffer(data)
    source=_Blob(len(data),ctypes.cast(buffer,ctypes.POINTER(ctypes.c_ubyte)))
    target=_Blob()
    crypt=ctypes.windll.crypt32
    func=crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    func.argtypes=[ctypes.POINTER(_Blob),ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_uint32,ctypes.POINTER(_Blob)]
    func.restype=ctypes.c_int
    if not func(ctypes.byref(source),None,None,None,None,1,ctypes.byref(target)):
        raise RuntimeError("Windows could not unlock the stored credential")
    try:
        result=ctypes.string_at(target.data,target.length)
        return result.decode() if decrypt else base64.b64encode(result).decode()
    finally:
        free=ctypes.windll.kernel32.LocalFree
        free.argtypes=[ctypes.c_void_p];free.restype=ctypes.c_void_p
        free(target.data)

def public_url(value):
    p=urlsplit(value)
    if p.scheme!="https" or not p.hostname or p.username or p.password or p.port not in (None,443) or len(value)>2048:
        raise ValueError("Only public HTTPS product/source URLs are supported")
    if p.hostname.lower() in {"localhost","localhost.localdomain"} or p.hostname.endswith((".local",".ts.net")):
        raise ValueError("Private network destinations are not allowed")
    return p

def global_ip(address):
    ip=ipaddress.ip_address(address.split("%")[0])
    return ip.is_global and not ip.is_multicast

