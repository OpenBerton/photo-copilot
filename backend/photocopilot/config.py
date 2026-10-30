"""Configuration shared by offline indexing and the API."""

import os
import tomllib
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, model_validator

from .ranking import RankingConfig

BACKEND_ROOT = Path(__file__).resolve().parents[1]


class Settings(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, allow_inf_nan=False)

    model_id: str = "google/siglip2-base-patch16-224"
    model_revision: str = Field(pattern=r"^[a-f0-9]{40}$")
    model_cache: Path = Path(".cache/models")
    device: str = "cpu"
    catalog_path: Path = Path("demo_catalog.json")
    index_path: Path = Path(".data/numpy-index")
    candidate_limit: int = Field(default=200, ge=1, le=1000)
    result_limit: int = Field(default=6, ge=1, le=30)
    max_upload_bytes: int = Field(default=10_485_760, ge=1, le=52_428_800)
    max_pixels: int = Field(default=20_000_000, ge=1, le=100_000_000)
    min_similarity: float = Field(default=0.65, ge=-1, le=1)
    relevance_weight: float = Field(default=1, ge=0)
    coverage_weight: float = Field(default=0.35, ge=0)
    repetition_weight: float = Field(default=0.2, ge=0)
    near_duplicate_similarity: float = Field(default=0.985, ge=-1, le=1)
    hash_distance: int = Field(default=4, ge=0, le=64)

    @model_validator(mode="after")
    def valid_ranking(self):
        self.ranking()
        if self.result_limit > self.candidate_limit:
            raise ValueError("result_limit must not exceed candidate_limit")
        return self

    def ranking(self) -> RankingConfig:
        return RankingConfig(
            **{
                field: getattr(self, field)
                for field in (
                    "min_similarity",
                    "relevance_weight",
                    "coverage_weight",
                    "repetition_weight",
                    "near_duplicate_similarity",
                    "hash_distance",
                )
            }
        )


def load_settings(path: Path | str | None = None) -> Settings:
    path = Path(path or os.environ.get("PHOTOCOPILOT_CONFIG", BACKEND_ROOT / "config.toml"))
    path = path.resolve()
    with path.open("rb") as file:
        values = tomllib.load(file)
    settings = Settings(**values)
    return settings.model_copy(
        update={
            key: (path.parent / getattr(settings, key)).resolve()
            for key in ("model_cache", "catalog_path", "index_path")
        }
    )
