"""Deterministic style rules; kept equivalent to the Android domain engine."""
from __future__ import annotations
import colorsys
import hashlib
import itertools
import json
import math
from datetime import datetime, timedelta, timezone
from pathlib import Path

RULES_PATH = Path(__file__).resolve().parents[2] / "shared" / "rules.json"
RULES = json.loads(RULES_PATH.read_text(encoding="utf-8"))


def color_features(value: str) -> tuple[float, float, float]:
    try:
        rgb = tuple(int(value.lstrip("#")[i:i+2], 16) / 255 for i in (0, 2, 4))
        return colorsys.rgb_to_hsv(*rgb)
    except (ValueError, IndexError):
        return 0.0, 0.0, 0.5


def color_match(a: str, b: str) -> float:
    h1, s1, v1 = color_features(a)
    h2, s2, v2 = color_features(b)
    if min(s1, s2) < .18:
        return .94 if abs(v1-v2) > .18 else .82
    delta = min(abs(h1-h2), 1-abs(h1-h2)) * 360
    if delta <= 40:
        return .90
    if 145 <= delta <= 180:
        return .78
    if 90 <= delta <= 135:
        return .72
    return .62


def valid(items: list[dict], occasion="any", weather="mild", pinned="") -> bool:
    if not items or len({g["id"] for g in items}) != len(items):
        return False
    cats = [g["category"] for g in items]
    if not (("top" in cats and "bottom" in cats) or "onepiece" in cats) or "shoes" not in cats:
        return False
    if "onepiece" in cats and ("top" in cats or "bottom" in cats):
        return False
    if len(cats) != len(set(cats)):
        return False
    if any(g.get("status", "available") != "available" or g.get("deleted") for g in items):
        return False
    if pinned and pinned not in {g["id"] for g in items}:
        return False
    if occasion == "formal" and any(g.get("formality", 1) < 2 for g in items if g["category"] != "accessory"):
        return False
    if weather == "cold" and "outerwear" not in cats:
        return False
    if weather == "warm" and any(g.get("warmth", 1) >= 3 for g in items):
        return False
    return True


def score(items: list[dict], profile: dict | None = None, trends: list[dict] | None = None, mode="balanced", occasion="any", weather="mild", season="all") -> dict:
    profile, trends = profile if profile is not None else {"styles":["casual"],"fit":"regular"}, trends or []
    pairs = list(itertools.combinations(items, 2))
    color = sum(color_match(a.get("color", "#808080"), b.get("color", "#808080")) for a,b in pairs) / max(1, len(pairs))
    patterned = sum(g.get("pattern", "solid") != "solid" for g in items)
    pattern = max(.45, 1-.18*max(0, patterned-1))
    textures = {g.get("texture", "unknown") for g in items} - {"unknown"}
    texture = .82 if len(textures) > 1 else .72
    formalities = [g.get("formality", 1) for g in items if g["category"] != "accessory"]
    coherence = max(.35, 1-.18*(max(formalities)-min(formalities)))
    fits = [g.get("fit", "regular") for g in items if g["category"] in {"top", "bottom", "onepiece"}]
    proportion = .85 if len(set(fits)) > 1 else .75
    season_match = sum(season=="all" or g.get("season","all") in ("all",season) for g in items)/len(items)
    weight_match = max(.4,1-.15*(max(g.get("warmth",1) for g in items)-min(g.get("warmth",1) for g in items)))
    foundation = .36*color+.15*pattern+.14*texture+.17*coherence+.10*proportion+.04*season_match+.04*weight_match
    styles = set(profile.get("styles", []))
    colors = set(profile.get("favoriteColors", []))
    preferred_fit = profile.get("fit", "")
    likes = set(profile.get("likedItems", []))
    dislikes = set(profile.get("dislikedItems", []))
    preference = sum(.5 + (.2 if styles.intersection(g.get("styleTags", [])) else 0) + (.1 if g.get("color") in colors else 0) + (.1 if preferred_fit and g.get("fit") == preferred_fit else 0) + (.1 if g["id"] in likes else 0) - (.3 if g["id"] in dislikes else 0) for g in items) / len(items)
    attributes={str(value).lower() for g in items for value in [g.get("pattern",""),g.get("texture",""),g.get("color",""),g.get("subcategory",""),*g.get("styleTags",[])]}
    for combination in profile.get("dislikedCombinations","").split(";"):
        terms={v.strip().lower() for v in combination.split("+") if v.strip()}
        if len(terms)>=2 and terms.issubset(attributes): preference=max(0,preference-.3)
    now = datetime.now(timezone.utc)
    evidence = []
    trend_score = 0.0
    for trend in trends:
        try:
            expires=datetime.fromisoformat(trend.get("expiresAt", "").replace("Z","+00:00"))
            observed=datetime.fromisoformat(trend["observedAt"].replace("Z","+00:00")) if trend.get("observedAt") else now
            active=observed<=now<min(expires,observed+timedelta(days=90))
        except (ValueError,TypeError): active=False
        if not active or not trend.get("sources") or trend.get("confidence", 0) < .5 or (season!="all" and trend.get("season","all") not in ("all",season)):
            continue
        tags = set(trend.get("tags", []))
        matched = sum(bool(tags.intersection([g.get("subcategory", "").lower(), g.get("texture", ""), *g.get("styleTags", [])])) for g in items)
        if matched:
            trend_score = max(trend_score, min(1., matched/2)*trend.get("confidence", .5))
            evidence.append({"title": trend.get("title", "Observed trend"), "sources": trend["sources"]})
    weights = RULES["weights"].get(mode, RULES["weights"]["balanced"])
    total = 100*(weights[0]*foundation+weights[1]*preference+weights[2]*trend_score)
    reasons = ["A coordinated palette with intentional contrast." if color >= .8 else "A more expressive color combination.", "One focal pattern keeps the outfit clear." if patterned <= 1 else "Shared colors help multiple patterns connect.", "Different surfaces add texture and depth." if len(textures)>1 else "Similar surfaces give a quieter finish.", "The pieces have a consistent level of formality." if coherence >= .8 else "This combines relaxed and dressier pieces."]
    reasons.extend(["Palette: "+", ".join(g.get("name",g["id"])+" "+g.get("color","#808080") for g in items)+".", "Silhouette: "+", ".join(g.get("fit","regular") for g in items if g["category"] in {"top","bottom","onepiece"})+"; texture: "+", ".join(sorted(textures) or ["unknown"])+".", "Your preferences contribute "+str(round(preference*100))+"/100; season and fabric-weight appearance are ranking preferences, not guarantees."])
    return {"score": round(total, 4), "foundation": round(foundation*100, 4), "personal": round(preference*100, 4), "trend": round(trend_score*100, 4), "reasons": reasons, "trendSources": evidence}


