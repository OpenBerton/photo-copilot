"""Small shared data types for retrieval and reranking."""

from dataclasses import dataclass, field

TECHNIQUE_FIELDS = ("angle", "shot_size", "composition")


@dataclass(frozen=True)
class Candidate:
    id: str
    image_path: str
    similarity: float
    tags: dict[str, str | None] = field(default_factory=dict)
    vector: tuple[float, ...] = ()
    pixel_hash: str = ""
    perceptual_hashes: tuple[str, ...] = ()
    duplicate_group: str | None = None
    category: str | None = None


@dataclass(frozen=True)
class RankedReference:
    candidate: Candidate
    relevance_score: float
    coverage_gain: float
    repetition_penalty: float
    ranking_score: float
    reasons: tuple[str, ...]
