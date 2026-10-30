"""Relevance filtering and technique-aware selection of reference photographs."""

from dataclasses import dataclass
from math import isfinite, sqrt

from .domain import TECHNIQUE_FIELDS, Candidate, RankedReference


@dataclass(frozen=True)
class RankingConfig:
    min_similarity: float = 0.35
    relevance_weight: float = 1.0
    coverage_weight: float = 0.35
    repetition_weight: float = 0.2
    near_duplicate_similarity: float = 0.985
    hash_distance: int = 4

    def __post_init__(self):
        for name in ("min_similarity", "near_duplicate_similarity"):
            value = getattr(self, name)
            if not isfinite(value) or not -1 <= value <= 1:
                raise ValueError(f"{name} must be a finite cosine in [-1, 1]")
        weights = (self.relevance_weight, self.coverage_weight, self.repetition_weight)
        if (
            any(not isfinite(weight) or weight < 0 for weight in weights)
            or not sum(weights)
            or not isfinite(sum(weights))
        ):
            raise ValueError("Ranking weights must be finite, nonnegative, and not all zero")
        if (
            isinstance(self.hash_distance, bool)
            or not isinstance(self.hash_distance, int)
            or not 0 <= self.hash_distance <= 64
        ):
            raise ValueError("hash_distance must be an integer in [0, 64]")


def rerank(
    candidates: list[Candidate], *, limit: int = 6, config: RankingConfig = RankingConfig()
) -> list[RankedReference]:
    """Gate raw cosine, remove duplicates, then greedily cover known techniques.

    Every selection, including the first, maximizes the configured utility. All
    reported components use fixed [0, 1] scales, independent of the candidate
    pool. The final score is a shifted, normalized weighted utility; scores
    describe the selection step and need not decrease across the resulting list.
    """
    if isinstance(limit, bool) or not isinstance(limit, int) or limit < 0:
        raise ValueError("limit must be a nonnegative integer")
    if limit == 0:
        return []

    remaining: list[Candidate] = []
    eligible = sorted(
        (
            candidate
            for candidate in candidates
            if isfinite(candidate.similarity) and candidate.similarity >= config.min_similarity
        ),
        key=lambda candidate: (-candidate.similarity, candidate.id),
    )
    for candidate in eligible:
        if not any(_duplicates(candidate, prior, config) for prior in remaining):
            remaining.append(candidate)

    selected: list[RankedReference] = []
    covered: set[tuple[str, str]] = set()
    while remaining and len(selected) < limit:
        scored = [_score(candidate, selected, covered, config) for candidate in remaining]
        best = min(
            scored,
            key=lambda result: (-result.ranking_score, result.candidate.id),
        )
        selected.append(best)
        covered.update(_techniques(best.candidate))
        remaining.remove(best.candidate)
    return selected


def _techniques(candidate: Candidate) -> set[tuple[str, str]]:
    return {
        (field, value.strip())
        for field in TECHNIQUE_FIELDS
        if isinstance(value := candidate.tags.get(field), str) and value.strip()
    }


def _score(
    candidate: Candidate,
    selected: list[RankedReference],
    covered: set[tuple[str, str]],
    config: RankingConfig,
) -> RankedReference:
    techniques = _techniques(candidate)
    new_techniques = techniques - covered
    coverage = len(new_techniques) / len(TECHNIQUE_FIELDS)
    repetition = max(
        (
            len(techniques & _techniques(item.candidate)) / len(TECHNIQUE_FIELDS)
            for item in selected
        ),
        default=0.0,
    )
    relevance = (max(-1.0, min(1.0, candidate.similarity)) + 1) / 2
    total = config.relevance_weight + config.coverage_weight + config.repetition_weight
    utility = (
        config.relevance_weight * relevance
        + config.coverage_weight * coverage
        - config.repetition_weight * repetition
    )
    score = (utility + config.repetition_weight) / total
    # Explain only supplied metadata; absence of tags is never inferred diversity.
    labels = {"angle": "視角", "shot_size": "景別", "composition": "構圖"}
    reason_techniques = dict(new_techniques or techniques)
    reasons = tuple(
        f"參考{labels[field]}：{reason_techniques[field]}"
        for field in TECHNIQUE_FIELDS
        if field in reason_techniques
    ) or ("內容相似度符合門檻",)
    return RankedReference(candidate, relevance, coverage, repetition, score, reasons)


def _duplicates(left: Candidate, right: Candidate, config: RankingConfig) -> bool:
    if left.id == right.id:
        return True
    if left.pixel_hash and left.pixel_hash == right.pixel_hash:
        return True
    if left.duplicate_group and left.duplicate_group == right.duplicate_group:
        return True

    # Content embeddings alone must not erase other views of the same subject.
    if left.category and right.category and left.category != right.category:
        return False
    left_tags, right_tags = dict(_techniques(left)), dict(_techniques(right))
    if any(left_tags[field] != right_tags[field] for field in left_tags.keys() & right_tags.keys()):
        return False
    if not _close_hash(left.perceptual_hashes, right.perceptual_hashes, config.hash_distance):
        return False
    cosine = _cosine(left.vector, right.vector)
    return cosine is not None and cosine >= config.near_duplicate_similarity


def _close_hash(left: tuple[str, ...], right: tuple[str, ...], distance: int) -> bool:
    for left_hash in left:
        for right_hash in right:
            # dHash metadata is at most 64 bits; malformed hashes are not evidence.
            try:
                if not left_hash or not right_hash or max(len(left_hash), len(right_hash)) > 16:
                    continue
                left_value, right_value = int(left_hash, 16), int(right_hash, 16)
            except ValueError:
                continue
            if min(left_value, right_value) < 0:
                continue
            if (left_value ^ right_value).bit_count() <= distance:
                return True
    return False


def _cosine(left: tuple[float, ...], right: tuple[float, ...]) -> float | None:
    if not left or len(left) != len(right):
        return None
    if not all(isfinite(value) for value in (*left, *right)):
        return None
    left_norm = sqrt(sum(value * value for value in left))
    right_norm = sqrt(sum(value * value for value in right))
    if not left_norm or not right_norm:
        return None
    return sum(a * b for a, b in zip(left, right, strict=True)) / (left_norm * right_norm)
