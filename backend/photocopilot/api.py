"""Local image recommendation API with a loaded, immutable index snapshot."""

from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel

from .config import Settings, load_settings
from .encoder import SiglipEncoder
from .service import RecommendationService
from .store import NumpyStore


class Result(BaseModel):
    id: str
    image_path: str
    cosine_similarity: float
    relevance_score: float
    coverage_gain: float
    repetition_penalty: float
    ranking_score: float
    tags: dict[str, str | None]
    reference_reasons: list[str]


class Recommendations(BaseModel):
    demo: bool
    candidate_count: int
    returned: int
    results: list[Result]


class LimitedUploadBody:
    """Bound multipart bytes before the parser can spool a large upload to disk."""

    def __init__(self, app, max_bytes: int):
        self.app, self.max_bytes = app, max_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] != "POST" or scope["path"] != "/recommend":
            await self.app(scope, receive, send)
            return
        chunks, size = [], 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            size += len(chunk)
            if size > self.max_bytes:
                response = JSONResponse({"detail": "Request body exceeds the upload limit"}, 413)
                await response(scope, receive, send)
                return
            chunks.append(chunk)
            if not message.get("more_body", False):
                break
        data = b"".join(chunks)

        async def buffered_receive():
            nonlocal data
            if data is not None:
                message = {"type": "http.request", "body": data, "more_body": False}
                data = None
                return message
            return await receive()

        await self.app(scope, buffered_receive, send)


def create_app(
    settings: Settings | None = None,
    *,
    service: RecommendationService | None = None,
) -> FastAPI:
    settings = settings or load_settings()

    @asynccontextmanager
    async def lifespan(app):
        app.state.service = service
        store = None
        try:
            if service is None:
                store = NumpyStore(settings.index_path)
                if store.metadata() is not None:
                    encoder = SiglipEncoder(
                        settings.model_id,
                        settings.model_revision,
                        settings.model_cache,
                        settings.device,
                    )
                    app.state.service = RecommendationService(settings, encoder, store)
            yield
        finally:
            if app.state.service is not None:
                app.state.service.close()
            elif store is not None:
                store.close()

    app = FastAPI(
        title="Photo Copilot Reference Recommendations", version="0.1.0", lifespan=lifespan
    )
    # Allow 64 KiB of multipart envelope in addition to the accepted file size.
    # Counting receive() bytes also covers uploads without Content-Length.
    app.add_middleware(LimitedUploadBody, max_bytes=settings.max_upload_bytes + 65_536)

    def ready() -> RecommendationService:
        current = getattr(app.state, "service", None)
        if current is None:
            raise HTTPException(503, "No index found. Build the index and restart the API")
        return current

    @app.get("/health")
    def health():
        current = ready()
        return {
            "status": "ok",
            "demo": current.metadata.get("demo", False),
            "indexed": current.metadata["count"],
        }

    @app.post("/recommend", response_model=Recommendations)
    async def recommend(
        image: Annotated[UploadFile, File(description="JPEG, PNG or WebP camera snapshot")],
        limit: Annotated[int | None, Form(ge=1, le=30)] = None,
    ):
        current = ready()
        try:
            data = await image.read(settings.max_upload_bytes + 1)
        finally:
            await image.close()
        if len(data) > settings.max_upload_bytes:
            raise HTTPException(413, "Image exceeds the configured byte limit")
        try:
            result = await run_in_threadpool(
                current.recommend,
                data,
                settings.result_limit if limit is None else limit,
            )
        except ValueError as error:
            raise HTTPException(422, str(error)) from error
        return JSONResponse(result, headers={"Cache-Control": "no-store"})

    @app.get("/images/{reference_id}", response_class=FileResponse)
    def image(reference_id: str):
        reference = ready().store.get(reference_id)
        if reference is None:
            raise HTTPException(404, "Reference not found")
        if not Path(reference.image_path).is_file():
            raise HTTPException(410, "Reference image is no longer available; rebuild the index")
        return FileResponse(reference.image_path)

    return app


app = create_app()
