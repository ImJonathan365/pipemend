"""LLM_MAX_CALLS_PER_DAY (AC-08.8): checked before every real LLM call, reset at midnight UTC.

The "real" provider here is the mock behind a counting wrapper, with settings that name a real
provider: no network, and answers that always match the request.
"""

import asyncio
import json
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from uuid import UUID

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.api.triage import get_provider
from app.config import LlmProvider, Settings, get_settings
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
from app.main import create_app
from app.providers.base import ProviderResult, ProviderUnavailable
from app.providers.mock import MockProvider
from app.services.cache import TriageCache
from app.services.daily_cap import DailyCallCounter
from app.services.triage_service import (
    LlmBudgetExhaustedError,
    LlmProviderError,
    TriageService,
)

FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"
ROW_VALUE = "ROW-VALUE-536520"
INVALID = {"summary": "x", "analyses": []}


def _real(cap: int) -> Settings:
    return Settings(
        llm_provider=LlmProvider.OPENAI_COMPATIBLE, llm_api_key="unused", llm_max_calls_per_day=cap
    )


class _Clock:
    def __init__(self, now: datetime) -> None:
        self.now = now

    def __call__(self) -> datetime:
        return self.now


class _Provider:
    """The mock's answers, unless a step says otherwise: a dict to return or an error to raise."""

    def __init__(self, *steps: Any) -> None:
        self.steps = list(steps)
        self.calls = 0
        self._mock = MockProvider()

    async def complete_json(
        self, system_prompt: str, user_prompt: str, json_schema: dict[str, Any]
    ) -> ProviderResult:
        step = self.steps[self.calls] if self.calls < len(self.steps) else None
        self.calls += 1
        if isinstance(step, Exception):
            raise step
        if isinstance(step, dict):
            return ProviderResult(step, None)
        return await self._mock.complete_json(system_prompt, user_prompt, json_schema)


def _request(value: str = "UK", language: str = "en") -> TriageRequest:
    return TriageRequest(
        request_id=UUID("0b6c3f5e-7f0f-4a55-9d0e-2a1f0f1b6a10"),
        batch_id=UUID("8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f"),
        schema=SchemaRef(name="sales_transaction", version="1"),
        source=Source(filename="dirty.csv", type=SourceType.CSV),
        record=Record(source_row_number=1, raw={"Country": value}),
        violations=[
            Violation(
                violation_id="v1",
                field="country",
                source_header="Country",
                error_code=ErrorCode.INVALID_ENUM_VALUE,
                received_value=value,
                expected=Expected(type="enum", allowed_values=["France", "United Kingdom"]),
                technical_message="seeded",
            )
        ],
        options={"explanationLanguage": language},
    )


def _service(provider: _Provider, cap: int, counter: DailyCallCounter) -> TriageService:
    return TriageService(provider, _real(cap), TriageCache(10, 60), daily_calls=counter)


def _run(service: TriageService, request: TriageRequest) -> TriageResponse:
    return asyncio.run(service.triage(request))


def test_the_counter_reserves_up_to_the_cap() -> None:
    counter = DailyCallCounter(_Clock(datetime(2010, 12, 1, 12, tzinfo=UTC)))

    assert [counter.try_reserve(2) for _ in range(3)] == [True, True, False]
    assert counter.today() == 2


def test_the_counter_starts_again_at_midnight_utc() -> None:
    clock = _Clock(datetime(2010, 12, 1, 23, 59, 59, tzinfo=UTC))
    counter = DailyCallCounter(clock)
    assert counter.try_reserve(1)
    assert not counter.try_reserve(1)

    clock.now += timedelta(seconds=1)

    assert counter.today() == 0
    assert counter.try_reserve(1)


def test_midnight_is_the_utc_one_whatever_the_clock_zone() -> None:
    """01:00 in UTC+2 is still the previous UTC day."""
    clock = _Clock(datetime(2010, 12, 1, 23, 0, tzinfo=UTC))
    counter = DailyCallCounter(clock)
    assert counter.try_reserve(1)

    clock.now = datetime(2010, 12, 2, 1, 0, tzinfo=timezone(timedelta(hours=2)))

    assert not counter.try_reserve(1)


