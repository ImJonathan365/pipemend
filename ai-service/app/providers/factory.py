from typing import assert_never

from app.config import LlmProvider, Settings
from app.providers.anthropic import AnthropicProvider
from app.providers.base import LLMProvider
from app.providers.mock import MockProvider
from app.providers.openai_compatible import OpenAICompatibleProvider


def create_provider(settings: Settings) -> LLMProvider:
    """AC-17.1: chosen by LLM_PROVIDER alone."""
    match settings.llm_provider:
        case LlmProvider.MOCK:
            return MockProvider()
        case LlmProvider.OPENAI_COMPATIBLE:
            return OpenAICompatibleProvider(settings)
        case LlmProvider.ANTHROPIC:
            return AnthropicProvider(settings)
        case unknown:
            assert_never(unknown)
