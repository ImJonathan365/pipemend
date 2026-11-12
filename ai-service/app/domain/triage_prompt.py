"""The marked block that carries a request to the LLM as data, not instructions (NFR-14)."""

from typing import Literal

from app.domain.contracts import TriageOptions, TriageRequest

CONTEXT_OPEN = "<triage_context>"
CONTEXT_CLOSE = "</triage_context>"


def render_context(request: TriageRequest, default_language: Literal["es", "en"]) -> str:
    options = request.options
    language = (options.explanation_language if options else None) or default_language
    resolved = request.model_copy(update={"options": TriageOptions(explanation_language=language)})
    # "<" only occurs inside JSON strings, so escaping it means no value can close the block.
    payload = resolved.model_dump_json(by_alias=True).replace("<", "\\u003c")
    return f"{CONTEXT_OPEN}\n{payload}\n{CONTEXT_CLOSE}"


def read_context(prompt: str) -> TriageRequest:
    _, opened, rest = prompt.partition(CONTEXT_OPEN)
    payload, closed, _ = rest.partition(CONTEXT_CLOSE)
    if not opened or not closed:
        raise ValueError("the prompt has no triage context block")
    return TriageRequest.model_validate_json(payload)
