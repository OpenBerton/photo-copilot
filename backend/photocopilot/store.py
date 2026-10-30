"""Exact cosine search over immutable, atomically published NumPy snapshots.

JSON records are bound to corresponding normalized .npy rows. current.json commits
the generation after both files are complete. Existing readers keep their loaded
snapshot; earlier generations remain available for concurrent startup readers.
New snapshots keep photo and catalog paths relative to the index directory so a
checkout can move between machines without rebuilding unchanged vectors.
"""

import hashlib
import json
import logging
import math
import os
import re
from collections.abc import Sequence
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path, PureWindowsPath
from threading import RLock
from uuid import uuid4

import numpy as np

from photocopilot.domain import Candidate

_SCHEMA_VERSION = 1
_GENERATION = re.compile(r"[a-f0-9]{32}")
_DIGEST = re.compile(r"[a-f0-9]{64}")
_PATH_BASE = "index_directory"
logger = logging.getLogger(__name__)


def _json_copy(value):
    try:
        return json.loads(json.dumps(value, allow_nan=False))
    except (TypeError, ValueError, OverflowError) as error:
        raise ValueError("Index payload must contain finite JSON values") from error


def _json_bytes(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True).encode("utf-8")


def _unit_vector(vector: Sequence[float], dimension: int | None = None) -> list[float]:
    try:
        if isinstance(vector, (str, bytes)):
            raise ValueError("A vector must be a sequence of numbers")
        values = [float(value) for value in vector]
    except (TypeError, ValueError, OverflowError) as error:
        raise ValueError("A vector must contain finite numbers") from error
    if not values or any(not math.isfinite(value) for value in values):
        raise ValueError("A vector must contain finite numbers")
    if dimension is not None and len(values) != dimension:
        raise ValueError(f"Expected vector dimension {dimension}, got {len(values)}")
    norm = math.hypot(*values)
    if not math.isfinite(norm) or norm == 0:
        raise ValueError("A vector must have a finite, nonzero norm")
    return [value / norm for value in values]


def _record_payload(record: dict) -> dict:
    if not isinstance(record, dict):
        raise ValueError("Each index record must be an object")
    for key in ("id", "image_path"):
        if not isinstance(record.get(key), str) or not record[key].strip():
            raise ValueError(f"Each index record needs a nonempty {key}")
    tags = record.get("tags", {})
    if not isinstance(tags, dict) or any(
        not isinstance(key, str) or (value is not None and not isinstance(value, str))
        for key, value in tags.items()
    ):
        raise ValueError("Technique tags must be strings or null")
    for key in ("category", "duplicate_group"):
        if record.get(key) is not None and not isinstance(record[key], str):
            raise ValueError(f"{key} must be a string or null")
    pixel_hash = record.get("pixel_hash", "")
    hashes = record.get("perceptual_hashes", [])
    if (
        not isinstance(pixel_hash, str)
        or not isinstance(hashes, (list, tuple))
        or any(not isinstance(value, str) for value in hashes)
    ):
        raise ValueError("Image hashes must be strings")
    payload = {
        "id": record["id"],
        "image_path": record["image_path"],
        "tags": tags,
        "category": record.get("category"),
        "duplicate_group": record.get("duplicate_group"),
        "pixel_hash": pixel_hash,
        "perceptual_hashes": list(hashes),
    }
    if "source" in record:
        payload["source"] = record["source"]
    return _json_copy(payload)


def _validate_metadata(metadata: dict) -> dict:
    if not isinstance(metadata, dict):
        raise ValueError("Index metadata must be an object")
    result = _json_copy(metadata)
    signature = result.get("encoder_signature")
    if not isinstance(signature, dict) or not signature:
        raise ValueError("Index metadata needs a nonempty encoder_signature object")
    return result


def _positive_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _row_digest(vector: np.ndarray) -> str:
    return hashlib.sha256(vector.tobytes(order="C")).hexdigest()