def test_a_request_after_the_cap_is_refused_without_calling() -> None:
    provider, counter = _Provider(), DailyCallCounter()
    service = _service(provider, 1, counter)
    assert _run(service, _request("UK")).meta.llm_calls == 1

    with pytest.raises(LlmBudgetExhaustedError):
        _run(service, _request("USA"))

    assert provider.calls == 1
    assert counter.today() == 1


def test_a_repair_with_the_cap_reached_is_budget_exhausted_without_calling() -> None:
    """S5-10: the cap is checked before every call, so it is never exceeded."""
    provider = _Provider(INVALID)

    with pytest.raises(LlmBudgetExhaustedError):
        _run(_service(provider, 1, DailyCallCounter()), _request())

    assert provider.calls == 1


def test_a_retry_with_the_cap_reached_is_budget_exhausted_without_calling() -> None:
    provider = _Provider(ProviderUnavailable())

    with pytest.raises(LlmBudgetExhaustedError):
        _run(_service(provider, 1, DailyCallCounter()), _request())

    assert provider.calls == 1


def test_failed_calls_count_too() -> None:
    provider, counter = _Provider(ProviderUnavailable(), ProviderUnavailable()), DailyCallCounter()
    service = _service(provider, 2, counter)
    with pytest.raises(LlmProviderError):
        _run(service, _request("UK"))

    with pytest.raises(LlmBudgetExhaustedError):
        _run(service, _request("USA"))

    assert provider.calls == 2
    assert counter.today() == 2


def test_a_cache_hit_still_answers_with_the_cap_reached() -> None:
    provider = _Provider()
    service = _service(provider, 1, DailyCallCounter())
    _run(service, _request("UK"))

    response = _run(service, _request("UK"))

    assert response.meta.cache_hit is True
    assert response.meta.llm_calls == 0
    assert provider.calls == 1


def test_the_mock_never_reserves() -> None:
    counter = DailyCallCounter()
    service = TriageService(
        MockProvider(), Settings(llm_max_calls_per_day=1), TriageCache(10, 60), daily_calls=counter
    )

    for value in ("UK", "USA", "GB"):
        _run(service, _request(value))

    assert counter.today() == 0


@pytest.mark.parametrize("cap", [0, -1])
def test_a_cap_under_1_does_not_start(cap: int) -> None:
    """D230."""
    with pytest.raises(ValidationError):
        Settings(llm_max_calls_per_day=cap)


def _client(settings: Settings, provider: Any) -> TestClient:
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    app.dependency_overrides[get_provider] = lambda: provider
    return TestClient(app)


def _body(language: str) -> dict[str, Any]:
    body = json.loads((FIXTURES / "triage-request.format-errors.json").read_text(encoding="utf-8"))
    body["record"]["raw"] = body["record"]["raw"] | {"Description": ROW_VALUE}
    # The language is part of the cache key: a second language is a miss, so it needs the LLM.
    return body | {"options": {"explanationLanguage": language}}


def test_the_api_shares_one_counter_per_app() -> None:
    """Not the counter TriageService creates when none is passed (D229)."""
    provider = _Provider()
    client = _client(_real(1), provider)

    first = client.post("/v1/triage", json=_body("es"))
    second = client.post("/v1/triage", json=_body("en"))

    assert first.status_code == 200
    assert second.status_code == 429
    assert second.headers["content-type"] == "application/problem+json"
    assert second.json()["code"] == "LLM_BUDGET_EXHAUSTED"
    assert ROW_VALUE not in second.text
    assert provider.calls == 1
    assert client.get("/v1/info").json()["limits"]["llmCallsToday"] == 1


def test_info_counts_nothing_with_the_mock() -> None:
    client = _client(Settings(), MockProvider())

    for language in ("es", "en"):
        assert client.post("/v1/triage", json=_body(language)).status_code == 200

    assert client.get("/v1/info").json()["limits"]["llmCallsToday"] == 0
