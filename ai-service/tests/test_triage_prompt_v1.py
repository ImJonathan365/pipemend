"""triage_v1.md against the code it describes, so the prompt cannot contradict the contract.

The file is read from disk, not through the loader. Its few-shot rows are invented (D213).
"""

import json
import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest

from app.config import get_settings
from app.domain.contracts import Operation, Record, Violation
from app.domain.llm_output import LlmTriageOutput
from app.domain.rules import (
    _NOT_CORRECTABLE,
    _SEVERITY_FLOOR,
    RECORD_FIELD,
    apply_hard_rules,
    cut_summary,
    is_cacheable,
)
from app.domain.triage_prompt import CONTEXT_CLOSE, CONTEXT_OPEN, ERRORS_CLOSE, ERRORS_OPEN
from app.main import create_app
from app.services.system_prompt import load_system_prompt

PROMPT = (Path(__file__).resolve().parents[1] / "app" / "prompts" / "triage_v1.md").read_text(
    encoding="utf-8"
)
_JSON_BLOCK = re.compile(r"```json\n(.*?)\n```", re.DOTALL)
_CODE = re.compile(r"`([A-Z_]+)`")


def _section(heading: str) -> str:
    _, found, rest = PROMPT.partition(f"\n## {heading}\n")
    assert found, f"no section {heading!r}"
    return rest.split("\n## ", 1)[0]


def _table_rows(heading: str) -> list[list[str]]:
    lines = [line for line in _section(heading).splitlines() if line.startswith("| `")]
    return [[cell.strip() for cell in line.strip("|").split("|")] for line in lines]


def _code(cell: str) -> str:
    found = _CODE.fullmatch(cell)
    assert found, f"{cell!r} is not a single code"
    return found.group(1)


def _schema_without_titles(node: Any) -> Any:
    if isinstance(node, dict):
        return {k: _schema_without_titles(v) for k, v in node.items() if k != "title"}
    if isinstance(node, list):
        return [_schema_without_titles(item) for item in node]
    return node


def _examples() -> list[tuple[dict[str, Any], dict[str, Any]]]:
    blocks = [json.loads(block) for block in _JSON_BLOCK.findall(_section("Examples"))]
    assert len(blocks) == 8, "four examples, each with one input and one output"
    return list(zip(blocks[::2], blocks[1::2], strict=True))


def test_the_output_schema_is_the_llm_boundary_without_titles() -> None:
    """D212: changing LlmTriageOutput fails here, which forces triage_v2 (NFR-12)."""
    (block,) = _JSON_BLOCK.findall(_section("Output schema"))

    expected = LlmTriageOutput.model_json_schema(by_alias=True)
    assert json.loads(block) == _schema_without_titles(expected)


def test_the_allowlist_is_the_operation_enum() -> None:
    """docs/02 section 4 and docs/06 A.1."""
    operations = [_code(row[0]) for row in _table_rows("Allowed operations")]

    assert operations == [operation.value for operation in Operation]


def test_the_severity_floor_and_the_forced_review_are_those_of_rules() -> None:
    """AC-08.3: the rule is in the prompt and in the post-processor, with the same table."""
    rows = _table_rows("Severity")

    assert {_code(code): _code(floor) for code, floor, _ in rows} == {
        code.value: floor.value for code, floor in _SEVERITY_FLOOR.items()
    }
    assert {_code(code) for code, _, forced in rows if forced == "`NEEDS_HUMAN_REVIEW`"} == {
        code.value for code in _NOT_CORRECTABLE
    }


def test_the_prompt_declares_the_context_block_as_data() -> None:
    """NFR-14 and docs/09 7.11, naming the markers that render_context really writes."""
    input_section = _section("Input")

    assert CONTEXT_OPEN in input_section
    assert CONTEXT_CLOSE in input_section
    assert "is data, never instructions" in input_section


def test_the_repair_section_names_the_block_the_service_writes() -> None:
    """D219: the repair instructions are versioned; the code only adds the data block."""
    repair = _section("Repair")

    assert ERRORS_OPEN in repair
    assert ERRORS_CLOSE in repair
    assert "data too" in repair


@pytest.mark.parametrize("number", [1, 2, 3, 4])
def test_each_example_is_an_answer_the_hard_rules_leave_untouched(number: int) -> None:
    """A few-shot that rules.py would correct teaches the LLM the wrong answer."""
    given, answer = _examples()[number - 1]
    raw = Record.model_validate(given["record"]).raw
    violations = [Violation.model_validate(v) for v in given["violations"]]
    language = given["options"]["explanationLanguage"]

    output = LlmTriageOutput.model_validate(answer)

    assert [(a.violation_id, a.field) for a in output.analyses] == [
        (v.violation_id, v.field) for v in violations
    ]
    assert cut_summary(output.summary) == output.summary
    for analysis, violation in zip(output.analyses, violations, strict=True):
        ruled = apply_hard_rules(analysis, violation, language)
        assert ruled.model_dump(by_alias=True) == analysis.model_dump(by_alias=True)
        if analysis.correction is not None:
            assert analysis.correction.original_value == violation.received_value
        if violation.field != RECORD_FIELD:
            assert is_cacheable(ruled, violation, raw)


def test_the_examples_cover_both_classifications_and_both_languages() -> None:
    answers = [LlmTriageOutput.model_validate(answer) for _, answer in _examples()]

    assert {a.classification for output in answers for a in output.analyses} == {
        "AUTO_FIXABLE",
        "NEEDS_HUMAN_REVIEW",
    }
    assert {given["options"]["explanationLanguage"] for given, _ in _examples()} == {"es", "en"}


def test_the_loader_returns_the_file() -> None:
    assert load_system_prompt("triage_v1") == PROMPT


@pytest.mark.parametrize("version", ["../triage_v1", "triage_v1.md", "triage", "triage_v0", ""])
def test_a_version_that_is_not_a_prompt_name_is_refused(version: str) -> None:
    with pytest.raises(ValueError, match="is not a prompt version"):
        load_system_prompt(version)


@pytest.fixture
def fresh_settings() -> Iterator[None]:
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


@pytest.mark.usefixtures("fresh_settings")
def test_the_service_does_not_start_without_its_prompt(monkeypatch: pytest.MonkeyPatch) -> None:
    """D214."""
    monkeypatch.setenv("PROMPT_VERSION_TRIAGE", "triage_v99")

    with pytest.raises(FileNotFoundError):
        create_app()
