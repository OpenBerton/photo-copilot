"""Image-to-reference workflow, independent of HTTP and encoder implementation."""

from threading import RLock

from .config import Settings
from .encoder import SiglipEncoder, load_image
from .ranking import rerank
from .store import NumpyStore


class RecommendationService:
    def __init__(self, settings: Settings, encoder: SiglipEncoder, store: NumpyStore):
        self.settings, self.encoder, self.store = settings, encoder, store
        self.metadata = store.metadata()
        if self.metadata is None:
            raise ValueError("No index found. Build the index before starting recommendations")
        if self.metadata.get("encoder_signature") != encoder.signature:
            raise ValueError("Encoder or preprocessing differs from the index. Rebuild the index")
        self._lock = RLock()

    def recommend(self, image_bytes: bytes, limit: int = 6) -> dict:
        if not image_bytes or len(image_bytes) > self.settings.max_upload_bytes:
            raise ValueError("Image is empty or exceeds the upload limit")
        with load_image(image_bytes, max_pixels=self.settings.max_pixels) as image:
            # One model inference at a time for this local MVP. Image work runs in
            # a worker thread, rather than blocking FastAPI's event loop.
            with self._lock:
                vector = self.encoder.encode([image])[0]
                candidates = self.store.search(
                    vector,
                    limit=self.settings.candidate_limit,
                    min_similarity=self.settings.min_similarity,
                )
        selected = rerank(candidates, limit=limit, config=self.settings.ranking())
        return {
            "demo": bool(self.metadata.get("demo", False)),
            "candidate_count": len(candidates),
            "returned": len(selected),
            "results": [
                {
                    "id": item.candidate.id,
                    "image_path": f"/images/{item.candidate.id}",
                    "cosine_similarity": item.candidate.similarity,
                    "relevance_score": item.relevance_score,
                    "coverage_gain": item.coverage_gain,
                    "repetition_penalty": item.repetition_penalty,
                    "ranking_score": item.ranking_score,
                    "tags": item.candidate.tags,
                    "reference_reasons": list(item.reasons),
                }
                for item in selected
            ],
        }

    def close(self):
        with self._lock:
            self.store.close()
