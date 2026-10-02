from typing import Annotated, Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from app import __version__
from app.config import Settings, get_settings

router = APIRouter(tags=["system"])

SettingsDep = Annotated[Settings, Depends(get_settings)]


class ContractModel(BaseModel):
    """Base for anything on the wire: docs/06 fixes camelCase for every JSON field."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


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
    """Always HTTP 200 while the process is alive.

    The compose healthcheck only proves the service answers (docs/06 A.3). A real provider
    without an API key is reported as `degraded` instead of failing (AC-17.3).
    """
    configured = settings.provider_configured
    return HealthResponse(
        status="ok" if configured else "degraded",
        provider=settings.llm_provider.value,
        provider_configured=configured,
    )


@router.get("/v1/info", summary="Active provider, model and prompt versions")
def info(settings: SettingsDep) -> InfoResponse:
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
        # `entries` and `llmCallsToday` are real counters from Sprint 3 (LRU cache, NFR-11) and
        # Sprint 5 (daily cap, AC-08.8). Nothing counts yet, so zero is the truthful answer, not
        # a placeholder for a feature pretending to exist.
        cache=CacheInfo(entries=0, max_entries=settings.triage_cache_max_entries),
        limits=LimitsInfo(
            request_deadline_seconds=settings.ai_request_deadline_seconds,
            max_llm_calls_per_day=settings.llm_max_calls_per_day,
            llm_calls_today=0,
        ),
    )
