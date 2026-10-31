"""The same bounded image preparation and SigLIP 2 encoder for index and query."""

import re
import struct
import warnings
from collections.abc import Sequence
from copy import deepcopy
from hashlib import sha256
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageOps, ImageStat, UnidentifiedImageError

PREPROCESSING_VERSION = "exif-rgb-v1"
_FORMATS = frozenset({"JPEG", "PNG", "WEBP"})


def load_image(data: bytes, *, max_pixels: int = 20_000_000) -> Image.Image:
    """Decode a static JPEG/PNG/WebP, checking dimensions before allocating pixels.

    EXIF orientation is applied once, then removed by Pillow. Neither file paths nor
    remote URLs are accepted. Callers separately bound the number of uploaded bytes.
    """
    if max_pixels <= 0:
        raise ValueError("max_pixels must be positive")
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(data)) as source:
                if source.width * source.height > max_pixels:
                    raise ValueError("Image exceeds the pixel limit")
                if source.format not in _FORMATS:
                    raise ValueError("Only JPEG, PNG and WebP images are supported")
                if getattr(source, "is_animated", False):
                    raise ValueError("Animated images are not supported")
                source.verify()
            # verify() invalidates the decoder; reopen the same bounded bytes.
            with Image.open(BytesIO(data)) as source:
                return ImageOps.exif_transpose(source).convert("RGB")
    except (
        UnidentifiedImageError,
        OSError,
        SyntaxError,
        Image.DecompressionBombError,
        Image.DecompressionBombWarning,
    ) as error:
        raise ValueError("Invalid or corrupt image") from error


def fingerprints(image: Image.Image) -> dict:
    """Pixel identity plus conservative crop-aware 64-bit difference hashes.

    The hashes are duplicate *evidence*, not semantic similarity. Small central
    crops help identify modest recrops, but do not cover arbitrary crops or edits.
    Uniform/low-detail images have no perceptual hashes because unrelated blank
    images otherwise share the same hash. Pixel identity still detects exact copies.
    """
    rgb = ImageOps.exif_transpose(image).convert("RGB")
    pixel_hash = sha256(struct.pack(">II", *rgb.size) + rgb.tobytes()).hexdigest()
    hashes = []
    for fraction in (1.0, 0.9, 0.8):
        crop_width = max(1, round(rgb.width * fraction))
        crop_height = max(1, round(rgb.height * fraction))
        left = (rgb.width - crop_width) // 2
        top = (rgb.height - crop_height) // 2
        gray = rgb.crop((left, top, left + crop_width, top + crop_height)).convert("L")
        small = gray.resize((9, 8), Image.Resampling.LANCZOS)
        if ImageStat.Stat(small).stddev[0] < 8:
            continue
        pixels = small.tobytes()
        bits = [pixels[y * 9 + x] > pixels[y * 9 + x + 1] for y in range(8) for x in range(8)]
        # Near-empty bit patterns are ambiguous even when luminance varies, e.g.
        # a blank wall with a left-to-right lighting gradient.
        if not 8 <= sum(bits) <= 56:
            continue
        value = 0
        for bit in bits:
            value = (value << 1) | bit
        encoded = f"{value:016x}"
        if encoded not in hashes:
            hashes.append(encoded)
    return {"pixel_hash": pixel_hash, "perceptual_hashes": hashes}


class SiglipEncoder:
    """Pinned SigLIP 2 FixRes vision features, L2-normalized in float32.

    Loading is explicit: importing this module does not import torch/transformers
    or download weights. The vision-only model uses the same pooler_output as
    SiglipModel.get_image_features, without keeping the unused text tower in RAM.
    """

    def __init__(
        self,
        model_id: str,
        revision: str,
        cache_dir: Path,
        device: str = "cpu",
        *,
        local_files_only: bool = False,
    ) -> None:
        if not re.fullmatch(r"[a-fA-F0-9]{40}", revision):
            raise ValueError("Model revision must be an immutable 40-character commit SHA")

        import PIL
        import torch
        import transformers
        from transformers import AutoConfig, AutoImageProcessor, SiglipVisionModel

        config = AutoConfig.from_pretrained(
            model_id,
            revision=revision,
            cache_dir=str(cache_dir),
            trust_remote_code=False,
            local_files_only=local_files_only,
        )
        if config.model_type != "siglip":
            raise ValueError("This encoder requires a SigLIP 2 FixRes (siglip) checkpoint")
        resolved_revision = getattr(config, "_commit_hash", None)
        if resolved_revision != revision.lower():
            raise ValueError("Model revision did not resolve to the pinned commit")
        self._processor = AutoImageProcessor.from_pretrained(
            model_id,
            revision=revision,
            cache_dir=str(cache_dir),
            trust_remote_code=False,
            use_fast=False,
            local_files_only=local_files_only,
        )
        self._model, loading_info = SiglipVisionModel.from_pretrained(
            model_id,
            revision=revision,
            cache_dir=str(cache_dir),
            config=config.vision_config,
            output_loading_info=True,
            trust_remote_code=False,
            local_files_only=local_files_only,
        )
        if loading_info["missing_keys"] or loading_info["mismatched_keys"]:
            raise ValueError("The checkpoint does not contain all required vision weights")
        self._torch = torch
        self._device = torch.device(device)
        self._model = self._model.to(self._device, dtype=torch.float32).eval()
        self.dimension = config.vision_config.hidden_size
        processor_config = self._processor.to_dict()
        processor_config.pop("_name_or_path", None)
        self._signature = {
            "encoder": "siglip2-fixres-vision-pooler",
            "model_id": model_id,
            "revision": resolved_revision,
            "dimension": self.dimension,
            "processor_class": type(self._processor).__name__,
            "processor": processor_config,
            "preprocessing_version": PREPROCESSING_VERSION,
            "normalization": "l2-float32",
            "libraries": {
                "pillow": PIL.__version__,
                "torch": torch.__version__,
                "transformers": transformers.__version__,
            },
        }

    @property
    def signature(self) -> dict:
        """JSON-compatible identity persisted with the index to prevent drift."""
        return deepcopy(self._signature)

    def encode(self, images: Sequence[Image.Image]) -> list[list[float]]:
        if not images:
            return []
        canonical = [ImageOps.exif_transpose(image).convert("RGB") for image in images]
        inputs = self._processor(images=canonical, return_tensors="pt").to(self._device)
        with self._torch.inference_mode():
            features = self._model(**inputs).pooler_output.float()
            norms = features.norm(p=2, dim=-1, keepdim=True)
            if (
                not self._torch.isfinite(features).all()
                or not self._torch.isfinite(norms).all()
                or (norms <= 0).any()
            ):
                raise ValueError("Model produced a non-finite or zero image vector")
            vectors = features / norms
        if vectors.ndim != 2 or vectors.shape != (len(images), self.dimension):
            raise ValueError("Model produced an unexpected image vector shape")
        return vectors.cpu().tolist()
