"""The LLM -> ai-service boundary: anything invented or unknown is invalid output (docs/06)."""

from typing import Any

import pytest
from pydantic import ValidationError

from app.domain.llm_output import LlmTriageOutput


def _output() -> dict[str, Any]:
    return {
        "summary": "s",
        "analyses": [
            {
                "violationId": "v1",
                "field": "unit_price",
                "rootCause": "LOCALE_FORMAT",
                "explanation": "e",
                "severity": "LOW",
                "classification": "AUTO_FIXABLE",
                "confidence": 0.95,
                "correction": {
                    "operation": "PARSE_NUMBER",
                    "originalValue": "2,55",
                    "proposedValue": "2.55",
                    "parameters": {"decimalSeparator": ","},
                },
                "suggestedHumanAction": None,
            }
        ],
    }


def test_a_well_formed_output_is_accepted() -> None:
    assert LlmTriageOutput.model_validate(_output()).analyses[0].confidence == 0.95


@pytest.mark.parametrize("field", ["recordDecision", "maxSeverity", "requestId", "meta", "extra"])
def test_a_field_invented_at_the_top_is_invalid(field: str) -> None:
    with pytest.raises(ValidationError):
        LlmTriageOutput.model_validate(_output() | {field: "x"})


def test_a_field_invented_inside_an_analysis_is_invalid() -> None:
    body = _output()
    body["analyses"][0]["reasoning"] = "x"

    with pytest.raises(ValidationError):
        LlmTriageOutput.model_validate(body)


def test_a_field_invented_inside_a_correction_is_invalid() -> None:
    body = _output()
    body["analyses"][0]["correction"]["note"] = "x"

    with pytest.raises(ValidationError):
        LlmTriageOutput.model_validate(body)


def test_snake_case_is_an_invented_field() -> None:
    body = _output()
    body["analyses"][0]["violation_id"] = body["analyses"][0].pop("violationId")

    with pytest.raises(ValidationError):
        LlmTriageOutput.model_validate(body)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("rootCause", "COSMIC_RAY"),
        ("severity", "SEVERE"),
        ("classification", "MAYBE"),
        ("confidence", "0.97"),
        ("confidence", True),
        ("confidence", 1.01),
        ("confidence", -0.01),
    ],
)
def test_an_unknown_enum_or_a_bad_confidence_is_invalid(field: str, value: object) -> None:
    body = _output()
    body["analyses"][0][field] = value

    with pytest.raises(ValidationError):
        LlmTriageOutput.model_validate(body)


def test_an_operation_outside_the_allowlist_is_invalid() -> None:
    """docs/06 rule 4. RENAME_COLUMN belongs to schema drift only."""
    body = _output()
    body["analyses"][0]["correction"]["operation"] = "RENAME_COLUMN"

    with pytest.raises(ValidationError):
        LlmTriageOutput.model_validate(body)


@pytest.mark.parametrize("field", ["correction", "suggestedHumanAction", "confidence", "field"])
def test_a_missing_field_is_invalid_even_if_it_may_be_null(field: str) -> None:
    body = _output()
    del body["analyses"][0][field]

    with pytest.raises(ValidationError):
        LlmTriageOutput.model_validate(body)


def test_a_fixable_analysis_without_correction_is_left_for_the_rules() -> None:
    """docs/06 rule 3 downgrades it to a human; rejecting it here would be a 502."""
    body = _output()
    body["analyses"][0]["correction"] = None

    assert LlmTriageOutput.model_validate(body).analyses[0].correction is None


def test_long_texts_are_left_to_be_cut() -> None:
    """docs/06 rule 5: cutting a text is not a contract error."""
    body = _output()
    body["summary"] = "x" * 5000
    body["analyses"][0]["explanation"] = "x" * 5000

    assert len(LlmTriageOutput.model_validate(body).summary) == 5000


def test_the_schema_given_to_the_llm_closes_every_object() -> None:
    schema = LlmTriageOutput.model_json_schema()

    assert schema["additionalProperties"] is False
    assert schema["$defs"]["LlmAnalysis"]["additionalProperties"] is False
    assert schema["$defs"]["LlmCorrection"]["additionalProperties"] is False
    assert "violationId" in schema["$defs"]["LlmAnalysis"]["properties"]
