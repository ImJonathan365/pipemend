"""Deadline, retry and repair of docs/03 NFR-07 and docs/04 section 6.2, steps 1, 5 and 6.

A scripted provider plays each answer or failure; the clock and the sleep are fakes, so only the two
tests of a real asyncio timeout take about a second.
"""

import asyncio
import json
from typing import Any
from uuid import UUID

import pytest
import structlog

from app.config import LlmProvider, Settings
from app.domain.contracts import (
    ErrorCode,
    Expected,
    Record,
    SchemaRef,
    Source,
    SourceType,
    TriageRequest,
    TriageResponse,
    Violation,
)
from app.domain.triage_prompt import ERRORS_CLOSE, ERRORS_OPEN
from app.providers.base import (
    ProviderAuthError,
    ProviderRateLimited,
    ProviderResult,
    ProviderTimeout,
    ProviderUnavailable,
    Usage,
)
from app.services import triage_service
from app.services.cache import TriageCache
from app.services.triage_service import (
    LlmInvalidOutputError,
    LlmProviderError,
    LlmRateLimitedError,
    LlmTimeoutError,
    TriageService,
)

REAL = Settings(llm_provider=LlmProvider.OPENAI_COMPATIBLE, llm_api_key="unused")
SENTINEL = "sentinel-only-in-the-answer"
VIOLATION = Violation(
    violation_id="v1",
    field="country",
    source_header="Country",
    error_code=ErrorCode.INVALID_ENUM_VALUE,
    received_value="Atlantis",
    expected=Expected(type="enum", allowed_values=["France"]),
    technical_message="seeded",
)
REQUEST = TriageRequest(
    request_id=UUID("0b6c3f5e-7f0f-4a55-9d0e-2a1f0f1b6a10"),
    batch_id=UUID("8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f"),
    schema=SchemaRef(name="sales_transaction", version="1"),
    source=Source(filename="dirty.csv", type=SourceType.CSV),
    record=Record(source_row_number=1, raw={"Country": "Atlantis"}),
    violations=[VIOLATION],
)
VALID = {
    "summary": "Unknown country.",
    "analyses": [
        {
            "violationId": "v1",
            "field": "country",
            "rootCause": "UNKNOWN",
            "explanation": "The country is not in the catalog.",
            "severity": "MEDIUM",
            "classification": "NEEDS_HUMAN_REVIEW",
            "confidence": 0.9,
            "correction": None,
            "suggestedHumanAction": "Check it.",
        }
    ],
}
INVALID = {
    "summary": "Unknown country.",
    "analyses": [VALID["analyses"][0] | {"confidence": SENTINEL}],
}
MISMATCHED = {
    "summary": "Unknown country.",
    "analyses": [VALID["analyses"][0] | {"violationId": "v9"}],
}
HANG = "hang"


class _Clock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


class _Script:
    """Each step is (seconds the call takes on the fake clock, answer, exception or HANG)."""

    def __init__(self, clock: _Clock, *steps: tuple[float, Any]) -> None:
        self.clock = clock
        self.steps = list(steps)
        self.prompts: list[str] = []

    @property
    def calls(self) -> int:
        return len(self.prompts)

    async def complete_json(
        self, _system_prompt: str, user_prompt: str, _json_schema: dict[str, Any]
    ) -> ProviderResult:
        took, outcome = self.steps[self.calls]
        self.prompts.append(user_prompt)
        self.clock.now += took
        if outcome == HANG:
            await asyncio.Event().wait()
        if isinstance(outcome, Exception):
            raise outcome
        if isinstance(outcome, ProviderResult):
            return outcome
        return ProviderResult(outcome, None)


def _triage(
    *steps: tuple[float, Any], settings: Settings = REAL
) -> tuple[_Script, list[float], TriageResponse]:
    clock = _Clock()
    script = _Script(clock, *steps)
    waits: list[float] = []

    async def sleep(seconds: float) -> None:
        waits.append(seconds)
        clock.now += seconds

    service = TriageService(script, settings, TriageCache(10, 60), clock=clock, sleep=sleep)
    return script, waits, asyncio.run(service.triage(REQUEST))


def _failing(
    error: type[Exception], *steps: tuple[float, Any], settings: Settings = REAL
) -> _Script:
    clock = _Clock()
    script = _Script(clock, *steps)

    async def sleep(seconds: float) -> None:
        clock.now += seconds

    service = TriageService(script, settings, TriageCache(10, 60), clock=clock, sleep=sleep)
    with pytest.raises(error):
        asyncio.run(service.triage(REQUEST))
    return script


def test_a_5xx_is_retried_once_and_the_answer_counts_both_calls() -> None:
    script, waits, response = _triage((1, ProviderUnavailable()), (1, VALID))

    assert script.calls == 2
    assert waits == []
    assert response.meta.llm_calls == 2


def test_two_5xx_are_a_provider_error_after_two_calls() -> None:
    script = _failing(
        LlmProviderError, (1, ProviderUnavailable()), (1, ProviderUnavailable()), (1, VALID)
    )

    assert script.calls == 2


