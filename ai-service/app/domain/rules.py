"""The hard rules applied after the LLM (docs/06 A.1): defence in depth, not the authority."""

from typing import Literal

from app.domain.contracts import (
    EXPLANATION_MAX_LENGTH,
    SUMMARY_MAX_LENGTH,
    Analysis,
    Classification,
    Correction,
    ErrorCode,
    Operation,
    Severity,
    Violation,
)
from app.domain.llm_output import LlmAnalysis

RECORD_FIELD = "_record"

_SEVERITY_FLOOR = {
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
_NOT_CORRECTABLE = frozenset(
    {
        ErrorCode.MALFORMED_ROW,
        ErrorCode.BUSINESS_RULE_VIOLATION,
        ErrorCode.MISSING_REQUIRED_FIELD,
        ErrorCode.OUT_OF_RANGE,
        ErrorCode.MAX_LENGTH_EXCEEDED,
    }
)
# Analysis requires an action for a human, and these rules can send there an analysis without one.
_HUMAN_ACTION = {
    "es": "Revisar el valor en el sistema de origen y reenviar la fila corregida.",
    "en": "Check the value in the source system and resend the corrected row.",
}
_SEVERITIES = list(Severity)


def apply_hard_rules(
    analysis: LlmAnalysis, violation: Violation, language: Literal["es", "en"]
) -> Analysis:
    fixable = (
        analysis.classification is Classification.AUTO_FIXABLE
        and violation.error_code not in _NOT_CORRECTABLE
        and _has_valid_correction(analysis, violation)
    )
    action = analysis.suggested_human_action
    if not fixable and not (action and action.strip()):
        action = _HUMAN_ACTION[language]
    return Analysis(
        violation_id=analysis.violation_id,
        field=analysis.field,
        root_cause=analysis.root_cause,
        explanation=analysis.explanation[:EXPLANATION_MAX_LENGTH],
        severity=_at_least(analysis.severity, _SEVERITY_FLOOR[violation.error_code]),
        classification=(
            Classification.AUTO_FIXABLE if fixable else Classification.NEEDS_HUMAN_REVIEW
        ),
        confidence=analysis.confidence,
        correction=(
            Correction.model_validate(analysis.correction.model_dump())
            if fixable and analysis.correction
            else None
        ),
        suggested_human_action=action,
    )


def _has_valid_correction(analysis: LlmAnalysis, violation: Violation) -> bool:
    correction = analysis.correction
    return (
        correction is not None
        and correction.original_value == violation.received_value
        and (
            correction.proposed_value is not None or correction.operation is Operation.NULLIFY_TOKEN
        )
    )


def _at_least(severity: Severity, floor: Severity) -> Severity:
    return max(severity, floor, key=_SEVERITIES.index)


def cut_summary(summary: str) -> str:
    return summary[:SUMMARY_MAX_LENGTH]


def record_decision(analyses: list[Analysis]) -> Classification:
    if all(a.classification is Classification.AUTO_FIXABLE for a in analyses):
        return Classification.AUTO_FIXABLE
    return Classification.NEEDS_HUMAN_REVIEW


def max_severity(analyses: list[Analysis]) -> Severity:
    return max((a.severity for a in analyses), key=_SEVERITIES.index)


def is_cacheable(analysis: Analysis, violation: Violation, raw: dict[str, str | None]) -> bool:
    """AC-08.4 b and c: a cached text reappears on other rows, so it cannot quote this one.

    Plain containment on purpose: a short value such as "1" also discards a text with "2010", which
    only costs one more call. record.raw is keyed by header, so without sourceHeader the violation's
    own column counts as another one.
    """
    if violation.field == RECORD_FIELD:
        return False
    text = f"{analysis.explanation}\n{analysis.suggested_human_action or ''}".casefold()
    return not any(
        value.strip().casefold() in text
        for header, value in raw.items()
        if header != violation.source_header and value and value.strip()
    )
