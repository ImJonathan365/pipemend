"""The hard rules of docs/06 A.1 against what an LLM may answer."""

from typing import Any

import pytest

from app.domain.contracts import (
    EXPLANATION_MAX_LENGTH,
    SUMMARY_MAX_LENGTH,
    Analysis,
    Classification,
    ErrorCode,
    Expected,
    RootCause,
    Severity,
    Violation,
)
from app.domain.llm_output import LlmAnalysis
from app.domain.rules import (
    apply_hard_rules,
    cut_summary,
    is_cacheable,
    max_severity,
    record_decision,
)

FLOOR = {
    ErrorCode.MALFORMED_ROW: Severity.CRITICAL,
    ErrorCode.BUSINESS_RULE_VIOLATION: Severity.CRITICAL,
    ErrorCode.MISSING_REQUIRED_FIELD: Severity.HIGH,
    ErrorCode.OUT_OF_RANGE: Severity.HIGH,
    ErrorCode.MAX_LENGTH_EXCEEDED: Severity.HIGH,
    ErrorCode.INVALID_TYPE: Severity.MEDIUM,
    ErrorCode.INVALID_FORMAT: Severity.LOW,
    ErrorCode.PATTERN_MISMATCH: Severity.LOW,
    ErrorCode.INVALID_ENUM_VALUE: Severity.LOW,
}
FORCED_HUMAN = {
    ErrorCode.MALFORMED_ROW,
    ErrorCode.BUSINESS_RULE_VIOLATION,
    ErrorCode.MISSING_REQUIRED_FIELD,
    ErrorCode.OUT_OF_RANGE,
    ErrorCode.MAX_LENGTH_EXCEEDED,
}
SEVERITIES = list(Severity)


def _violation(
    code: ErrorCode = ErrorCode.INVALID_ENUM_VALUE,
    value: str | None = "UK",
    source_header: str | None = "Country",
) -> Violation:
    return Violation(
        violation_id="v1",
        field="country",
        source_header=source_header,
        error_code=code,
        received_value=value,
        expected=Expected(type="enum"),
        technical_message="seeded",
    )


def _correction(original: str | None = "UK", proposed: str | None = "United Kingdom") -> dict:
    return {
        "operation": "MAP_TO_ENUM",
        "originalValue": original,
        "proposedValue": proposed,
        "parameters": {},
    }


def _llm(**overrides: Any) -> LlmAnalysis:
    body = {
        "violationId": "v1",
        "field": "country",
        "rootCause": "SYNONYM",
        "explanation": "A known synonym.",
        "severity": "LOW",
        "classification": "AUTO_FIXABLE",
        "confidence": 0.96,
        "correction": _correction(),
        "suggestedHumanAction": None,
    }
    return LlmAnalysis.model_validate(body | overrides)


def _analysis(
    classification: Classification = Classification.NEEDS_HUMAN_REVIEW,
    severity: Severity = Severity.LOW,
    explanation: str = "text",
    action: str | None = "Check it.",
) -> Analysis:
    return Analysis(
        violation_id="v1",
        field="country",
        root_cause=RootCause.UNKNOWN,
        explanation=explanation,
        severity=severity,
        classification=classification,
        confidence=0.5,
        correction=None,
        suggested_human_action=action,
    )


def test_a_valid_correction_goes_through_unchanged() -> None:
    result = apply_hard_rules(_llm(), _violation(), "es")

    assert result.classification is Classification.AUTO_FIXABLE
    assert result.severity is Severity.LOW
    assert result.confidence == 0.96
    assert result.correction is not None
    assert result.correction.operation == "MAP_TO_ENUM"
    assert result.correction.original_value == "UK"
    assert result.correction.proposed_value == "United Kingdom"
    assert result.correction.parameters == {}
    assert result.suggested_human_action is None