def _relative_path(value: str, directory: Path) -> str:
    try:
        return Path(os.path.relpath(Path(value).resolve(), directory)).as_posix()
    except ValueError as error:
        raise ValueError("Index images and catalog must be on the same filesystem drive") from error


def _resolved_path(value: str, directory: Path) -> str:
    if (
        not isinstance(value, str)
        or not value.strip()
        or Path(value).is_absolute()
        or PureWindowsPath(value).drive
    ):
        raise ValueError("Portable index paths must be relative to the index directory")
    return str((directory / value).resolve())


@dataclass(frozen=True)
class _Snapshot:
    vectors: np.ndarray
    records: tuple[dict, ...]
    metadata: dict
    id_to_row: dict[str, int]


def _candidate(snapshot: _Snapshot, row: int, similarity: float = 0.0) -> Candidate:
    payload = snapshot.records[row]
    return Candidate(
        id=payload["id"],
        image_path=payload["image_path"],
        similarity=max(-1.0, min(1.0, float(similarity))),
        tags=dict(payload["tags"]),
        vector=tuple(snapshot.vectors[row].tolist()),
        pixel_hash=payload["pixel_hash"],
        perceptual_hashes=tuple(payload["perceptual_hashes"]),
        duplicate_group=payload["duplicate_group"],
        category=payload["category"],
    )


