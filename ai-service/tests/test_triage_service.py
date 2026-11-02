"""The flow of docs/04 section 6.2 against the service: there is no route yet.

Request inputs are the contract fixtures or written by hand; nothing here reads data/.
"""

import asyncio
import json
from pathlib import Path
from typing import Any
from uuid import UUID

import pytest

from app.config import Settings
from app.domain.contracts import (
    EXPLANATION_MAX_LENGTH,
    SUMMARY_MAX_LENGTH,
    Classification,
    ErrorCode,
    Expected,
    Record,
    SchemaRef,
    Severity,
    Source,
    SourceType,
    TriageOptions,
    TriageRequest,
    TriageResponse,
    Violation,
)
from app.domain.llm_output import LlmTriageOutput
from app.domain.triage_prompt import read_context
from app.providers.base import ProviderResult, Usage
from app.providers.mock import MockProvider
from app.services.cache import TriageCache
from app.services.triage_service import LlmInvalidOutputError, TriageService

FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"
REQUEST_FIXTURES = [
    entry["fixture"]
    for entry in json.loads((FIXTURES / "index.json").read_text(encoding="utf-8"))
    if entry["kind"] == "request" and entry["expected"] == "valid"
]
ENUM = Expected(type="enum", allowed_values=["France", "United Kingdom"])
DATE = Expected(type="datetime", format="yyyy-MM-dd HH:mm:ss", min="2009-01-01 00:00:00")
ROW = {"Invoice": "536520", "Country": "UK", "InvoiceDate": "25/12/2010 08:26"}


def _country(value: str = "UK", violation_id: str = "v1") -> Violation:
    return Violation(
        violation_id=violation_id,
        field="country",
        source_header="Country",
        error_code=ErrorCode.INVALID_ENUM_VALUE,
        received_value=value,
        expected=ENUM,
        technical_message="seeded",
    )


def _date(violation_id: str = "v2") -> Violation:
    return Violation(
        violation_id=violation_id,
        field="invoice_date",
        source_header="InvoiceDate",
        error_code=ErrorCode.INVALID_FORMAT,
        received_value="25/12/2010 08:26",
        expected=DATE,
        technical_message="seeded",
    )


def _record_level() -> Violation:
    return Violation(
        violation_id="v9",
        field="_record",
        error_code=ErrorCode.BUSINESS_RULE_VIOLATION,
        received_value=None,
        expected=Expected(rule="BR-02"),
        technical_message="seeded",
    )


def _request(
    *violations: Violation,
    raw: dict[str, str | None] | None = None,
    language: str | None = None,
) -> TriageRequest:
    return TriageRequest(
        request_id=UUID("0b6c3f5e-7f0f-4a55-9d0e-2a1f0f1b6a10"),
        batch_id=UUID("8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f"),
        schema=SchemaRef(name="sales_transaction", version="1"),
        source=Source(filename="dirty.csv", type=SourceType.CSV),
        record=Record(source_row_number=1, raw=ROW if raw is None else raw),
        violations=list(violations),
        options=TriageOptions(explanation_language=language) if language else None,
    )


def _fixture(name: str) -> TriageRequest:
    return TriageRequest.model_validate_json((FIXTURES / name).read_text(encoding="utf-8"))


class _Counting:
    """The mock, counting calls and keeping what it was sent."""

    def __init__(self) -> None:
        self.calls = 0
        self.system_prompt: str | None = None
        self.user_prompt: str | None = None
        self.schema: dict[str, Any] | None = None
        self._mock = MockProvider()

    async def complete_json(
        self, system_prompt: str, user_prompt: str, json_schema: dict[str, Any]
    ) -> ProviderResult:
        self.calls += 1
        self.system_prompt, self.user_prompt, self.schema = system_prompt, user_prompt, json_schema
        return await self._mock.complete_json(system_prompt, user_prompt, json_schema)


class _Canned:
    """An LLM that answers whatever the test wants, as a dict, like any real provider."""

    def __init__(self, content: dict[str, Any], usage: Usage | None = None) -> None:
        self.content = content
        self.usage = usage
        self.calls = 0

    async def complete_json(
        self, _system_prompt: str, _user_prompt: str, _json_schema: dict[str, Any]
    ) -> ProviderResult:
        self.calls += 1
        return ProviderResult(self.content, self.usage)


