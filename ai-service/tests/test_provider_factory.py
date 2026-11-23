"""AC-17.1: LLM_PROVIDER alone picks the adapter."""

import pytest
from pydantic import ValidationError

from app.config import LlmProvider, Settings, get_settings
from app.main import create_app
from app.providers.anthropic import AnthropicProvider
from app.providers.factory import create_provider
from app.providers.mock import MockProvider
from app.providers.openai_compatible import OpenAICompatibleProvider


def test_the_default_provider_is_the_mock() -> None:
    assert isinstance(create_provider(Settings()), MockProvider)


@pytest.mark.parametrize(
    ("provider", "adapter"),
    [
        (LlmProvider.MOCK, MockProvider),
        (LlmProvider.OPENAI_COMPATIBLE, OpenAICompatibleProvider),
        (LlmProvider.ANTHROPIC, AnthropicProvider),
    ],
)
def test_each_provider_builds_its_adapter(provider: LlmProvider, adapter: type) -> None:
    settings = Settings(llm_provider=provider, llm_api_key="not-a-real-key")

    assert isinstance(create_provider(settings), adapter)


@pytest.fixture
def environment(monkeypatch: pytest.MonkeyPatch) -> pytest.MonkeyPatch:
    for name in ("LLM_PROVIDER", "LLM_API_KEY", "LLM_BASE_URL", "LLM_MODEL", "LLM_TEMPERATURE"):
        monkeypatch.delenv(name, raising=False)
    return monkeypatch


def test_the_environment_alone_picks_openai_compatible(environment: pytest.MonkeyPatch) -> None:
    """docs/07 l.130: switching provider is a change of .env, not of code."""
    environment.setenv("LLM_PROVIDER", "openai_compatible")
    environment.setenv("LLM_API_KEY", "ollama")
    environment.setenv("LLM_BASE_URL", "http://127.0.0.1:11434/v1")

    assert isinstance(create_provider(Settings()), OpenAICompatibleProvider)


def test_the_environment_alone_picks_anthropic(environment: pytest.MonkeyPatch) -> None:
    environment.setenv("LLM_PROVIDER", "anthropic")
    environment.setenv("LLM_API_KEY", "not-a-real-key")

    assert isinstance(create_provider(Settings()), AnthropicProvider)


def test_anthropic_with_a_temperature_does_not_start(environment: pytest.MonkeyPatch) -> None:
    """D250: anthropic never sends it (docs/03 NFR-13)."""
    environment.setenv("LLM_PROVIDER", "anthropic")
    environment.setenv("LLM_TEMPERATURE", "0.5")
    get_settings.cache_clear()

    try:
        with pytest.raises(ValidationError, match="LLM_TEMPERATURE"):
            create_app()
    finally:
        get_settings.cache_clear()


@pytest.mark.parametrize(
    ("provider", "temperature"),
    [(LlmProvider.ANTHROPIC, 0.0), (LlmProvider.OPENAI_COMPATIBLE, 0.5), (LlmProvider.MOCK, 0.5)],
)
def test_only_anthropic_refuses_a_temperature(provider: LlmProvider, temperature: float) -> None:
    assert (
        Settings(llm_provider=provider, llm_temperature=temperature).llm_temperature == temperature
    )


def test_an_empty_environment_is_the_mock(environment: pytest.MonkeyPatch) -> None:
    assert isinstance(create_provider(Settings()), MockProvider)


def test_an_unknown_provider_does_not_start(environment: pytest.MonkeyPatch) -> None:
    environment.setenv("LLM_PROVIDER", "gemini")

    with pytest.raises(ValidationError):
        Settings()
