"""Start the shared backend using the model, photos and index shipped in Git."""

import argparse
import json
import logging
from pathlib import Path

from .catalog import load_catalog
from .config import Settings, load_settings
from .encoder import SiglipEncoder
from .index import build_index
from .service import RecommendationService
from .store import NumpyStore

logger = logging.getLogger(__name__)
_LFS_POINTER = b"version https://git-lfs.github.com/spec/v1"


def _require_local_model(settings: Settings) -> None:
    snapshot = (
        settings.model_cache
        / ("models--" + settings.model_id.replace("/", "--"))
        / "snapshots"
        / settings.model_revision
    )
    for name in ("config.json", "preprocessor_config.json", "model.safetensors"):
        path = snapshot / name
        if not path.is_file():
            raise ValueError(
                f"Local model file is missing: {path}. "
                "Install Git LFS, then run `git lfs install` and `git lfs pull` "
                "inside the cloned repository. No model will be downloaded automatically."
            )
        with path.open("rb") as stream:
            prefix = stream.read(256)
        if not prefix or prefix.lstrip().startswith(_LFS_POINTER):
            raise ValueError(
                f"Local model file is empty or is a Git LFS pointer: {path}. "
                "Run `git lfs install` and `git lfs pull` inside the cloned repository."
            )


def prepare_service(settings: Settings) -> RecommendationService:
    """Keep valid indexes; rebuild only missing or same-model incompatible ones.

    Different platform library versions may change the encoder signature, so a
    fresh clone rebuilds from the included photos when needed. Corrupt indexes
    and indexes from a different checkpoint fail without being overwritten.
    """
    _require_local_model(settings)
    # Validate the included catalog and photos before a potentially expensive load.
    load_catalog(settings.catalog_path)
    store = NumpyStore(settings.index_path)
    try:
        metadata = store.metadata()
        if metadata is not None:
            signature = metadata["encoder_signature"]
            if (
                signature.get("model_id") != settings.model_id
                or signature.get("revision") != settings.model_revision
            ):
                raise ValueError(
                    "The index belongs to a different model or revision. "
                    "Use the matching configuration or explicitly build a new index."
                )
        encoder = SiglipEncoder(
            settings.model_id,
            settings.model_revision,
            settings.model_cache,
            settings.device,
            local_files_only=True,
        )
        if metadata is None or metadata["encoder_signature"] != encoder.signature:
            logger.warning(
                "Building a local index from the included photos: %s",
                "index missing" if metadata is None else "encoder signature changed",
            )
            store.close()
            build_index(settings, encoder=encoder)
            store = NumpyStore(settings.index_path)
        return RecommendationService(settings, encoder, store)
    except BaseException:
        store.close()
        raise


def _check(service: RecommendationService) -> dict:
    _, records = load_catalog(service.settings.catalog_path)
    # Image serving must work as well as vector search after moving the clone.
    for record in records:
        reference = service.store.get(record["id"])
        if reference is None or not Path(reference.image_path).is_file():
            raise ValueError(
                f"Indexed image is unavailable: {record['id']}. "
                "Rebuild the index using the included catalog."
            )
    result = service.recommend(
        Path(records[0]["image_path"]).read_bytes(), service.settings.result_limit
    )
    return {
        "status": "ok",
        "indexed": service.metadata["count"],
        "demo": service.metadata.get("demo", False),
        "candidate_count": result["candidate_count"],
        "returned": result["returned"],
    }


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", help="TOML configuration path")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--check", action="store_true", help="Check one recommendation and exit")
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65_535:
        parser.error("port must be in [1,65535]")
    service = None
    try:
        settings = load_settings(args.config)
        service = prepare_service(settings)
        if args.check:
            print(json.dumps(_check(service), ensure_ascii=False))
        else:
            import uvicorn

            from .api import create_app

            uvicorn.run(create_app(settings, service=service), host=args.host, port=args.port)
    except (OSError, ValueError, RuntimeError) as error:
        parser.exit(2, f"Backend not started: {error}\n")
    finally:
        if service is not None:
            service.close()


if __name__ == "__main__":
    main()
