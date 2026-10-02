import logging
import time
import uuid
from collections.abc import Awaitable, Callable

import structlog
from fastapi import FastAPI, Request, Response
from structlog.typing import EventDict, WrappedLogger

from app import __version__
from app.api import system

REQUEST_ID_HEADER = "X-Request-Id"
BATCH_ID_HEADER = "X-Batch-Id"

_log = structlog.get_logger(__name__)


def _add_service_name(
    _logger: WrappedLogger, _method_name: str, event_dict: EventDict
) -> EventDict:
    """NFR-08 requires a `service` field so both services' logs are distinguishable."""
    event_dict["service"] = "ai-service"
    return event_dict


def configure_logging() -> None:
    """NFR-08: structured JSON logs on stdout.

    `requestId` and `batchId` are bound per request as contextvars, so every log line emitted
    while handling a request carries them without being passed around explicitly.
    """
    logging.basicConfig(format="%(message)s", level=logging.INFO)
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            _add_service_name,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )


def create_app() -> FastAPI:
    configure_logging()

    app = FastAPI(
        title="PipeMend ai-service",
        version=__version__,
        description=(
            "Explains, classifies and proposes corrections for invalid records. It only "
            "proposes: the deterministic policy in pipeline-service decides (ADR-0003)."
        ),
        # AC-18.2: Swagger UI at /docs and the spec at /openapi.json (FastAPI defaults).
    )

    @app.middleware("http")
    async def bind_request_context(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        """NFR-08: correlate every log line with the pipeline's request and batch."""
        request_id = request.headers.get(REQUEST_ID_HEADER) or str(uuid.uuid4())
        structlog.contextvars.clear_contextvars()
        structlog.contextvars.bind_contextvars(
            requestId=request_id,
            batchId=request.headers.get(BATCH_ID_HEADER),
        )

        started = time.perf_counter()
        response = await call_next(request)
        latency_ms = round((time.perf_counter() - started) * 1000)

        # The request id is echoed so the pipeline can correlate even when it did not send one.
        response.headers[REQUEST_ID_HEADER] = request_id
        _log.info(
            "http_request",
            method=request.method,
            path=request.url.path,
            status=response.status_code,
            latencyMs=latency_ms,
        )
        return response

    app.include_router(system.router)
    return app


app = create_app()
