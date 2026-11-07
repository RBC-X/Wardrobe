from __future__ import annotations
import hashlib
import io
import os
import threading
import uuid
from pathlib import Path
from PIL import Image, ImageOps
from .security import require_media_id

Image.MAX_IMAGE_PIXELS=12_000_000
MODEL_PATH=Path(__file__).resolve().parents[2]/"models"/"u2netp.onnx"
_session_lock=threading.Lock()
_session_cache=None

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

def session():
    """Load the bundled U²-Net once; every cutout or detection reuses the session."""
    global _session_cache
    if not MODEL_PATH.exists(): raise ValueError("Cutout model is missing; run Setup to install the bundled model")
    with _session_lock:
        if _session_cache is None:
            import onnxruntime as ort
            options=ort.SessionOptions();options.intra_op_num_threads=1;options.inter_op_num_threads=1
            options.enable_cpu_mem_arena=False;options.enable_mem_pattern=False
            _session_cache=ort.InferenceSession(str(MODEL_PATH),sess_options=options,providers=["CPUExecutionProvider"])
        return _session_cache

def _upright(source):
    image=ImageOps.exif_transpose(source).convert("RGB")
    image.thumbnail((1600,1600))
    return image

def _mask(image):
    """Soft clothing mask (0-255) for an upright, already-thumbnailed image."""
    import numpy as np
    model=session()
    sample=np.asarray(image.resize((320,320))).astype("float32")/255
    sample=(sample-np.array([.485,.456,.406],dtype="float32"))/np.array([.229,.224,.225],dtype="float32")
    prediction=model.run([model.get_outputs()[0].name],{model.get_inputs()[0].name:sample.transpose(2,0,1)[None]})[0][0,0]
    mask=((prediction-prediction.min())/max(float(prediction.max()-prediction.min()),1e-6)*255).astype("uint8")
    return np.asarray(Image.fromarray(mask).resize(image.size,Image.Resampling.LANCZOS))

def segment(store,id_):
    """Return the upright photo and its soft clothing mask (0-255) at the same size."""
    with Image.open(image_path(store,id_)) as source:
        image=_upright(source)
        return image,_mask(image)

def crop_suggestion(data):
    """How far to crop from each edge so only the garment remains.

    Works on a photo that is not stored yet, using the same mask as detection. Values are fractions
    of each edge, which is what the phone's crop dialog expects, and they can never exceed 40% per
    side so a small garment still leaves a usable frame.
    """
    import numpy as np
    validate_image(data)
    with Image.open(io.BytesIO(data)) as source:
        image=_upright(source);alpha=_mask(image)
    ys,xs=np.nonzero(alpha>=16)
    if not len(xs): return {"found":False,"left":0.0,"top":0.0,"right":0.0,"bottom":0.0}
    width,height=image.size;pad=0.03
    left_edge=float(xs.min());top_edge=float(ys.min());right_edge=float(xs.max())+1;bottom_edge=float(ys.max())+1
    values=[
        max(0.0,left_edge/width-pad),
        max(0.0,top_edge/height-pad),
        max(0.0,1.0-right_edge/width-pad),
        max(0.0,1.0-bottom_edge/height-pad),
    ]
    left,top,right,bottom=(round(min(float(value),.40),3) for value in values)
    return {"found":True,"left":left,"top":top,"right":right,"bottom":bottom}

def foreground(store,id_):
    """Trim the photo to the detected garment.

    Returns the RGBA crop, its mask crop, the bounding box in the original frame and the frame size.
    Everything downstream works on this crop, so a background can never enter colour or shape analysis.
    """
    import numpy as np
    image,alpha=segment(store,id_)
    ys,xs=np.nonzero(alpha>=16)
    if not len(xs): raise ValueError("No clothing shape was found in this photo; crop closer and try again")
    bbox=(int(xs.min()),int(ys.min()),int(xs.max())+1,int(ys.max())+1)
    rgba=image.convert("RGBA");rgba.putalpha(Image.fromarray(alpha))
    return rgba.crop(bbox),alpha[bbox[1]:bbox[3],bbox[0]:bbox[2]],bbox,image.size

def foreground_png(store,id_):
    """A background-free PNG used when an AI analysis has no saved cutout yet."""
    trimmed,_,_,_=foreground(store,id_)
    output=io.BytesIO();trimmed.save(output,"PNG")
    return output.getvalue()

def cutout(store,id_):
    trimmed,_,_,_=foreground(store,id_)
    output=io.BytesIO();trimmed.save(output,"PNG")
    return {"imageId":save_image(store,output.getvalue()),"sourceImageId":id_,"trimmed":True}