@pytest.mark.parametrize("code", list(ErrorCode))
def test_every_error_code_has_its_floor_and_its_classification(code: ErrorCode) -> None:
    """AC-08.3 and docs/06 rules 1 and 2, with an LLM that claims the easiest answer."""
    result = apply_hard_rules(_llm(severity="LOW"), _violation(code), "es")

    assert result.severity is FLOOR[code]
    if code in FORCED_HUMAN:
        assert result.classification is Classification.NEEDS_HUMAN_REVIEW
        assert result.correction is None
    else:
        assert result.classification is Classification.AUTO_FIXABLE
        assert result.correction is not None


@pytest.mark.parametrize("code", list(ErrorCode))
@pytest.mark.parametrize("severity", list(Severity))
def test_the_llm_can_raise_a_severity_but_not_lower_it_below_the_floor(
    code: ErrorCode, severity: Severity
) -> None:
    result = apply_hard_rules(_llm(severity=severity.value), _violation(code), "es")

    assert result.severity is max(severity, FLOOR[code], key=SEVERITIES.index)


@pytest.mark.parametrize(
    "correction",
    [
        pytest.param(None, id="no-correction"),
        pytest.param(_correction(original="uk"), id="other-original-value"),
        pytest.param(_correction(original=None), id="null-original-value"),
        pytest.param(_correction(proposed=None), id="null-proposal-outside-nullify"),
    ],
)
def test_a_fixable_analysis_without_a_valid_correction_goes_to_a_human(
    correction: dict | None,
) -> None:
    """docs/06 rule 3, which also has to leave an analysis that Analysis accepts."""
    result = apply_hard_rules(_llm(correction=correction), _violation(), "es")

    assert result.classification is Classification.NEEDS_HUMAN_REVIEW
    assert result.correction is None
    assert result.suggested_human_action


def test_nullify_token_may_propose_null() -> None:
    correction = _correction(original="N/A", proposed=None) | {"operation": "NULLIFY_TOKEN"}

    result = apply_hard_rules(_llm(correction=correction), _violation(value="N/A"), "es")

    assert result.classification is Classification.AUTO_FIXABLE
    assert result.correction is not None
    assert result.correction.proposed_value is None


def test_a_human_review_with_a_correction_loses_it_whatever_the_error_code() -> None:
    """Rule 1 only names the non correctable codes, and TriageResponse rejects the rest."""
    llm = _llm(classification="NEEDS_HUMAN_REVIEW", suggestedHumanAction="Ask the owner.")

    result = apply_hard_rules(llm, _violation(ErrorCode.INVALID_FORMAT), "es")

    assert result.correction is None
    assert result.suggested_human_action == "Ask the owner."


@pytest.mark.parametrize("action", [None, "", "   "])
@pytest.mark.parametrize(
    ("llm_overrides", "code"),
    [
        pytest.param({}, ErrorCode.MISSING_REQUIRED_FIELD, id="forced-by-rule-1"),
        pytest.param({"correction": None}, ErrorCode.INVALID_FORMAT, id="sent-by-rule-3"),
        pytest.param(
            {"classification": "NEEDS_HUMAN_REVIEW"}, ErrorCode.INVALID_FORMAT, id="from-the-llm"
        ),
    ],
)
def test_a_human_always_gets_an_action(
    action: str | None, llm_overrides: dict[str, Any], code: ErrorCode
) -> None:
    llm = _llm(suggestedHumanAction=action, **llm_overrides)

    spanish = apply_hard_rules(llm, _violation(code), "es").suggested_human_action
    english = apply_hard_rules(llm, _violation(code), "en").suggested_human_action

    assert spanish
    assert spanish.strip()
    assert english
    assert english.strip()
    assert spanish != english


@pytest.mark.parametrize(
    ("length", "kept"),
    [
        (EXPLANATION_MAX_LENGTH, EXPLANATION_MAX_LENGTH),
        (EXPLANATION_MAX_LENGTH + 1, EXPLANATION_MAX_LENGTH),
        (20 * EXPLANATION_MAX_LENGTH, EXPLANATION_MAX_LENGTH),
    ],
)
def test_a_long_explanation_is_cut_not_rejected(length: int, kept: int) -> None:
    """docs/06 rule 5. Accented text: the limit counts characters, not bytes."""
    result = apply_hard_rules(_llm(explanation="é" * length), _violation(), "es")

    assert result.explanation == "é" * kept


