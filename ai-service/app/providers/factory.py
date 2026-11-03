from app.config import LlmProvider, Settings
from app.providers.base import LLMProvider, ProviderNotConfiguredError
from app.providers.mock import MockProvider


def create_provider(settings: Settings) -> LLMProvider:
    """AC-17.1. The real providers arrive in Sprint 5 (docs/07)."""
    if settings.llm_provider is LlmProvider.MOCK:
        return MockProvider()
    raise ProviderNotConfiguredError(
        f"LLM_PROVIDER={settings.llm_provider.value} is not implemented"
    )
