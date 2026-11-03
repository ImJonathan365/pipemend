from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Request

from app import __version__
from app.config import Settings, get_settings
from app.domain.contracts import ContractModel

router = APIRouter(tags=["system"])

SettingsDep = Annotated[Settings, Depends(get_settings)]


class HealthResponse(ContractModel):
    status: Literal["ok", "degraded"]
    provider: str
    provider_configured: bool


class PromptVersions(ContractModel):
    triage: str
    schema_drift: str


class CacheInfo(ContractModel):
    entries: int
    max_entries: int


class LimitsInfo(ContractModel):
    request_deadline_seconds: int
    max_llm_calls_per_day: int
    llm_calls_today: int


class InfoResponse(ContractModel):
    service: str
    version: str
    provider: str
    model: str
    prompt_versions: PromptVersions
    cache: CacheInfo
    limits: LimitsInfo


@router.get("/health", summary="Liveness and provider status")
def health(settings: SettingsDep) -> HealthResponse:
    configured = settings.provider_configured
    return HealthResponse(
        status="ok" if configured else "degraded",
        provider=settings.llm_provider.value,
        provider_configured=configured,
    )


@router.get("/v1/info", summary="Active provider, model and prompt versions")
def info(settings: SettingsDep, request: Request) -> InfoResponse:
    """AC-17.5. Never exposes the API key, only whether a provider is configured."""
    return InfoResponse(
        service="pipemend-ai-service",
        version=__version__,
        provider=settings.llm_provider.value,
        model=settings.llm_model,
        prompt_versions=PromptVersions(
            triage=settings.prompt_version_triage,
            schema_drift=settings.prompt_version_schema,
        ),
        cache=CacheInfo(
            entries=len(request.app.state.triage_cache),
            max_entries=settings.triage_cache_max_entries,
        ),
        # A real counter from Sprint 5 (daily cap, AC-08.8). Nothing counts yet, so zero is the
        # truthful answer, not a placeholder for a feature pretending to exist.
        limits=LimitsInfo(
            request_deadline_seconds=settings.ai_request_deadline_seconds,
            max_llm_calls_per_day=settings.llm_max_calls_per_day,
            llm_calls_today=0,
        ),
    )
