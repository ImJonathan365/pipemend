"""OpenAI and any server speaking its chat completions API: Ollama, Groq, LM Studio (AC-17.4)."""

import json
from typing import Any

import httpx2
import openai
from openai import AsyncOpenAI
from openai.types import CompletionUsage

from app.config import Settings
from app.providers.base import (
    ProviderAuthError,
    ProviderBadRequest,
    ProviderInvalidOutput,
    ProviderRateLimited,
    ProviderResult,
    ProviderTimeout,
    ProviderUnavailable,
    Usage,
)

_NOT_AN_OBJECT = {
    "loc": [],
    "type": "json_invalid",
    "msg": "the answer must be one JSON object and nothing else",
}
_TRUNCATED = {
    "loc": [],
    "type": "truncated",
    "msg": "the answer was cut at the output token limit; answer with a shorter JSON object",
}


class OpenAICompatibleProvider:
    def __init__(self, settings: Settings, http_client: httpx2.AsyncClient | None = None) -> None:
        self._client = AsyncOpenAI(
            api_key=settings.llm_api_key,
            # Empty means the official endpoint (docs/04 section 9).
            base_url=settings.llm_base_url or None,
            # D243: the triage service owns the retries (docs/03 NFR-07); its asyncio.timeout is
            # the real bound, this one only a backstop.
            max_retries=0,
            timeout=settings.llm_timeout_seconds,
            http_client=http_client,
        )
        self._model = settings.llm_model
        # docs/04 section 9: fixed here, never part of the call (NFR-13, S5-9).
        self._temperature = settings.llm_temperature
        self._max_tokens = settings.llm_max_output_tokens

    async def complete_json(
        self, system_prompt: str, user_prompt: str, json_schema: dict[str, Any]
    ) -> ProviderResult:
        # S5-7: json_object works on most compatible servers; the schema is in the system prompt.
        try:
            completion = await self._client.chat.completions.create(
                model=self._model,
                temperature=self._temperature,
                max_tokens=self._max_tokens,
                response_format={"type": "json_object"},
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
            )
        # A timeout is a connection error to the SDK, so it goes first. No message quotes the body.
        except openai.APITimeoutError as error:
            raise ProviderTimeout("the provider did not answer in time") from error
        except openai.APIConnectionError as error:
            raise ProviderUnavailable("the provider could not be reached") from error
        except openai.RateLimitError as error:
            raise ProviderRateLimited(_retry_after(error.response)) from error
        except (openai.AuthenticationError, openai.PermissionDeniedError) as error:
            raise ProviderAuthError("the provider refused the credentials") from error
        except openai.APIStatusError as error:
            if error.status_code >= 500:
                raise ProviderUnavailable("the provider failed") from error
            raise ProviderBadRequest(f"the provider answered {error.status_code}") from error

        usage = _usage(completion.usage)
        choice = completion.choices[0] if completion.choices else None
        if choice is not None and choice.finish_reason == "length":
            raise ProviderInvalidOutput([_TRUNCATED], usage)
        content = choice.message.content if choice is not None else None
        try:
            parsed = json.loads(content) if content else None
        except json.JSONDecodeError:
            parsed = None
        if not isinstance(parsed, dict):
            raise ProviderInvalidOutput([_NOT_AN_OBJECT], usage)
        return ProviderResult(parsed, usage)

    async def aclose(self) -> None:
        await self._client.close()


def _usage(usage: CompletionUsage | None) -> Usage | None:
    if usage is None:
        return None
    return Usage(usage.prompt_tokens, usage.completion_tokens)


def _retry_after(response: httpx2.Response) -> float | None:
    """D248: delta-seconds only; an HTTP date gives None."""
    try:
        seconds = float(response.headers.get("retry-after", ""))
    except ValueError:
        return None
    return seconds if seconds >= 0 else None