class NumpyStore:
    """Load one snapshot and search float64 unit vectors exactly.

    Replacement publishes with one atomic manifest rename and changes only this
    writer's snapshot. Other instances serve their original snapshot until restart.
    """

    def __init__(self, path: Path):
        self._path = Path(path).resolve()
        self._lock = RLock()
        self._closed = False
        self._snapshot = self._load()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    def _ensure_open(self):
        if self._closed:
            raise RuntimeError("NumpyStore is closed")

    def _read_file(self, name: str) -> bytes:
        path = self._path / name
        if path.is_symlink() or path.resolve().parent != self._path.resolve():
            raise ValueError("Index files must stay inside the index directory")
        try:
            return path.read_bytes()
        except OSError as error:
            raise ValueError(f"Cannot read index file {name!r}") from error

    def _load(self) -> _Snapshot | None:
        if not self._path.exists():
            return None
        if not self._path.is_dir():
            raise ValueError("Index path must be a directory")
        manifest_path = self._path / "current.json"
        if not manifest_path.exists():
            if manifest_path.is_symlink():
                raise ValueError("Index manifest must not be a symbolic link")
            if any(self._path.iterdir()):
                raise ValueError("Nonempty index directory has no NumPy manifest; use a new path")
            return None
        try:
            manifest = json.loads(self._read_file("current.json"))
            if (
                not isinstance(manifest, dict)
                or not _positive_int(manifest.get("schema_version"))
                or manifest["schema_version"] != _SCHEMA_VERSION
            ):
                raise ValueError("Unsupported index manifest schema")
            generation = manifest.get("generation")
            if not isinstance(generation, str) or not _GENERATION.fullmatch(generation):
                raise ValueError("Invalid index generation")
            contents = {}
            for kind, extension in (("vectors", "npy"), ("metadata", "json")):
                file = manifest.get(kind)
                if (
                    not isinstance(file, dict)
                    or file.get("file") != f"{kind}-{generation}.{extension}"
                    or not isinstance(file.get("sha256"), str)
                    or not _DIGEST.fullmatch(file["sha256"])
                ):
                    raise ValueError("Invalid index file reference")
                data = self._read_file(file["file"])
                if hashlib.sha256(data).hexdigest() != file["sha256"]:
                    raise ValueError(f"Index {kind} checksum mismatch")
                contents[kind] = data
            document = json.loads(contents["metadata"])
            if (
                not isinstance(document, dict)
                or not _positive_int(document.get("schema_version"))
                or document.get("schema_version") != _SCHEMA_VERSION
                or document.get("generation") != generation
            ):
                raise ValueError("Index metadata belongs to a different generation")
            metadata = _validate_metadata(document.get("metadata"))
            path_base = document.get("path_base")
            if path_base not in (None, _PATH_BASE):
                raise ValueError("Unsupported index path base")
            if path_base == _PATH_BASE and "catalog_path" in metadata:
                metadata["catalog_path"] = _resolved_path(metadata["catalog_path"], self._path)
            count, dimension = metadata.get("count"), metadata.get("vector_size")
            if not _positive_int(count) or not _positive_int(dimension):
                raise ValueError("Index count and vector dimension must be positive integers")
            vectors = np.load(BytesIO(contents["vectors"]), allow_pickle=False)
            if (
                not isinstance(vectors, np.ndarray)
                or vectors.dtype != np.float64
                or vectors.ndim != 2
                or vectors.shape != (count, dimension)
                or not np.isfinite(vectors).all()
                or not np.allclose(np.linalg.norm(vectors, axis=1), 1.0, rtol=0, atol=1e-12)
            ):
                raise ValueError("Index vectors have invalid shape, dtype or unit norms")
            records = document.get("records")
            if not isinstance(records, list) or len(records) != count:
                raise ValueError("Index needs exactly one record per vector row")
            payloads = []
            for row, record in enumerate(records):
                payload = _record_payload(record)
                if record.get("vector_sha256") != _row_digest(vectors[row]):
                    raise ValueError("Index record is not bound to its vector row")
                if path_base == _PATH_BASE:
                    payload["image_path"] = _resolved_path(payload["image_path"], self._path)
                payloads.append(payload)
            ids = [record["id"] for record in payloads]
            if len(set(ids)) != count:
                raise ValueError("Reference IDs must be unique")
            vectors.flags.writeable = False
            return _Snapshot(
                vectors, tuple(payloads), metadata, dict(zip(ids, range(count), strict=True))
            )
        except (
            OSError,
            EOFError,
            TypeError,
            KeyError,
            json.JSONDecodeError,
            UnicodeDecodeError,
        ) as error:
            raise ValueError("Invalid or incomplete NumPy index") from error

    def metadata(self) -> dict | None:
        with self._lock:
            self._ensure_open()
            snapshot = self._snapshot
        return _json_copy(snapshot.metadata) if snapshot else None

    def replace(
        self,
        records: Sequence[dict],
        vectors: Sequence[Sequence[float]],
        metadata: dict,
    ) -> int:
        """Write a complete generation without deleting earlier index data."""
        with self._lock:
            self._ensure_open()
            if len(records) == 0 or len(records) != len(vectors):
                raise ValueError("A nonempty catalog needs exactly one vector per record")
            payloads = [_record_payload(record) for record in records]
            for payload in payloads:
                payload["image_path"] = str(Path(payload["image_path"]).resolve())
            ids = [record["id"] for record in payloads]
            if len(set(ids)) != len(ids):
                raise ValueError("Reference IDs must be unique")
            unit_vectors = [_unit_vector(vector) for vector in vectors]
            dimension = len(unit_vectors[0])
            if any(len(vector) != dimension for vector in unit_vectors):
                raise ValueError("All index vectors must have the same dimension")
            matrix = np.asarray(unit_vectors, dtype=np.float64)
            index_metadata = _validate_metadata(metadata)
            index_metadata.update(count=len(records), vector_size=dimension)
            stored_metadata = _json_copy(index_metadata)
            if "catalog_path" in index_metadata:
                catalog_path = index_metadata["catalog_path"]
                if not isinstance(catalog_path, str) or not catalog_path.strip():
                    raise ValueError("Catalog path must be a nonempty string")
                index_metadata["catalog_path"] = str(Path(catalog_path).resolve())
                stored_metadata["catalog_path"] = _relative_path(catalog_path, self._path)
            generation = uuid4().hex
            document = {
                "schema_version": _SCHEMA_VERSION,
                "generation": generation,
                "path_base": _PATH_BASE,
                "metadata": stored_metadata,
                "records": [
                    {
                        **record,
                        "image_path": _relative_path(record["image_path"], self._path),
                        "vector_sha256": _row_digest(matrix[row]),
                    }
                    for row, record in enumerate(payloads)
                ],
            }
            buffer = BytesIO()
            np.save(buffer, matrix, allow_pickle=False)
            files = {
                "vectors": (f"vectors-{generation}.npy", buffer.getvalue()),
                "metadata": (f"metadata-{generation}.json", _json_bytes(document)),
            }
            manifest = {"schema_version": _SCHEMA_VERSION, "generation": generation}
            # A path created or changed by another process after startup must not
            # accidentally become an old Qdrant directory or unrelated data folder.
            self._load()
            self._path.mkdir(parents=True, exist_ok=True)
            created = []
            published = False
            try:
                for kind, (name, data) in files.items():
                    path = self._path / name
                    with path.open("xb") as stream:
                        created.append(path)
                        stream.write(data)
                        stream.flush()
                        os.fsync(stream.fileno())
                    manifest[kind] = {"file": name, "sha256": hashlib.sha256(data).hexdigest()}
                temporary = self._path / f"current-{generation}.tmp"
                with temporary.open("xb") as stream:
                    created.append(temporary)
                    stream.write(_json_bytes(manifest))
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, self._path / "current.json")
                published = True
            except BaseException:
                if not published:
                    # Only files exclusively created by this failed attempt may
                    # be removed. Prior snapshots and unrelated files stay intact.
                    for path in reversed(created):
                        try:
                            path.unlink(missing_ok=True)
                        except OSError:
                            logger.warning("Could not remove unpublished index file %s", path)
                raise
            # Windows does not support opening directories for fsync. Files are
            # still flushed before the atomic manifest replacement on every OS.
            if os.name != "nt":
                descriptor = os.open(self._path, os.O_RDONLY)
                try:
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
            matrix.flags.writeable = False
            self._snapshot = _Snapshot(
                matrix,
                tuple(payloads),
                index_metadata,
                dict(zip(ids, range(len(ids)), strict=True)),
            )
            return len(records)

    def search(
        self,
        vector: Sequence[float],
        *,
        limit: int = 200,
        min_similarity: float = 0.35,
    ) -> list[Candidate]:
        with self._lock:
            self._ensure_open()
            snapshot = self._snapshot
        if not _positive_int(limit):
            raise ValueError("Candidate limit must be a positive integer")
        try:
            threshold = float(min_similarity)
        except (TypeError, ValueError, OverflowError) as error:
            raise ValueError("Minimum similarity must lie between -1 and 1") from error
        if not math.isfinite(threshold) or not -1 <= threshold <= 1:
            raise ValueError("Minimum similarity must lie between -1 and 1")
        query = _unit_vector(vector, snapshot.metadata["vector_size"] if snapshot else None)
        if snapshot is None:
            return []
        scores = np.clip(snapshot.vectors @ np.asarray(query, dtype=np.float64), -1.0, 1.0)
        rows = np.flatnonzero(scores >= threshold)
        # Stable public ordering even when multiple photos have the same vector.
        ranked = sorted(rows, key=lambda row: (-float(scores[row]), snapshot.records[row]["id"]))
        return [_candidate(snapshot, row, scores[row]) for row in ranked[:limit]]

    def get(self, reference_id: str) -> Candidate | None:
        """Return one copied reference; similarity is undefined and returned as 0."""
        with self._lock:
            self._ensure_open()
            snapshot = self._snapshot
        if snapshot is None:
            return None
        row = snapshot.id_to_row.get(reference_id)
        return _candidate(snapshot, row) if row is not None else None

    def close(self):
        with self._lock:
            self._closed = True
