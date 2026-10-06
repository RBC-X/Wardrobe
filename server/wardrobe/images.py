from __future__ import annotations
import hashlib
import io
import os
import uuid
from pathlib import Path
from PIL import Image, ImageOps
from .security import require_media_id

Image.MAX_IMAGE_PIXELS=12_000_000

def validate_image(data):
    if len(data)>8_000_000: raise ValueError("Image exceeds 8 MB")
    with Image.open(io.BytesIO(data)) as image:
        if image.format not in ("JPEG","PNG","WEBP") or getattr(image,"n_frames",1)!=1:
            raise ValueError("Use a single JPEG, PNG or WebP image")
        if image.width*image.height>12_000_000: raise ValueError("Image dimensions are too large")
        image.verify()

def save_image(store,data,expected=None):
    validate_image(data)
    id_=hashlib.sha256(data).hexdigest()
    if expected and expected!=id_: raise ValueError("Image checksum mismatch")
    target=store.root/"media"/id_
    with store.lock:
        if not target.exists():
            temp=target.with_suffix("."+uuid.uuid4().hex+".tmp")
            try:
                temp.write_bytes(data)
                os.replace(temp,target)
            finally:
                temp.unlink(missing_ok=True)
    return id_

def image_path(store,id_):
    return store.root/"media"/require_media_id(id_)

def import_remote_image(store,url):
    from .webdata import safe_fetch
    data,kind,status,_=safe_fetch(url,limit=8_000_000)
    if status!=200 or not kind.startswith("image/"): raise ValueError("Product image could not be retrieved")
    validate_image(data)
    with Image.open(io.BytesIO(data)) as source:
        image=ImageOps.exif_transpose(source).convert("RGB")
        image.thumbnail((1600,1600))
        output=io.BytesIO();image.save(output,"JPEG",quality=90)
    return save_image(store,output.getvalue())

def cutout(store,id_):
    import numpy as np
    import onnxruntime as ort
    model=Path(__file__).resolve().parents[2]/"models"/"u2netp.onnx"
    if not model.exists(): raise ValueError("Cutout model is missing; run Setup to install the bundled model")
    options=ort.SessionOptions();options.intra_op_num_threads=1;options.inter_op_num_threads=1
    options.enable_cpu_mem_arena=False;options.enable_mem_pattern=False
    session=ort.InferenceSession(str(model),sess_options=options,providers=["CPUExecutionProvider"])
    with Image.open(image_path(store,id_)) as source:
        image=ImageOps.exif_transpose(source).convert("RGB")
        image.thumbnail((1600,1600))
        sample=np.asarray(image.resize((320,320))).astype("float32")/255
        sample=(sample-np.array([.485,.456,.406],dtype="float32"))/np.array([.229,.224,.225],dtype="float32")
        prediction=session.run([session.get_outputs()[0].name],{session.get_inputs()[0].name:sample.transpose(2,0,1)[None]})[0][0,0]
        mask=((prediction-prediction.min())/max(float(prediction.max()-prediction.min()),1e-6)*255).astype("uint8")
        alpha=Image.fromarray(mask).resize(image.size,Image.Resampling.LANCZOS)
        rgba=image.convert("RGBA");rgba.putalpha(alpha)
        output=io.BytesIO();rgba.save(output,"PNG")
    return {"imageId":save_image(store,output.getvalue()),"sourceImageId":id_}

