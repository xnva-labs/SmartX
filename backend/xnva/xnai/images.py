"""Image sanitizing: verify format, drop metadata (EXIF/GPS), bound the size."""
from __future__ import annotations

import io
from typing import Tuple

from PIL import Image, ImageOps, UnidentifiedImageError

from ..errors import ApiError, bad_request

Image.MAX_IMAGE_PIXELS = 50_000_000
MAX_RAW_BYTES = 6 * 1024 * 1024
MAX_SIDE = 1568


def sanitize(raw: bytes) -> Tuple[bytes, int, int]:
    if not raw or len(raw) > MAX_RAW_BYTES:
        raise ApiError(413, "image_too_large", "Gambar kosong atau melebihi 6 MB")
    try:
        img = Image.open(io.BytesIO(raw))
        if img.format not in ("JPEG", "PNG", "WEBP"):
            raise bad_request("unsupported_image", "Format gambar harus JPEG, PNG, atau WEBP")
        img = ImageOps.exif_transpose(img)
        img.load()
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as e:
        raise bad_request("invalid_image", "Gambar tidak dapat dibaca") from e
    img = img.convert("RGB")
    img.thumbnail((MAX_SIDE, MAX_SIDE))
    out = io.BytesIO()
    img.save(out, format="JPEG", quality=85, optimize=True)
    return out.getvalue(), img.width, img.height