def generate(garments: list[dict], profile=None, trends=None, mode="balanced", occasion="any", weather="mild", pinned="", limit=5, season="all") -> list[dict]:
    available = sorted((g for g in garments if g.get("status", "available") == "available" and not g.get("deleted") and (occasion!="formal" or g["category"]=="accessory" or g.get("formality",1)>=2) and (weather!="warm" or g.get("warmth",1)<3)), key=lambda g:g["id"])
    slots = {c: [g for g in available if g["category"] == c][:RULES["slotLimit"]] for c in RULES["categories"]}
    if pinned:
        anchor = next((g for g in available if g["id"] == pinned), None)
        if anchor is None:
            return []
        slots[anchor["category"]] = [anchor]
    candidates = []
    for template in RULES["templates"]:
        categories = list(template)
        if weather == "cold" or (pinned and anchor["category"] == "outerwear"):
            categories.append("outerwear")
        if pinned and anchor["category"] == "accessory":
            categories.append("accessory")
        beam = [[]]
        for category in categories:
            expanded = [partial+[g] for partial in beam for g in slots[category]]
            # Pair coherence for partial beams, deterministic tie-breaks.
            expanded.sort(key=lambda gs:(-sum(color_match(a.get("color", "#808080"), b.get("color", "#808080")) for a,b in itertools.combinations(gs,2)), ":".join(g["id"] for g in gs)))
            beam = expanded[:RULES["beamWidth"]]
        for items in beam:
            if valid(items, occasion, weather, pinned):
                ids = [g["id"] for g in items]
                candidates.append({"id": hashlib.sha256("|".join(sorted(ids)).encode()).hexdigest()[:24], "itemIds":ids, "mode": mode, "occasion": occasion, "weather": weather, "season":season, **score(items, profile, trends, mode, occasion, weather, season)})
    candidates.sort(key=lambda o:(-o["score"], o["id"]))
    selected = []
    for candidate in candidates:
        if all(len(set(candidate["itemIds"]) & set(old["itemIds"])) / max(len(candidate["itemIds"]),len(old["itemIds"])) < .85 for old in selected):
            selected.append(candidate)
            if len(selected) >= limit:
                break
    return selected


def gaps(garments: list[dict]) -> list[dict]:
    available = [g for g in garments if g.get("status", "available") == "available" and not g.get("deleted")]
    present = {g["category"] for g in available}
    result = []
    for category, title, color, texture in [("top","A simple neutral top","#f3eadb","smooth"),("bottom","Olive straight-leg trousers","#72734e","smooth"),("shoes","Minimal neutral sneakers","#f4f1e8","smooth"),("outerwear","A versatile light jacket","#9b7957","smooth")]:
        matches = [g for g in available if g["category"] != category and color_match(g.get("color","#808080"), color) >= .72]
        duplicate=any(g["category"]==category and color_match(g.get("color","#808080"),color)>=.8 for g in available)
        if not duplicate and (category not in present or len(matches)>=3):
            result.append({"category":category,"title":title,"color":color,"texture":texture,"matchingItemIds":[g["id"] for g in matches],"reason": f"Coordinates with {len(matches)} available pieces." if matches else f"Add a {category} to complete more outfit templates.","kind":"wardrobe_gap"})
    return sorted(result,key=lambda r:(r["category"] in present,-len(r["matchingItemIds"])))
