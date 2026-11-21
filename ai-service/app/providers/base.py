"""The seam between the triage service and any LLM (ADR-0004, docs/04 section 9)."""

from dataclasses import dataclass
from typing import Any, Protocol, runtime_checkable

import httpx2


@dataclass(frozen=True)
class Usage:
    input_tokens: int
    output_tokens: int


@dataclass(frozen=True)
class ProviderResult:
    content: dict[str, Any]
    usage: Usage | None
    # D253: why the LLM stopped, as its API names it, for the llm_call log. None for the mock.
    stop_reason: str | None = None


class ProviderNotConfiguredError(Exception):
    """A real provider with no API key (AC-17.3)."""


# What each real provider translates its SDK errors into (docs/04 section 9). The triage service
# owns the retries (docs/03 NFR-07), so a provider never retries on its own.
class ProviderTimeout(Exception):  # noqa: N818 - the names of docs/04 section 9
    pass


class ProviderUnavailable(Exception):  # noqa: N818
    """5xx or a network error."""


class ProviderRateLimited(Exception):  # noqa: N818
    def __init__(self, retry_after: float | None = None) -> None:
        super().__init__("rate limited")
        self.retry_after = retry_after


class ProviderAuthError(Exception):
    pass


class ProviderBadRequest(Exception):  # noqa: N818
    """Any other 4xx, such as an unknown model, or a refusal: a retry would get the same answer
    (D244). The status and the stop reason tell them apart in the llm_call log (D253)."""

    def __init__(self, status: int | None = None, stop_reason: str | None = None) -> None:
        super().__init__("the provider refused the request")
        self.status = status
        self.stop_reason = stop_reason


# The errors of an answer that is not one JSON object, the same whatever the provider (D255).
NOT_AN_OBJECT = {
    "loc": [],
    "type": "json_invalid",
    "msg": "the answer must be one JSON object and nothing else",
}
TRUNCATED = {
    "loc": [],
    "type": "truncated",
    "msg": "the answer was cut at the output token limit; answer with a shorter JSON object",
}


class ProviderInvalidOutput(Exception):  # noqa: N818
    """The answer is not one JSON object (D245). It goes to the repair like any invalid output."""

    def __init__(
        self, errors: list[dict[str, Any]], usage: Usage | None, stop_reason: str | None = None
    ) -> None:
        super().__init__("the answer is not one JSON object")
        self.errors = errors
        self.usage = usage
        self.stop_reason = stop_reason


def retry_after(response: httpx2.Response) -> float | None:
    """D248: delta-seconds only; an HTTP date gives None. Shared by every real provider (D255)."""
    try:
        seconds = float(response.headers.get("retry-after", ""))
    except ValueError:
        return None
    return seconds if seconds >= 0 else None


@runtime_checkable
class ClosableProvider(Protocol):
    """A provider holding connections, closed when the app stops (D246)."""

    async def aclose(self) -> None: ...


class LLMProvider(Protocol):
    # async because the triage endpoint must never block the event loop (NFR-10, docs/09 7.6). The
    # caller bounds the call with asyncio.timeout, so the timeout is not part of this signature.
    async def complete_json(
        self, system_prompt: str, user_prompt: str, json_schema: dict[str, Any]
    ) -> ProviderResult: ...
