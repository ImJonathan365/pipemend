import json

from app.contract_export import SPEC_PATH, build_spec, render


def test_the_committed_spec_matches_the_contract_models() -> None:
    assert SPEC_PATH.read_text(encoding="utf-8") == render(), (
        "contracts/ai-service.openapi.json is out of date: "
        "run `uv run python -m app.contract_export` in ai-service"
    )


def test_the_export_is_deterministic() -> None:
    assert render() == render()


def test_the_triage_models_keep_the_names_fastapi_will_give_them() -> None:
    schemas = build_spec()["components"]["schemas"]

    assert {"TriageRequest", "TriageResponse"} <= schemas.keys()
    assert not [name for name in schemas if name.endswith(("-Input", "-Output"))]


def test_the_request_is_closed_and_the_response_is_open() -> None:
    """docs/06: unknown request fields are a 422; unknown response fields are compatible."""
    schemas = json.loads(render())["components"]["schemas"]

    assert schemas["TriageRequest"]["additionalProperties"] is False
    assert "additionalProperties" not in schemas["TriageResponse"]
