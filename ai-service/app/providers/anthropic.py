"""Anthropic's Messages API (FR-17, docs/04 section 9)."""

import json
from typing import Any

import anthropic
import httpx2
from anthropic import AsyncAnthropic
from anthropic.types import Message
from anthropic.types import Usage as MessageUsage

from app.config import Settings
from app.providers.base import (
    NOT_AN_OBJECT,
    TRUNCATED,
    ProviderAuthError,
    ProviderBadRequest,
    ProviderInvalidOutput,
    ProviderRateLimited,
    ProviderResult,
    ProviderTimeout,
    ProviderUnavailable,
    Usage,
    retry_after,
)


class AnthropicProvider:
    def __init__(self, settings: Settings, http_client: httpx2.AsyncClient | None = None) -> None:
        # D254: LLM_BASE_URL is for openai_compatible only. D243: the service owns the retries.
        self._client = AsyncAnthropic(
            api_key=settings.llm_api_key,
            max_retries=0,
            timeout=settings.llm_timeout_seconds,
            http_client=http_client,
        )
        self._model = settings.llm_model
        self._max_tokens = settings.llm_max_output_tokens

    async def complete_json(
        self, system_prompt: str, user_prompt: str, json_schema: dict[str, Any]
    ) -> ProviderResult:
        # D250: no temperature, the API version of the SDK rejects it (docs/03 NFR-13). D253: no
        # thinking or effort either, as they would depend on the model. D251: no tools, no prefill.
        try:
            message = await self._client.messages.create(
                model=self._model,
                max_tokens=self._max_tokens,
                system=system_prompt,
                messages=[{"role": "user", "content": user_prompt}],
                output_config={"format": {"type": "json_schema", "schema": json_schema}},
            )
        # A timeout is a connection error to the SDK, so it goes first. No message quotes the body.
        except anthropic.APITimeoutError as error:
            raise ProviderTimeout("the provider did not answer in time") from error
        except anthropic.APIConnectionError as error:
            raise ProviderUnavailable("the provider could not be reached") from error
        except anthropic.RateLimitError as error:
            raise ProviderRateLimited(retry_after(error.response)) from error
        except (anthropic.AuthenticationError, anthropic.PermissionDeniedError) as error:
            raise ProviderAuthError("the provider refused the credentials") from error
        except anthropic.APIStatusError as error:
            if error.status_code >= 500:
                raise ProviderUnavailable("the provider failed") from error
            raise ProviderBadRequest(error.status_code) from error

        usage = _usage(message)
        stop_reason = message.stop_reason
        if stop_reason == "max_tokens":
            raise ProviderInvalidOutput([TRUNCATED], usage, stop_reason)
        if stop_reason == "refusal":
            # D252: asking again gets the same refusal, so it is not repaired.
            raise ProviderBadRequest(stop_reason=stop_reason)
        parsed = _parse(_text(message))
        if not isinstance(parsed, dict):
            raise ProviderInvalidOutput([NOT_AN_OBJECT], usage, stop_reason)
        return ProviderResult(parsed, usage, stop_reason)

    async def aclose(self) -> None:
        await self._client.close()


def _usage(message: Message) -> Usage | None:
    # Typed as always there, yet the SDK leaves None when the body lacks it, as openai's can.
    usage: MessageUsage | None = message.usage
    if usage is None:
        return None
    return Usage(usage.input_tokens, usage.output_tokens)


def _text(message: Message) -> str | None:
    """The first text block: a model that thinks by default puts its thinking blocks first."""
    return next((block.text for block in message.content if block.type == "text"), None)


def _parse(text: str | None) -> Any:
    try:
        return json.loads(text) if text else None
    except json.JSONDecodeError:
        return None
