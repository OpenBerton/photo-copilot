"""Offline snapshot indexing: python -m photocopilot.index --allow-demo."""

import argparse
import hashlib
import json
from pathlib import Path

from .catalog import load_catalog
from .config import Settings, load_settings
from .encoder import SiglipEncoder, fingerprints, load_image
from .store import NumpyStore


def build_index(
    settings: Settings,
    *,
    allow_demo: bool = False,
    batch_size: int = 8,
    encoder: SiglipEncoder | None = None,
) -> dict:
    if not 1 <= batch_size <= 64:
        raise ValueError("batch_size must be in [1,64]")
    catalog, records = load_catalog(settings.catalog_path, allow_demo=allow_demo)
    # Publish a new on-disk snapshot; an existing API keeps its loaded snapshot
    # until restarted, so an offline rebuild does not disrupt active searches.
    with NumpyStore(settings.index_path) as store:
        encoder = encoder or SiglipEncoder(
            settings.model_id,
            settings.model_revision,
            settings.model_cache,
            settings.device,
        )
        vectors = []
        for start in range(0, len(records), batch_size):
            images = []
            try:
                for record in records[start : start + batch_size]:
                    path = Path(record["image_path"])
                    if path.stat().st_size > settings.max_upload_bytes:
                        raise ValueError(f"Image exceeds byte limit: {record['id']}")
                    image = load_image(path.read_bytes(), max_pixels=settings.max_pixels)
                    images.append(image)
                    record.update(fingerprints(image))
                vectors.extend(encoder.encode(images))
            finally:
                for image in images:
                    image.close()
        metadata = {
            "encoder_signature": encoder.signature,
            "demo": catalog.demo,
            "catalog_path": str(settings.catalog_path),
            "catalog_sha256": hashlib.sha256(settings.catalog_path.read_bytes()).hexdigest(),
        }
        count = store.replace(records, vectors, metadata)
    return {"indexed": count, "demo": catalog.demo, "dimension": encoder.dimension}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", help="TOML configuration path")
    parser.add_argument("--allow-demo", action="store_true", help="Explicitly allow demo images")
    parser.add_argument("--batch-size", type=int, default=8)
    args = parser.parse_args()
    try:
        result = build_index(
            load_settings(args.config), allow_demo=args.allow_demo, batch_size=args.batch_size
        )
    except (OSError, ValueError, RuntimeError) as error:
        parser.exit(2, f"Index not replaced: {error}\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