def _llm_analysis(violation: Violation, **overrides: Any) -> dict[str, Any]:
    body = {
        "violationId": violation.violation_id,
        "field": violation.field,
        "rootCause": "UNKNOWN",
        "explanation": "The value is odd.",
        "severity": "MEDIUM",
        "classification": "NEEDS_HUMAN_REVIEW",
        "confidence": 0.5,
        "correction": None,
        "suggestedHumanAction": "Check it.",
    }
    return body | overrides


def _llm_output(*analyses: dict[str, Any], summary: str = "From the LLM.") -> dict[str, Any]:
    return {"summary": summary, "analyses": list(analyses)}


def _service(provider: Any, settings: Settings | None = None) -> TriageService:
    return TriageService(provider, settings or Settings(), TriageCache(100, 3600))


def _run(service: TriageService, request: TriageRequest) -> TriageResponse:
    return asyncio.run(service.triage(request))


@pytest.mark.parametrize("name", REQUEST_FIXTURES)
def test_every_valid_request_of_the_contract_gets_a_contract_response(name: str) -> None:
    """AC-08.1 against the service: one analysis per violation, in order, with its record fields."""
    request = _fixture(name)

    response = _run(_service(_Counting()), request)

    assert response.request_id == request.request_id
    assert [a.violation_id for a in response.analyses] == [
        v.violation_id for v in request.violations
    ]
    assert [a.field for a in response.analyses] == [v.field for v in request.violations]
    assert response.summary
    for analysis in response.analyses:
        assert (analysis.correction is not None) == (
            analysis.classification is Classification.AUTO_FIXABLE
        )


def test_the_format_errors_of_docs_06_are_fixable_and_low() -> None:
    response = _run(_service(_Counting()), _fixture("triage-request.format-errors.json"))

    assert response.record_decision is Classification.AUTO_FIXABLE
    assert response.max_severity is Severity.LOW


def test_a_missing_price_goes_to_a_human_with_high_severity() -> None:
    response = _run(_service(_Counting()), _fixture("triage-request.missing-price.json"))

    assert response.record_decision is Classification.NEEDS_HUMAN_REVIEW
    assert response.max_severity is Severity.HIGH


def test_meta_traces_the_answer_with_the_settings_and_no_tokens_from_the_mock() -> None:
    """AC-08.2 and NFR-12. LLM_MODEL and the prompt version only come from the settings."""
    settings = Settings(llm_model="any-model", prompt_version_triage="triage_v7")

    meta = _run(_service(_Counting(), settings), _request(_country())).meta

    assert meta.provider == "mock"
    assert meta.model == "any-model"
    assert meta.prompt_version == "triage_v7"
    assert meta.latency_ms >= 0
    assert meta.cache_hit is False
    assert meta.input_tokens is None
    assert meta.output_tokens is None


def test_the_tokens_a_provider_reports_reach_meta() -> None:
    """AC-08.2: "if the provider reports them"."""
    provider = _Canned(_llm_output(_llm_analysis(_country())), Usage(120, 45))

    meta = _run(_service(provider), _request(_country())).meta

    assert (meta.input_tokens, meta.output_tokens) == (120, 45)


def test_llm_calls_is_zero_with_the_mock_on_a_miss_and_on_a_hit() -> None:
    """docs/06: "llamadas reales al LLM", and its mock example says 0."""
    service = _service(_Counting())

    assert _run(service, _request(_country())).meta.llm_calls == 0
    assert _run(service, _request(_country())).meta.llm_calls == 0


def test_the_prompt_carries_the_request_as_data_and_the_output_schema() -> None:
    """docs/04 step 4. The system prompt stays empty until Sprint 5 (docs/07)."""
    provider = _Counting()
    request = _request(_country())

    _run(_service(provider, Settings(ai_explanation_language="en")), request)

    assert provider.system_prompt == ""
    assert provider.user_prompt is not None
    sent = read_context(provider.user_prompt)
    assert sent.violations == request.violations
    assert sent.record == request.record
    assert sent.options == TriageOptions(explanation_language="en")
    assert provider.schema == LlmTriageOutput.model_json_schema(by_alias=True)


