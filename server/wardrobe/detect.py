"""Background-free clothing detection from the segmented silhouette.

The bundled U²-Net mask decides what is clothing. Colour, type and shape are then read only from the
masked pixels, so a wooden floor or a bedroom wall can never be reported as part of the garment.
Nothing here calls a network or an AI provider, and every result is a suggestion the owner can correct.
"""
from __future__ import annotations
import io
from .images import foreground, save_image


def _channels(value):
    return [int(value[i:i+2],16) for i in (1,3,5)]


def _separation(first,second):
    return sum((a-b)**2 for a,b in zip(_channels(first),_channels(second)))**.5


def dominant_colors(rgba,alpha,limit=3):
    """Colours read only from pixels inside the clothing mask, merged across shading."""
    import numpy as np
    pixels=np.asarray(rgba.convert("RGB"),dtype="float64")
    mask=None
    for minimum in (200,64,16):
        candidate=alpha>=minimum
        if int(candidate.sum())>=64: mask=candidate;break
    if mask is None: return []
    values=pixels[mask]
    quant=((values//24)*24+12).astype("int32")
    keys=(quant[:,0]<<16)|(quant[:,1]<<8)|quant[:,2]
    unique,counts=np.unique(keys,return_counts=True)
    colors=[]
    for index in np.argsort(-counts):
        members=values[keys==unique[index]]
        mean=members.mean(axis=0)
        value="#%02x%02x%02x" % tuple(int(round(min(255,max(0,channel)))) for channel in mean)
        if all(_separation(value,known)>=36 for known in colors): colors.append(value)
        if len(colors)>=limit: break
    return colors


def profile(alpha):
    """Silhouette measurements from the mask alone (widths are relative to the widest row)."""
    import numpy as np
    mask=alpha>=128
    rows=np.nonzero(mask.any(axis=1))[0];columns=np.nonzero(mask.any(axis=0))[0]
    if not rows.size or not columns.size: raise ValueError("No clothing shape was found in this photo")
    top,bottom=int(rows[0]),int(rows[-1])+1
    left,right=int(columns[0]),int(columns[-1])+1
    height=bottom-top;width=right-left
    widths=mask.sum(axis=1).astype("float64")[top:bottom]
    widest=float(widths.max()) or 1.0
    def band(start,end):
        chunk=widths[max(0,int(height*start)):max(1,int(height*end))]
        return float(chunk.mean())/widest if chunk.size else 0.0
    middle=band(.40,.60)
    return {
        "height":height,"width":width,
        "aspect":round(height/max(width,1),3),
        "fill":round(float(widths.mean())/widest,3),
        "flare":round(band(.86,1)/max(middle,1e-6),3),
        "shoulder":round(band(.10,.30),3),
        "foregroundPixels":int(mask.sum()),
    }


def _near(value,target,tolerance):
    return max(0.0,1.0-abs(value-target)/tolerance)


def category_scores(metrics,area_share):
    """Explicit, inspectable evidence per category. Ties break alphabetically for determinism."""
    aspect=metrics["aspect"];fill=metrics["fill"];flare=metrics["flare"];shoulder=metrics["shoulder"]
    return {
        "accessory":0.9*_near(area_share,.02,.10)+0.5*_near(aspect,1.2,1.0)+0.2*_near(fill,.7,.5),
        "shoes":0.9*_near(aspect,.55,.45)+0.7*_near(fill,.85,.35)+0.3*_near(area_share,.35,.45),
        "top":0.9*_near(aspect,1.15,.55)+0.5*_near(fill,.75,.35)+0.3*_near(flare,1.0,.45),
        "bottom":0.8*_near(aspect,1.85,.8)+0.5*_near(shoulder,.72,.4)+0.4*_near(fill,.65,.4),
        "onepiece":0.8*_near(aspect,2.15,.95)+0.5*_near(flare,1.3,.55)+0.3*_near(fill,.7,.45),
        "outerwear":0.6*_near(aspect,1.5,.7)+0.6*_near(fill,.87,.28)+0.3*_near(shoulder,.95,.3),
    }


def classify(metrics,area_share):
    """Best category and a confidence that never claims certainty from an outline."""
    scores=category_scores(metrics,area_share)
    ranked=sorted(scores.items(),key=lambda item:(-item[1],item[0]))
    best,best_score=ranked[0];second=ranked[1][1] if len(ranked)>1 else 0.0
    margin=(best_score-second)/max(best_score,1e-6)
    quality=min(1.0,metrics["foregroundPixels"]/40_000)
    confidence=round(min(.85,max(.30,.32+.45*margin+.12*quality)),2)
    return best,confidence


def subtype(category,metrics):
    """Only suggest a subtype when the outline genuinely narrows it down."""
    if category=="bottom" and metrics["aspect"]<1.05: return "Shorts"
    if category=="onepiece": return "Dress"
    return ""


def shape(metrics):
    parts=[]
    if metrics["aspect"]>=2.2: parts.append("longline")
    elif metrics["aspect"]<=.85: parts.append("compact")
    if metrics["flare"]>=1.22: parts.append("flared hem")
    elif metrics["flare"]<=.82: parts.append("tapered")
    if metrics["shoulder"]<=.62: parts.append("narrow shoulder")
    if metrics["fill"]>=.85: parts.append("full silhouette")
    elif metrics["fill"]<=.52: parts.append("slim silhouette")
    return ", ".join(parts[:3]) or "straight silhouette"


def guess_fit(metrics):
    if metrics["fill"]>=.90: return "oversized"
    if metrics["fill"]>=.74: return "relaxed"
    if metrics["fill"]<=.45: return "slim"
    return "regular"


def detect_clothing(store,image_id):
    """Detect type, colour and shape from the garment only, and store the trimmed transparent cutout."""
    trimmed,alpha,bbox,frame=foreground(store,image_id)
    metrics=profile(alpha)
    if not metrics["foregroundPixels"]: raise ValueError("No clothing shape was found in this photo")
    colors=dominant_colors(trimmed,alpha)
    if not colors: raise ValueError("The garment colours could not be read; retake the photo in even light")
    area_share=metrics["foregroundPixels"]/max(1,frame[0]*frame[1])
    category,confidence=classify(metrics,area_share)
    subcategory=subtype(category,metrics)
    output=io.BytesIO();trimmed.save(output,"PNG")
    cutout_id=save_image(store,output.getvalue())
    metrics.update(
        bbox=[round(bbox[0]/frame[0],3),round(bbox[1]/frame[1],3),round((bbox[2]-bbox[0])/frame[0],3),round((bbox[3]-bbox[1])/frame[1],3)],
        areaShare=round(area_share,3),frame={"width":frame[0],"height":frame[1]},
    )
    return {
        "category":category,
        "subcategory":subcategory,
        "color":colors[0],
        "secondaryColors":colors[1:3],
        "shape":shape(metrics),
        "fit":guess_fit(metrics),
        "confidence":confidence,
        "fieldConfidence":{"category":confidence,"color":0.85,"shape":0.70,"fit":0.55,"subcategory":0.45 if subcategory else 0.0},
        "metrics":metrics,
        "cutoutId":cutout_id,
        "sourceImageId":image_id,
        "backgroundExcluded":True,
        "reviewRequired":True,
        "notes":"Detected from the garment outline with the background removed. Check the type, colour and shape before applying.",
    }
