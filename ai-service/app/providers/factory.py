from app.config import LlmProvider, Settings
from app.providers.base import LLMProvider, ProviderNotConfiguredError
from app.providers.mock import MockProvider
from app.providers.openai_compatible import OpenAICompatibleProvider


def create_provider(settings: Settings) -> LLMProvider:
    """AC-17.1: chosen by LLM_PROVIDER alone. anthropic arrives later in Sprint 5 (docs/07)."""
    if settings.llm_provider is LlmProvider.MOCK:
        return MockProvider()
    if settings.llm_provider is LlmProvider.OPENAI_COMPATIBLE:
        return OpenAICompatibleProvider(settings)
    raise ProviderNotConfiguredError(
        f"LLM_PROVIDER={settings.llm_provider.value} is not implemented"
    )