def test_the_language_of_the_request_wins_over_the_default() -> None:
    """AC-08.1."""
    settings = Settings(ai_explanation_language="es")

    default = _run(_service(_Counting(), settings), _request(_country()))
    english = _run(_service(_Counting(), settings), _request(_country(), language="en"))

    assert default.analyses[0].explanation != english.analyses[0].explanation


def test_the_same_signature_on_another_row_is_answered_from_the_cache() -> None:
    """AC-08.4 a: the id belongs to the request, the analysis to the signature."""
    provider = _Counting()
    service = _service(provider)
    first = _run(service, _request(_country(violation_id="a"), _date(violation_id="b")))

    other_row = {"Invoice": "999999", "Country": "UK", "InvoiceDate": "25/12/2010 08:26"}
    second = _run(
        service,
        _request(_date(violation_id="x"), _country(violation_id="y"), raw=other_row),
    )

    assert provider.calls == 1
    assert second.meta.cache_hit is True
    assert second.meta.llm_calls == 0
    assert second.meta.input_tokens is None
    assert [a.violation_id for a in second.analyses] == ["x", "y"]
    assert [a.field for a in second.analyses] == ["invoice_date", "country"]
    assert second.analyses[1].correction == first.analyses[0].correction
    assert second.record_decision is Classification.AUTO_FIXABLE


def test_a_hit_does_not_change_the_id_of_the_stored_analysis() -> None:
    provider = _Counting()
    service = _service(provider)
    _run(service, _request(_country(violation_id="a")))
    _run(service, _request(_country(violation_id="b")))

    third = _run(service, _request(_country(violation_id="c")))

    assert third.analyses[0].violation_id == "c"
    assert provider.calls == 1


@pytest.mark.parametrize(
    "other",
    [
        pytest.param(_request(_country("USA")), id="another-value"),
        pytest.param(_request(_country(), language="en"), id="another-language"),
    ],
)
def test_another_signature_is_a_miss(other: TriageRequest) -> None:
    provider = _Counting()
    service = _service(provider)
    _run(service, _request(_country(), language="es"))

    response = _run(service, other)

    assert provider.calls == 2
    assert response.meta.cache_hit is False


def test_a_record_level_violation_is_never_cached() -> None:
    """AC-08.4 b and docs/09 7.8."""
    provider = _Counting()
    service = _service(provider)
    request = _request(_record_level())

    _run(service, request)
    response = _run(service, request)

    assert provider.calls == 2
    assert response.meta.cache_hit is False


def test_a_request_with_a_record_level_violation_always_calls_and_still_caches_the_rest() -> None:
    """docs/04 step 2: only "si todas las violaciones son a nivel campo"."""
    provider = _Counting()
    service = _service(provider)
    _run(service, _request(_country()))

    mixed = _run(service, _request(_country(), _record_level()))

    assert provider.calls == 2
    assert mixed.meta.cache_hit is False
    assert mixed.record_decision is Classification.NEEDS_HUMAN_REVIEW
    assert _run(service, _request(_country())).meta.cache_hit is True
    assert provider.calls == 2


def test_a_text_that_quotes_another_column_is_answered_but_not_stored() -> None:
    """AC-08.4 c."""
    violation = _country()
    provider = _Canned(
        _llm_output(_llm_analysis(violation, explanation="Invoice 536520 looks odd."))
    )
    service = _service(provider)

    first = _run(service, _request(violation))
    second = _run(service, _request(violation))

    assert first.analyses[0].explanation == "Invoice 536520 looks odd."
    assert provider.calls == 2
    assert second.meta.cache_hit is False


def test_only_the_analyses_that_pass_are_stored() -> None:
    country, date = _country(), _date()
    provider = _Canned(
        _llm_output(
            _llm_analysis(country, explanation="Invoice 536520 looks odd."),
            _llm_analysis(date),
        )
    )
    service = _service(provider)
    _run(service, _request(country, date))

    assert _run(service, _request(date)).meta.cache_hit is True

    provider.content = _llm_output(_llm_analysis(country))
    assert _run(service, _request(country)).meta.cache_hit is False


