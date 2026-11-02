"""AC-17.1. The real providers do not exist until Sprint 5, so selecting one fails loudly."""

import pytest

from app.config import LlmProvider, Settings
from app.providers.factory import create_provider
from app.providers.mock import MockProvider


def test_the_default_provider_is_the_mock() -> None:
    assert isinstance(create_provider(Settings()), MockProvider)


@pytest.mark.parametrize("provider", [LlmProvider.OPENAI_COMPATIBLE, LlmProvider.ANTHROPIC])
def test_a_real_provider_is_refused_by_name(provider: LlmProvider) -> None:
    settings = Settings(llm_provider=provider, llm_api_key="not-a-real-key")

    with pytest.raises(ValueError, match=provider.value):
        create_provider(settings)
