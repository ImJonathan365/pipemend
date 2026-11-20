"""The seam between the triage service and any LLM (ADR-0004, docs/04 section 9)."""

from dataclasses import dataclass
from typing import Any, Protocol, runtime_checkable


@dataclass(frozen=True)
class Usage:
    input_tokens: int
    output_tokens: int


@dataclass(frozen=True)
class ProviderResult:
    content: dict[str, Any]
    usage: Usage | None


class ProviderNotConfiguredError(Exception):
    """A provider that cannot run: no API key (AC-17.3) or not implemented yet (Sprint 5)."""


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
    """Any other 4xx, such as an unknown model: a retry would get the same answer (D244)."""


class ProviderInvalidOutput(Exception):  # noqa: N818
    """The answer is not one JSON object (D245). It goes to the repair like any invalid output."""

    def __init__(self, errors: list[dict[str, Any]], usage: Usage | None) -> None:
        super().__init__("the answer is not one JSON object")
        self.errors = errors
        self.usage = usage


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