@pytest.mark.parametrize("name", REQUEST_FIXTURES)
def test_the_answers_of_the_mock_never_quote_another_column(name: str) -> None:
    """The test AC-08.4 c asks for: every field-level answer of the mock can be stored."""
    provider = _Counting()
    service = _service(provider)
    request = _fixture(name)

    _run(service, request)
    response = _run(service, request)

    field_level = all(v.field != "_record" for v in request.violations)
    assert response.meta.cache_hit is field_level


@pytest.mark.parametrize(
    ("language", "expected"),
    [
        ("es", "Violaciones analizadas: 2. Corregibles: 1. Para revisión humana: 1."),
        ("en", "Violations analysed: 2. Fixable: 1. For human review: 1."),
    ],
)
def test_a_full_hit_builds_its_summary_from_the_analyses(language: str, expected: str) -> None:
    """AC-08.4 d, from the stored analyses and never from a stored summary."""
    country = _country()
    missing = Violation(
        violation_id="v2",
        field="unit_price",
        source_header="Price",
        error_code=ErrorCode.MISSING_REQUIRED_FIELD,
        received_value="",
        expected=Expected(type="decimal"),
        technical_message="seeded",
    )
    service = _service(_Counting())
    _run(service, _request(country, missing, language=language))

    response = _run(service, _request(missing, country, language=language))

    assert response.meta.cache_hit is True
    assert response.summary == expected
    assert response.record_decision is Classification.NEEDS_HUMAN_REVIEW
    assert response.max_severity is Severity.HIGH


def test_with_the_mock_a_hit_and_a_miss_say_the_same() -> None:
    """NFR-13: only meta tells them apart."""
    service = _service(_Counting())
    request = _request(_country(), _date())

    miss = _run(service, request)
    hit = _run(service, request)

    assert hit.summary == miss.summary
    assert hit.analyses == miss.analyses


def test_a_partial_hit_calls_for_the_whole_request() -> None:
    """AC-08.4 d."""
    provider = _Counting()
    service = _service(provider)
    _run(service, _request(_country()))

    response = _run(service, _request(_country(), _date()))

    assert provider.calls == 2
    assert response.meta.cache_hit is False
    assert provider.user_prompt is not None
    assert len(read_context(provider.user_prompt).violations) == 2


def test_an_expired_entry_calls_again() -> None:
    now = [0.0]
    provider = _Counting()
    service = TriageService(provider, Settings(), TriageCache(10, 60, lambda: now[0]))
    _run(service, _request(_country()))

    now[0] = 60.0
    response = _run(service, _request(_country()))

    assert provider.calls == 2
    assert response.meta.cache_hit is False


def test_long_texts_are_cut_instead_of_failing_the_response() -> None:
    """docs/06 rule 5. Without the cut, TriageResponse would raise."""
    violation = _country()
    provider = _Canned(
        _llm_output(
            _llm_analysis(violation, explanation="x" * (EXPLANATION_MAX_LENGTH + 1)),
            summary="y" * (SUMMARY_MAX_LENGTH + 1),
        )
    )
    service = _service(provider)

    response = _run(service, _request(violation))

    assert response.summary == "y" * SUMMARY_MAX_LENGTH
    assert response.analyses[0].explanation == "x" * EXPLANATION_MAX_LENGTH
    hit = _run(service, _request(violation))
    assert hit.analyses[0].explanation == "x" * EXPLANATION_MAX_LENGTH