def test_an_auth_error_is_a_provider_error_without_retry() -> None:
    script = _failing(LlmProviderError, (1, ProviderAuthError()), (1, VALID))

    assert script.calls == 1


def test_a_429_waits_its_retry_after_when_it_fits() -> None:
    _, waits, response = _triage((1, ProviderRateLimited(2)), (1, VALID))

    assert waits == [2]
    assert response.meta.llm_calls == 2


def test_a_429_whose_retry_after_does_not_fit_is_rate_limited_at_once() -> None:
    """25 s of deadline, 1 s spent: waiting 22 s would leave 2 s, under the 3 s of NFR-07."""
    script = _failing(LlmRateLimitedError, (1, ProviderRateLimited(22)), (1, VALID))

    assert script.calls == 1


def test_two_429_are_rate_limited_after_two_calls() -> None:
    script = _failing(
        LlmRateLimitedError, (1, ProviderRateLimited(None)), (1, ProviderRateLimited(None))
    )

    assert script.calls == 2


def test_a_timeout_of_the_provider_is_not_retried() -> None:
    """D218."""
    script = _failing(LlmTimeoutError, (20, ProviderTimeout()), (1, VALID))

    assert script.calls == 1


def test_a_call_that_hangs_ends_at_llm_timeout_seconds() -> None:
    settings = Settings(llm_provider=LlmProvider.OPENAI_COMPATIBLE, llm_timeout_seconds=1)

    script = _failing(LlmTimeoutError, (0, HANG), (0, VALID), settings=settings)

    assert script.calls == 1


def test_the_call_timeout_is_cut_to_what_is_left_of_the_deadline() -> None:
    """min(LLM_TIMEOUT_SECONDS, remaining): 20 s per call, but only 1 s of deadline."""
    settings = Settings(
        llm_provider=LlmProvider.OPENAI_COMPATIBLE,
        llm_timeout_seconds=20,
        ai_request_deadline_seconds=1,
    )

    script = _failing(LlmTimeoutError, (0, HANG), settings=settings)

    assert script.calls == 1


def test_an_invalid_answer_is_repaired_with_its_errors_as_data() -> None:
    script, _, response = _triage(
        (1, ProviderResult(INVALID, Usage(100, 20))), (1, ProviderResult(VALID, Usage(110, 25)))
    )

    first, repair = script.prompts
    assert response.meta.llm_calls == 2
    assert (response.meta.input_tokens, response.meta.output_tokens) == (210, 45)
    assert repair.startswith(first + "\n" + ERRORS_OPEN + "\n")
    assert repair.endswith("\n" + ERRORS_CLOSE)
    assert SENTINEL not in repair
    errors = json.loads(repair.removeprefix(first + "\n" + ERRORS_OPEN).removesuffix(ERRORS_CLOSE))
    assert errors
    assert all(set(error) == {"loc", "type", "msg"} for error in errors)


def test_analyses_that_are_not_the_violations_are_repaired_too() -> None:
    script, _, response = _triage((1, MISMATCHED), (1, VALID))

    assert response.meta.llm_calls == 2
    assert '"violations_mismatch"' in script.prompts[1]


def test_an_answer_still_invalid_after_the_repair_is_invalid_output() -> None:
    script = _failing(LlmInvalidOutputError, (1, INVALID), (1, INVALID), (1, VALID))

    assert script.calls == 2


def test_with_less_than_3_seconds_left_there_is_no_repair() -> None:
    script = _failing(LlmInvalidOutputError, (23, INVALID), (1, VALID))

    assert script.calls == 1


def test_with_less_than_3_seconds_left_there_is_no_retry() -> None:
    script = _failing(LlmProviderError, (23, ProviderUnavailable()), (1, VALID))

    assert script.calls == 1


def test_a_repair_and_a_retry_make_three_calls() -> None:
    script, _, response = _triage((1, INVALID), (1, ProviderUnavailable()), (1, VALID))

    assert script.calls == 3
    assert response.meta.llm_calls == 3


def test_there_is_never_a_fourth_call() -> None:
    script = _failing(
        LlmProviderError,
        (1, INVALID),
        (1, ProviderUnavailable()),
        (1, ProviderUnavailable()),
        (1, VALID),
    )

    assert script.calls == 3


def test_every_call_logs_its_attempt_and_outcome_and_nothing_of_the_answer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """D224: the per-call latency that B7 measures."""
    capture = structlog.testing.LogCapture()
    monkeypatch.setattr(triage_service, "_log", structlog.wrap_logger(None, processors=[capture]))

    _triage((1, ProviderUnavailable()), (1, INVALID), (1, ProviderResult(VALID, Usage(9, 3))))

    logs = capture.entries
    calls = [log for log in logs if log["event"] == "llm_call"]
    assert [(c["attempt"], c["outcome"]) for c in calls] == [
        ("first", "unavailable"),
        ("retry", "invalid_output"),
        ("repair", "ok"),
    ]
    assert (calls[-1]["inputTokens"], calls[-1]["outputTokens"]) == (9, 3)
    assert all(c["latencyMs"] >= 0 and c["provider"] == "openai_compatible" for c in calls)
    assert SENTINEL not in str(logs)
