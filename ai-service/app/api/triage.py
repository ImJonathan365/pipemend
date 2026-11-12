from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, Header, Request

from app.api.errors import (
    PROBLEM_MEDIA_TYPE,
    REQUEST_ID_HEADER,
    RequestIdMismatchError,
    problem_schema,
)
from app.api.system import SettingsDep
from app.domain.contracts import TriageRequest, TriageResponse
from app.providers.base import LLMProvider, ProviderNotConfiguredError
from app.providers.factory import create_provider
from app.services.triage_service import TriageService

router = APIRouter(tags=["triage"])

# All of docs/06 A.5, also what only Sprint 5 emits (429, 504).
_ERRORS = {
    400: "INVALID_REQUEST",
    422: "VALIDATION_ERROR",
    429: "RATE_LIMITED or LLM_BUDGET_EXHAUSTED",
    500: "INTERNAL_ERROR",
    502: "LLM_INVALID_OUTPUT or LLM_PROVIDER_ERROR",
    503: "PROVIDER_NOT_CONFIGURED",
    504: "LLM_TIMEOUT",
}
_ERROR_RESPONSES: dict[int | str, dict[str, Any]] = {
    status: {
        "description": codes,
        "content": {PROBLEM_MEDIA_TYPE: {"schema": problem_schema()}},
    }
    for status, codes in _ERRORS.items()
}


def get_provider(settings: SettingsDep) -> LLMProvider:
    if not settings.provider_configured:
        raise ProviderNotConfiguredError(f"LLM_PROVIDER={settings.llm_provider.value} has no key")
    return create_provider(settings)


@router.post("/v1/triage", summary="Analyse one invalid record", responses=_ERROR_RESPONSES)
async def triage(
    body: TriageRequest,
    request: Request,
    settings: SettingsDep,
    provider: Annotated[LLMProvider, Depends(get_provider)],
    header_id: Annotated[UUID | None, Header(alias=REQUEST_ID_HEADER)] = None,
) -> TriageResponse:
    """AC-08.1. It only proposes: the policy in pipeline-service decides (ADR-0003)."""
    if header_id is not None and header_id != body.request_id:
        raise RequestIdMismatchError
    service = TriageService(provider, settings, request.app.state.triage_cache)
    return await service.triage(body)
