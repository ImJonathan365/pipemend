from enum import StrEnum
from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class LlmProvider(StrEnum):
    """AC-17.1. `mock` is the default so a clean clone runs with no API key and no network."""

    MOCK = "mock"
    OPENAI_COMPATIBLE = "openai_compatible"
    ANTHROPIC = "anthropic"


class Settings(BaseSettings):
    # No env_file: values come from the environment that docker-compose injects per service
    # (NFR-14, least privilege). Unrelated variables in the environment are ignored rather than
    # rejected - `extra="forbid"` belongs to the LLM output boundary, not here (docs/09 7.5).
    model_config = SettingsConfigDict(extra="ignore", frozen=True)

    llm_provider: LlmProvider = LlmProvider.MOCK
    llm_model: str = "mock-rules-v1"
    llm_api_key: str = ""
    llm_base_url: str = ""
    llm_timeout_seconds: int = 20
    llm_max_calls_per_day: int = 2000
    llm_max_output_tokens: int = 1024
    llm_temperature: float = 0.0

    ai_request_deadline_seconds: int = 25
    ai_explanation_language: Literal["es", "en"] = "es"

    prompt_version_triage: str = "triage_v1"
    prompt_version_schema: str = "schema_drift_v1"

    triage_cache_max_entries: int = 5000
    triage_cache_ttl_seconds: int = 86400

    @property
    def provider_configured(self) -> bool:
        """AC-17.3: a real provider with no API key leaves the service degraded.

        The mock needs no credentials (NFR-02). docs/04 section 8 notes the key is unnecessary
        for local endpoints without auth; that exception is deliberately not implemented yet
        because `openai_compatible` itself only arrives in Sprint 5. Until then the strict rule
        is the safe one.
        """
        if self.llm_provider is LlmProvider.MOCK:
            return True
        return bool(self.llm_api_key)


@lru_cache
def get_settings() -> Settings:
    """Cached so the environment is read once per process. Overridden in tests."""
    return Settings()
