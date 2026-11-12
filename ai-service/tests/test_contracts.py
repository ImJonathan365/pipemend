"""The contract models against the hand-written fixtures of contracts/fixtures (docs/06 A.1).

Fixtures marked `"pydantic": "accepts"` with an AI_INVALID_RESPONSE expectation break a rule that
needs the request (AC-08.7); the pipeline's validator rejects them, not these models.
"""

import json
from pathlib import Path
from typing import Any

import pytest
from pydantic import ValidationError

from app.domain.contracts import TriageRequest, TriageResponse

FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"
INDEX: list[dict[str, Any]] = json.loads((FIXTURES / "index.json").read_text(encoding="utf-8"))
MODELS = {"request": TriageRequest, "response": TriageResponse}


def _load(name: str) -> Any:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def test_every_fixture_is_listed_in_the_index() -> None:
    on_disk = {path.name for path in FIXTURES.glob("*.json")} - {"index.json"}

    assert on_disk == {entry["fixture"] for entry in INDEX}


@pytest.mark.parametrize("entry", INDEX, ids=lambda entry: entry["fixture"])
def test_each_fixture_is_accepted_or_rejected_as_the_index_says(entry: dict[str, Any]) -> None:
    model = MODELS[entry["kind"]]
    body = _load(entry["fixture"])

    if entry["pydantic"] == "accepts":
        model.model_validate(body)
    else:
        with pytest.raises(ValidationError) as rejected:
            model.model_validate(body)
        # Each invalid fixture breaks one rule only, so it cannot pass for the wrong reason.
        assert rejected.value.error_count() == 1


def test_every_response_fixture_names_a_valid_request_fixture() -> None:
    valid_requests = {
        e["fixture"] for e in INDEX if e["kind"] == "request" and e["expected"] == "valid"
    }

    assert all(e["request"] in valid_requests for e in INDEX if e["kind"] == "response")


@pytest.mark.parametrize(
    "name",
    [e["fixture"] for e in INDEX if e["kind"] == "request" and e["expected"] == "valid"],
)
def test_a_valid_request_round_trips_with_its_camel_case_names(name: str) -> None:
    body = _load(name)

    parsed = TriageRequest.model_validate(body)

    assert parsed.model_dump(mode="json", by_alias=True, exclude_unset=True) == body


def test_the_response_ignores_unknown_fields_but_the_request_rejects_them() -> None:
    """docs/09 7.5 and docs/06: the two directions of the wire treat extras differently."""
    response = _load("triage-response.unknown-field.json")
    request = _load("triage-request.invalid.unknown-field.json")

    TriageResponse.model_validate(response)
    with pytest.raises(ValidationError, match="priority"):
        TriageRequest.model_validate(request)


def test_a_raw_line_longer_than_the_limit_is_rejected() -> None:
    """AC-07.4: the pipeline truncates _rawLine to 2 000 characters before sending it."""
    body = _load("triage-request.malformed-row.json")
    body["record"]["raw"]["_rawLine"] = "x" * 2001

    with pytest.raises(ValidationError, match="_rawLine"):
        TriageRequest.model_validate(body)


def test_a_needs_human_review_analysis_requires_a_suggested_action() -> None:
    body = _load("triage-response.needs-human-review.json")
    body["analyses"][0]["suggestedHumanAction"] = None

    with pytest.raises(ValidationError, match="suggestedHumanAction"):
        TriageResponse.model_validate(body)
