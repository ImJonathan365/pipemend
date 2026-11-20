"""AC-17.1: LLM_PROVIDER alone picks the adapter. anthropic still fails loudly until it exists."""

import pytest
from pydantic import ValidationError

from app.config import LlmProvider, Settings
from app.providers.base import ProviderNotConfiguredError
from app.providers.factory import create_provider
from app.providers.mock import MockProvider
from app.providers.openai_compatible import OpenAICompatibleProvider


def test_the_default_provider_is_the_mock() -> None:
    assert isinstance(create_provider(Settings()), MockProvider)


@pytest.mark.parametrize("provider", [LlmProvider.ANTHROPIC])
def test_a_real_provider_is_refused_by_name(provider: LlmProvider) -> None:
    settings = Settings(llm_provider=provider, llm_api_key="not-a-real-key")

    with pytest.raises(ProviderNotConfiguredError, match=provider.value):
        create_provider(settings)


@pytest.fixture
def environment(monkeypatch: pytest.MonkeyPatch) -> pytest.MonkeyPatch:
    for name in ("LLM_PROVIDER", "LLM_API_KEY", "LLM_BASE_URL", "LLM_MODEL"):
        monkeypatch.delenv(name, raising=False)
    return monkeypatch


def test_the_environment_alone_picks_openai_compatible(environment: pytest.MonkeyPatch) -> None:
    """docs/07 l.130: switching provider is a change of .env, not of code."""
    environment.setenv("LLM_PROVIDER", "openai_compatible")
    environment.setenv("LLM_API_KEY", "ollama")
    environment.setenv("LLM_BASE_URL", "http://127.0.0.1:11434/v1")

    assert isinstance(create_provider(Settings()), OpenAICompatibleProvider)


def test_an_empty_environment_is_the_mock(environment: pytest.MonkeyPatch) -> None:
    assert isinstance(create_provider(Settings()), MockProvider)


def test_an_unknown_provider_does_not_start(environment: pytest.MonkeyPatch) -> None:
    environment.setenv("LLM_PROVIDER", "gemini")

    with pytest.raises(ValidationError):
        Settings()
