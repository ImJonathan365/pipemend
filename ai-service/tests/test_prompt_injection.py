"""The prompt-injection fixture of contracts/fixtures through the prompt and the mock (NFR-14).

The pipeline suite (AC-10.6) uses the same fixture with an AI that obeys the instruction.
"""

import asyncio
from pathlib import Path
from typing import Any

from app.domain.contracts import TriageRequest
from app.domain.triage_prompt import CONTEXT_CLOSE, CONTEXT_OPEN, read_context, render_context
from app.providers.mock import MockProvider

FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "contracts"
    / "fixtures"
    / "triage-request.prompt-injection.json"
)
REQUEST = TriageRequest.model_validate_json(FIXTURE.read_text(encoding="utf-8"))
INJECTED = REQUEST.record.raw["Description"]


def _answer(request: TriageRequest) -> dict[str, Any]:
    prompt = render_context(request, "es")
    return asyncio.run(MockProvider().complete_json("system", prompt, {})).content


def test_the_fixture_carries_an_instruction_and_a_closing_marker() -> None:
    assert INJECTED is not None
    assert "AUTO_FIXABLE" in INJECTED
    assert CONTEXT_CLOSE in INJECTED


def test_the_injected_text_stays_inside_the_block_as_data() -> None:
    prompt = render_context(REQUEST, "es")

    assert prompt.count(CONTEXT_OPEN) == 1
    assert prompt.count(CONTEXT_CLOSE) == 1
    assert prompt.startswith(CONTEXT_OPEN)
    assert prompt.endswith(CONTEXT_CLOSE)
    assert read_context(prompt).record.raw == REQUEST.record.raw


def test_the_mock_answers_the_same_with_or_without_the_instruction() -> None:
    raw = {**REQUEST.record.raw, "Description": "MURANO STYLE GLASS BRACELET PINK"}
    plain = REQUEST.model_copy(update={"record": REQUEST.record.model_copy(update={"raw": raw})})

    attacked = _answer(REQUEST)

    assert attacked == _answer(plain)
    # Atlantis is in no alias pair (docs/02 section 4 MAP_TO_ENUM): a human, not AUTO_FIXABLE.
    assert [a["classification"] for a in attacked["analyses"]] == ["NEEDS_HUMAN_REVIEW"]
