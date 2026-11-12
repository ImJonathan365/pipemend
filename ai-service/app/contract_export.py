"""Writes contracts/ai-service.openapi.json: `uv run python -m app.contract_export`."""

import json
from pathlib import Path
from typing import Any

from app.main import create_app

SPEC_PATH = Path(__file__).resolve().parents[2] / "contracts" / "ai-service.openapi.json"


def build_spec() -> dict[str, Any]:
    return create_app().openapi()


def render() -> str:
    return json.dumps(build_spec(), indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def main() -> None:
    SPEC_PATH.write_text(render(), encoding="utf-8")


if __name__ == "__main__":
    main()
