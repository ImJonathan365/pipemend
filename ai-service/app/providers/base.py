"""The seam between the triage service and any LLM (ADR-0004, docs/04 section 9)."""

from dataclasses import dataclass
from typing import Any, Protocol


@dataclass(frozen=True)
class Usage:
    input_tokens: int
    output_tokens: int


@dataclass(frozen=True)
class ProviderResult:
    content: dict[str, Any]
    usage: Usage | None


class LLMProvider(Protocol):
    # async because the triage endpoint must never block the event loop (NFR-10, docs/09 7.6). The
    # caller bounds the call with asyncio.timeout, so the timeout is not part of this signature.
    async def complete_json(
        self, system_prompt: str, user_prompt: str, json_schema: dict[str, Any]
    ) -> ProviderResult: ...
