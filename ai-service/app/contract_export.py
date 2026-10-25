"""Writes contracts/ai-service.openapi.json: `uv run python -m app.contract_export`."""

import json
from pathlib import Path
from typing import Any

from fastapi.openapi.constants import REF_TEMPLATE
from pydantic.json_schema import models_json_schema

from app.domain.contracts import TriageRequest, TriageResponse
from app.main import create_app

SPEC_PATH = Path(__file__).resolve().parents[2] / "contracts" / "ai-service.openapi.json"


def build_spec() -> dict[str, Any]:
    """The app's own OpenAPI plus the triage models, which no route uses yet.

    The models are generated as FastAPI 0.141.1 will once POST /v1/triage exists
    (fastapi/_compat/v2.py, get_definitions): the request in validation mode, the response in
    serialization mode, REF_TEMPLATE, and descriptions cut at the first form feed.
    """
    spec = create_app().openapi()
    _, definitions = models_json_schema(
        [(TriageRequest, "validation"), (TriageResponse, "serialization")],
        ref_template=REF_TEMPLATE,
    )
    schemas = spec.setdefault("components", {}).setdefault("schemas", {})
    for name, schema in definitions.get("$defs", {}).items():
        # Pydantic only adds these suffixes when a model is used in both modes; FastAPI would
        # then name it differently from this export.
        if name.endswith(("-Input", "-Output")):
            raise ValueError(f"contract model {name} differs between request and response")
        if "description" in schema:
            schema["description"] = schema["description"].split("\f")[0]
        if schemas.get(name, schema) != schema:
            raise ValueError(f"schema {name} already exists with another definition")
        schemas[name] = schema
    return spec


def render() -> str:
    return json.dumps(build_spec(), indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def main() -> None:
    SPEC_PATH.write_text(render(), encoding="utf-8")


if __name__ == "__main__":
    main()
