"""Load editable catalogue metadata. Aesthetic review happens outside this MVP."""

import json
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .domain import TECHNIQUE_FIELDS


class Reference(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    id: str = Field(pattern=r"^[a-zA-Z0-9][a-zA-Z0-9_-]{0,79}$")
    image_path: str = Field(min_length=1)
    approved: bool = False
    category: str | None = None
    tags: dict[str, str | None] = Field(default_factory=dict)
    duplicate_group: str | None = None
    source: str = Field(min_length=1)

    @model_validator(mode="after")
    def validate_tags(self):
        if set(self.tags) - set(TECHNIQUE_FIELDS):
            raise ValueError(f"Technique fields must be drawn from {TECHNIQUE_FIELDS}")
        if any(value is not None and not value.strip() for value in self.tags.values()):
            raise ValueError("Use null for unknown technique labels, not empty strings")
        return self


class Catalog(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: int = Field(default=1, ge=1, le=1)
    demo: bool = False
    references: list[Reference]

    @model_validator(mode="after")
    def unique_ids(self):
        ids = [entry.id for entry in self.references]
        if len(ids) != len(set(ids)):
            raise ValueError("Reference ids must be unique")
        return self


def load_catalog(path: Path, *, allow_demo: bool = False) -> tuple[Catalog, list[dict]]:
    path = path.resolve()
    catalog = Catalog.model_validate(json.loads(path.read_text(encoding="utf-8")))
    if catalog.demo and not allow_demo:
        raise ValueError(
            "Demo catalogue requires --allow-demo; it is not an aesthetic-reviewed library"
        )
    records = []
    for reference in catalog.references:
        if not reference.approved and not (catalog.demo and allow_demo):
            continue
        image_path = (path.parent / reference.image_path).resolve()
        if not image_path.is_file():
            raise ValueError(f"Image missing for reference {reference.id}: {image_path}")
        record = reference.model_dump()
        record["image_path"] = str(image_path)
        records.append(record)
    if not records:
        raise ValueError("No approved references to index")
    return catalog, records
