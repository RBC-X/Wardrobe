from __future__ import annotations
from typing import Literal
from pydantic import BaseModel, Field, field_validator

Category = Literal["top", "bottom", "onepiece", "outerwear", "shoes", "accessory"]

class Garment(BaseModel):
    id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,80}$")
    name: str = Field(min_length=1, max_length=200)
    category: Category
    subcategory: str = Field(default="", max_length=80)
    color: str = Field(default="#808080", pattern=r"^#[0-9a-fA-F]{6}$")
    secondaryColors: list[str] = Field(default_factory=list, max_length=5)
    pattern: str = Field(default="solid", max_length=80)
    texture: str = Field(default="unknown", max_length=80)
    fit: str = Field(default="regular", max_length=80)
    warmth: int = Field(default=1, ge=0, le=3)
    formality: int = Field(default=1, ge=0, le=3)
    styleTags: list[str] = Field(default_factory=lambda:["casual"], max_length=20)
    season: str = Field(default="all", max_length=50)
    status: Literal["available", "laundry", "excluded"] = "available"
    imageIds: list[str] = Field(default_factory=list, max_length=8)
    cutoutId: str = ""
    brand: str = Field(default="", max_length=100)
    brandConfirmed: bool = False
    productUrl: str = Field(default="", max_length=2048)
    composition: str = Field(default="", max_length=400)
    compositionVerified: bool = False
    manualFields: list[str] = Field(default_factory=list, max_length=40)
    provenance: dict = Field(default_factory=dict)
    revision: int = Field(default=1, ge=1)
    updatedAt: str = ""
    deleted: bool = False

    @field_validator("imageIds")
    @classmethod
    def image_hashes(cls, values):
        import re
        if any(not re.fullmatch(r"[a-f0-9]{64}", v) for v in values):
            raise ValueError("Image IDs must be SHA-256 hashes")
        return values

class Profile(BaseModel):
    id: str = "profile"
    styles: list[str] = Field(default_factory=lambda:["casual"],max_length=20)
    favoriteColors: list[str] = Field(default_factory=list,max_length=20)
    fit: str = "regular"
    sizes: str = Field(default="", max_length=300)
    dislikedCombinations: str = Field(default="",max_length=1000)
    likedItems: list[str] = Field(default_factory=list,max_length=1000)
    dislikedItems: list[str] = Field(default_factory=list,max_length=1000)
    region: str = Field(default="", max_length=80)
    currency: str = Field(default="USD", max_length=8)
    shoppingBudget: float = Field(default=0,ge=0,le=100000)
    stores: list[str] = Field(default_factory=list,max_length=40)
    referenceImageId: str = ""
    revision: int = Field(default=1, ge=1)

class Outfit(BaseModel):
    id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,80}$")
    itemIds: list[str] = Field(min_length=2,max_length=8)
    score: float = Field(default=0,ge=0,le=100)
    foundation: float = Field(default=0,ge=0,le=100)
    personal: float = Field(default=0,ge=0,le=100)
    trend: float = Field(default=0,ge=0,le=100)
    reasons: list[str] = Field(default_factory=list,max_length=20)
    trendSources: list[dict] = Field(default_factory=list,max_length=20)
    mode: Literal["timeless","balanced","trend"] = "balanced"
    occasion: str = Field(default="any",max_length=80)
    weather: str = Field(default="mild",max_length=40)
    season: str = Field(default="all",max_length=40)
    revision: int = Field(default=1,ge=1)
    name: str = Field(default="Saved outfit",max_length=200)
    previewImageId: str = ""

    @field_validator("itemIds")
    @classmethod
    def unique_items(cls,values):
        import re
        if len(values)!=len(set(values)) or any(not re.fullmatch(r"[A-Za-z0-9_-]{1,80}",v) for v in values): raise ValueError("Outfit item IDs must be unique wardrobe IDs")
        return values

class Change(BaseModel):
    changeId: str = Field(min_length=1,max_length=100)
    kind: Literal["garment", "profile", "outfit"]
    recordId: str = Field(pattern=r"^[A-Za-z0-9_-]{1,80}$")
    revision: int = Field(ge=1)
    deleted: bool = False
    payload: dict

class SyncRequest(BaseModel):
    cursor: int = Field(default=0,ge=0)
    changes: list[Change] = Field(default_factory=list,max_length=300)

class PairRequest(BaseModel):
    code: str = Field(min_length=20,max_length=200)
    deviceName: str = Field(default="My phone",max_length=100)

class ProviderConfig(BaseModel):
    enabled: bool = False
    apiKey: str | None = Field(default=None,max_length=1000)
    freeOnly: bool = True
    freeProjectConfirmed: bool = False
    monthlyBudget: float = Field(default=0,ge=0,le=10000)
    allowPhotos: bool = False
    backgroundAnalysis: bool = False

class JobRequest(BaseModel):
    kind: Literal["analysis", "style", "preview", "preview_probe", "search", "cutout", "trend_analysis"]
    provider: Literal["gemini", "openai", "codex", "claude", "local"]
    garmentId: str = Field(default="",max_length=80)
    revision: int = Field(default=0,ge=0)
    imageIds: list[str] = Field(default_factory=list,max_length=10)
    itemIds: list[str] = Field(default_factory=list,max_length=8)
    referenceImageId: str = ""
    useOwnPhoto: bool = False
    consentPhotos: bool = False
    mode: Literal["balanced", "timeless", "trend"] = "balanced"
    occasion: str = Field(default="any",max_length=80)
    weather: str = Field(default="mild",max_length=40)
    query: str = Field(default="",max_length=600)
    requestId: str = Field(min_length=1,max_length=100)

