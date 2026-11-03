"""Every error as problem+json (docs/06 A.5, NFR-20). No body quotes a value of the row."""

from collections.abc import Iterable, Sequence
from http import HTTPStatus
from typing import Any, cast

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException

from app.domain.contracts import ProblemDetail, ProblemFieldError
from app.providers.base import ProviderNotConfiguredError
from app.services.triage_service import LlmInvalidOutputError

REQUEST_ID_HEADER = "X-Request-Id"
PROBLEM_MEDIA_TYPE = "application/problem+json"

_RAW = ("body", "record", "raw")


class RequestIdMismatchError(Exception):
    """requestId and X-Request-Id are two different UUIDs (docs/06 A.1)."""


def problem_schema() -> dict[str, Any]:
    """ProblemDetail for a response of the spec, with its $defs resolved in place.

    It is inline because `model` in `responses` also declares application/json, which is false, and
    a `#/$defs/...` reference would not resolve from the root of the document.
    """
    schema = ProblemDetail.model_json_schema(by_alias=True)
    definitions = schema.pop("$defs", {})

    def resolve(node: Any) -> Any:
        if isinstance(node, list):
            return [resolve(item) for item in node]
        if not isinstance(node, dict):
            return node
        reference = node.get("$ref")
        if reference:
            return resolve(definitions[reference.rsplit("/", 1)[-1]])
        return {key: resolve(value) for key, value in node.items()}

    return cast(dict[str, Any], resolve(schema))


def _problem(
    request: Request,
    status: int,
    code: str,
    title: str,
    detail: str,
    *,
    errors: list[ProblemFieldError] | None = None,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    body = ProblemDetail(
        type=f"https://pipemend.dev/problems/{code.lower().replace('_', '-')}",
        title=title,
        status=status,
        detail=detail,
        instance=request.url.path,
        code=code,
        request_id=request.state.request_id,
        errors=errors,
    )
    return JSONResponse(
        body.model_dump(mode="json", by_alias=True, exclude_none=True),
        status_code=status,
        media_type=PROBLEM_MEDIA_TYPE,
        headers=headers,
    )


def internal_error(request: Request) -> JSONResponse:
    return _problem(
        request, 500, "INTERNAL_ERROR", "Internal error", "The request could not be completed."
    )


def _field_errors(errors: Iterable[dict[str, Any]]) -> list[ProblemFieldError]:
    """Only loc and type: msg, input and ctx can repeat what was sent."""
    found: dict[tuple[tuple[str | int, ...], str], None] = {}
    for error in errors:
        found[(_public_loc(error["loc"]), error["type"])] = None
    return [ProblemFieldError(loc=list(loc), type=kind) for loc, kind in found]


def _public_loc(loc: Sequence[str | int]) -> tuple[str | int, ...]:
    # The keys of record.raw are headers of the file, so they are data like its values.
    return _RAW if tuple(loc[: len(_RAW)]) == _RAW else tuple(loc)


def register_problem_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestIdMismatchError)
    async def _request_id_mismatch(request: Request, _: RequestIdMismatchError) -> JSONResponse:
        return _problem(
            request,
            400,
            "INVALID_REQUEST",
            "Invalid request",
            "requestId and the X-Request-Id header are different.",
        )

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, error: RequestValidationError) -> JSONResponse:
        return _problem(
            request,
            422,
            "VALIDATION_ERROR",
            "Validation error",
            "The request does not match the contract.",
            errors=_field_errors(error.errors()),
        )

    @app.exception_handler(LlmInvalidOutputError)
    async def _invalid_output(request: Request, _: LlmInvalidOutputError) -> JSONResponse:
        return _problem(
            request,
            502,
            "LLM_INVALID_OUTPUT",
            "Invalid LLM output",
            "The LLM answered something outside the contract.",
        )

    @app.exception_handler(ProviderNotConfiguredError)
    async def _not_configured(request: Request, _: ProviderNotConfiguredError) -> JSONResponse:
        return _problem(
            request,
            503,
            "PROVIDER_NOT_CONFIGURED",
            "Provider not configured",
            "The configured LLM provider cannot run.",
        )

    @app.exception_handler(HTTPException)
    async def _http(request: Request, error: HTTPException) -> JSONResponse:
        """404 and 405. The status names the code, as in docs/06 part B; 405 needs its Allow."""
        status = HTTPStatus(error.status_code)
        return _problem(
            request,
            status.value,
            status.name,
            status.phrase,
            status.description or status.phrase,
            headers=dict(error.headers) if error.headers else None,
        )
