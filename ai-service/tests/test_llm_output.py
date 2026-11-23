"""The LLM -> ai-service boundary: anything invented or unknown is invalid output (docs/06)."""

from collections.abc import Iterator
from typing import Any

import pytest
from pydantic import ValidationError

from app.domain.llm_output import LlmTriageOutput
from app.services.triage_service import _OUTPUT_SCHEMA


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


def _with_parameters(parameters: Any) -> dict[str, Any]:
    body = _output()
    body["analyses"][0]["correction"]["parameters"] = parameters
    return body


@pytest.mark.parametrize(
    "parameters",
    [
        {},
        {"sourcePattern": "dd/MM/yyyy HH:mm"},
        {"decimalSeparator": ",", "groupingSeparator": None, "strippedSymbols": ["£"]},
        {"decimalSeparator": ",", "groupingSeparator": "."},
    ],
)
def test_the_parameters_of_docs_06_are_accepted(parameters: dict[str, Any]) -> None:
    output = LlmTriageOutput.model_validate(_with_parameters(parameters))

    correction = output.analyses[0].correction
    assert correction is not None
    assert correction.parameters == parameters


@pytest.mark.parametrize(
    "parameters",
    [
        {"note": "x"},
        {"sourcePattern": 3},
        {"decimalSeparator": None},
        {"strippedSymbols": "£"},
        {"strippedSymbols": [1]},
        None,
    ],
)
def test_parameters_with_an_unknown_key_or_type_are_invalid(parameters: Any) -> None:
    """D263: shape only; which keys each operation needs is for the verifiers."""
    with pytest.raises(ValidationError):
        LlmTriageOutput.model_validate(_with_parameters(parameters))


def _subschemas(node: Any, path: str = "$") -> Iterator[tuple[str, Any]]:
    yield path, node
    if isinstance(node, dict):
        for key, value in node.items():
            yield from _subschemas(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from _subschemas(value, f"{path}[{index}]")


_ANNOTATIONS = {"title", "description", "default", "examples"}


def _accepts_anything(node: Any) -> bool:
    """{} or {"title": ...}: Any and JsonValue, with or without the annotations Pydantic adds."""
    return (
        isinstance(node, dict)
        and set(node) <= _ANNOTATIONS
        and not any(isinstance(value, dict) for value in node.values())
    )


# Rejected by structured outputs on number and string; the API names only the first one (D266).
_UNSUPPORTED = {
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "multipleOf",
    "minLength",
    "maxLength",
}


def test_the_schema_sent_has_no_empty_subschema_and_closed_objects() -> None:
    """D264 and D266: the Anthropic API rejects {} and numeric or length limits (B7), and its
    structured outputs need additionalProperties false on every object."""
    nodes = list(_subschemas(_OUTPUT_SCHEMA))

    assert [path for path, node in nodes if _accepts_anything(node)] == []
    assert [
        f"{path}.{key}"
        for path, node in nodes
        if isinstance(node, dict) and node.get("type") in ("number", "integer", "string")
        for key in _UNSUPPORTED & set(node)
    ] == []
    objects = [(p, n) for p, n in nodes if isinstance(n, dict) and n.get("type") == "object"]
    assert objects
    assert [p for p, n in objects if n.get("additionalProperties") is not False] == []


def _with_confidence(confidence: Any) -> dict[str, Any]:
    body = _output()
    body["analyses"][0]["confidence"] = confidence
    return body


@pytest.mark.parametrize("confidence", [0, 1, 0.0, 1.0])
def test_confidence_at_the_ends_of_0_to_1_is_valid(confidence: float) -> None:
    assert (
        LlmTriageOutput.model_validate(_with_confidence(confidence)).analyses[0].confidence
        == confidence
    )


@pytest.mark.parametrize("confidence", [1.5, -0.1])
def test_confidence_outside_0_to_1_is_invalid(confidence: float) -> None:
    """D266: checked by a validator, as the schema sent carries no minimum or maximum."""
    with pytest.raises(ValidationError, match="confidence must be from 0 to 1"):
        LlmTriageOutput.model_validate(_with_confidence(confidence))