@pytest.mark.parametrize(
    ("length", "kept"),
    [(SUMMARY_MAX_LENGTH, SUMMARY_MAX_LENGTH), (SUMMARY_MAX_LENGTH + 1, SUMMARY_MAX_LENGTH)],
)
def test_a_long_summary_is_cut(length: int, kept: int) -> None:
    assert cut_summary("é" * length) == "é" * kept


def test_a_long_human_action_is_kept_because_the_contract_sets_no_maximum() -> None:
    action = "x" * (2 * EXPLANATION_MAX_LENGTH)
    llm = _llm(classification="NEEDS_HUMAN_REVIEW", suggestedHumanAction=action)

    assert apply_hard_rules(llm, _violation(), "es").suggested_human_action == action


def test_record_decision_is_fixable_only_if_every_analysis_is() -> None:
    """docs/06 rule 6."""
    fixable = apply_hard_rules(_llm(), _violation(), "es")
    human = _analysis()

    assert record_decision([fixable, fixable]) is Classification.AUTO_FIXABLE
    assert record_decision([fixable, human]) is Classification.NEEDS_HUMAN_REVIEW


def test_max_severity_is_the_highest_of_the_scale_not_of_the_alphabet() -> None:
    analyses = [_analysis(severity=Severity.MEDIUM), _analysis(severity=Severity.CRITICAL)]

    assert max_severity(analyses) is Severity.CRITICAL
    assert max_severity([_analysis(severity=Severity.LOW)]) is Severity.LOW


def test_a_record_level_analysis_is_never_cacheable() -> None:
    """AC-08.4 b: it depends on the whole row, even with a text that quotes nothing."""
    violation = _violation(ErrorCode.MALFORMED_ROW).model_copy(update={"field": "_record"})

    assert not is_cacheable(_analysis(), violation, {})


@pytest.mark.parametrize(
    ("explanation", "action", "cacheable"),
    [
        pytest.param("The value is odd.", "Check it.", True, id="quotes-nothing"),
        pytest.param("Invoice 536520 is odd.", "Check it.", False, id="in-the-explanation"),
        pytest.param("The value is odd.", "See invoice 536520.", False, id="in-the-action"),
        pytest.param("Product ivory candle.", "Check it.", False, id="another-case"),
        pytest.param("The value UK is odd.", "Check it.", True, id="its-own-column"),
    ],
)
def test_a_text_that_quotes_another_column_is_not_cacheable(
    explanation: str, action: str | None, cacheable: bool
) -> None:
    """AC-08.4 c and docs/09 7.8."""
    raw = {"Invoice": "536520", "Description": "IVORY CANDLE", "Country": "UK"}
    analysis = _analysis(explanation=explanation, action=action)

    assert is_cacheable(analysis, _violation(), raw) is cacheable


def test_an_analysis_without_a_human_action_can_be_cached() -> None:
    assert is_cacheable(
        apply_hard_rules(_llm(), _violation(), "es"), _violation(), {"Invoice": "1"}
    )


def test_empty_and_blank_columns_quote_nothing() -> None:
    raw: dict[str, str | None] = {"Invoice": "", "Description": None, "Quantity": "   "}

    assert is_cacheable(_analysis(), _violation(), raw)


def test_a_padded_value_of_another_column_is_still_found() -> None:
    analysis = _analysis(explanation="Invoice 536520 is odd.")

    assert not is_cacheable(analysis, _violation(), {"Invoice": "  536520 "})


def test_without_source_header_every_column_counts_as_another_one() -> None:
    """record.raw is keyed by header; the cost of not knowing is one more call, not a leak."""
    analysis = _analysis(explanation="The value UK is odd.")

    assert not is_cacheable(analysis, _violation(source_header=None), {"Country": "UK"})