def test_the_hard_rules_overrule_the_llm_and_the_record_fields_are_recomputed() -> None:
    """AC-08.3 and docs/06 rules 1, 2 and 6, with an LLM that claims the easiest answer."""
    missing = Violation(
        violation_id="v1",
        field="unit_price",
        source_header="Price",
        error_code=ErrorCode.MISSING_REQUIRED_FIELD,
        received_value="",
        expected=Expected(type="decimal"),
        technical_message="seeded",
    )
    provider = _Canned(
        _llm_output(
            _llm_analysis(
                missing,
                severity="LOW",
                classification="AUTO_FIXABLE",
                confidence=1.0,
                correction={
                    "operation": "PARSE_NUMBER",
                    "originalValue": "",
                    "proposedValue": "1.00",
                    "parameters": {"decimalSeparator": "."},
                },
                suggestedHumanAction=None,
            )
        )
    )

    response = _run(_service(provider), _request(missing))

    analysis = response.analyses[0]
    assert analysis.classification is Classification.NEEDS_HUMAN_REVIEW
    assert analysis.correction is None
    assert analysis.severity is Severity.HIGH
    assert analysis.suggested_human_action
    assert response.record_decision is Classification.NEEDS_HUMAN_REVIEW
    assert response.max_severity is Severity.HIGH


def _reordered(country: Violation, date: Violation) -> dict[str, Any]:
    return _llm_output(_llm_analysis(date), _llm_analysis(country))


@pytest.mark.parametrize(
    "content",
    [
        pytest.param({}, id="empty"),
        pytest.param({"analyses": []}, id="no-summary"),
        pytest.param({"summary": "s"}, id="no-analyses"),
        pytest.param({"summary": "s", "analyses": [], "maxSeverity": "LOW"}, id="invented-field"),
        pytest.param({"summary": 5, "analyses": []}, id="summary-not-a-string"),
        pytest.param(_llm_output(), id="no-analysis"),
        pytest.param(_llm_output(_llm_analysis(_country())), id="one-missing"),
        pytest.param(_reordered(_country(), _date()), id="another-order"),
        pytest.param(
            _llm_output(_llm_analysis(_country()), _llm_analysis(_country())), id="repeated"
        ),
        pytest.param(
            _llm_output(
                _llm_analysis(_country()), _llm_analysis(_date()), _llm_analysis(_date("v3"))
            ),
            id="one-too-many",
        ),
        pytest.param(
            _llm_output(_llm_analysis(_country()), _llm_analysis(_date()) | {"field": "country"}),
            id="another-field",
        ),
        pytest.param(
            _llm_output(_llm_analysis(_country(), confidence="0.9"), _llm_analysis(_date())),
            id="confidence-as-text",
        ),
        pytest.param(
            _llm_output(_llm_analysis(_country(), severity="SEVERE"), _llm_analysis(_date())),
            id="unknown-severity",
        ),
        pytest.param(
            _llm_output(
                _llm_analysis(
                    _country(),
                    classification="AUTO_FIXABLE",
                    correction={
                        "operation": "INVENT",
                        "originalValue": "UK",
                        "proposedValue": "x",
                        "parameters": {},
                    },
                ),
                _llm_analysis(_date()),
            ),
            id="operation-outside-the-allowlist",
        ),
    ],
)
def test_an_invalid_output_raises_and_stores_nothing(content: dict[str, Any]) -> None:
    """docs/06 frontier LLM -> ai-service and rule 4. The repair attempt is Sprint 5 (docs/07)."""
    provider = _Canned(content)
    cache = TriageCache(10, 60)
    service = TriageService(provider, Settings(), cache)

    with pytest.raises(LlmInvalidOutputError):
        _run(service, _request(_country(), _date()))

    assert provider.calls == 1
    assert len(cache) == 0


def test_the_error_of_an_invalid_output_does_not_quote_the_row() -> None:
    """The validation error quotes its input, and this message reaches logs and responses."""
    provider = _Canned(_llm_output(_llm_analysis(_country(), confidence="secret-value")))

    with pytest.raises(LlmInvalidOutputError) as caught:
        _run(_service(provider), _request(_country()))

    assert "secret-value" not in str(caught.value)


def test_the_daily_cap_does_not_apply_to_the_mock() -> None:
    """AC-08.8: "Con LLM_PROVIDER=mock el tope no aplica". The cap itself is Sprint 5 (docs/07)."""
    provider = _Counting()
    service = _service(provider, Settings(llm_max_calls_per_day=1))

    for value in ("UK", "USA", "GB"):
        assert _run(service, _request(_country(value))).analyses

    assert provider.calls == 3
