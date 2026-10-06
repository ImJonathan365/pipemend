import csv
import random
from collections import Counter, defaultdict
from decimal import Decimal
from pathlib import Path

import pytest

from dirty_data_generator.defects import ALL_TYPES, BROKEN_TYPES, COLUMNS, RECORD
from dirty_data_generator.generator import (
    REPO_ROOT,
    labels_path,
    load_context,
    read_rows,
    run,
    select,
)

CLEAN_1K = REPO_ROOT / "data" / "samples" / "clean-1k.csv"
RECORD_COLUMN = {"BUSINESS_RULE_VIOLATION": "quantity", "MALFORMED_ROW": "description"}

Label = dict[str, str]


def _generate(directory: Path, seed: int = 42, defects: list[str] = ALL_TYPES) -> Path:
    output = directory / "dirty.csv"
    run(CLEAN_1K, output, 0.05, seed, defects)
    return output


def _labels(output: Path) -> dict[int, list[Label]]:
    by_row: dict[int, list[Label]] = defaultdict(list)
    with labels_path(output).open(encoding="utf-8", newline="") as handle:
        for label in csv.DictReader(handle):
            by_row[int(label["source_row_number"])].append(label)
    return by_row


def _column(label: Label) -> str:
    if label["field"] == RECORD:
        return RECORD_COLUMN[label["expected_error_code"]]
    return label["field"]


@pytest.fixture(scope="module")
def output(tmp_path_factory: pytest.TempPathFactory) -> Path:
    return _generate(tmp_path_factory.mktemp("default"))


@pytest.fixture(scope="module")
def labels(output: Path) -> dict[int, list[Label]]:
    return _labels(output)


def _all(labels: dict[int, list[Label]]) -> list[Label]:
    return [label for entries in labels.values() for label in entries]


def test_ac_19_3_same_seed_gives_identical_bytes(output: Path, tmp_path: Path) -> None:
    again = _generate(tmp_path)
    assert again.read_bytes() == output.read_bytes()
    assert labels_path(again).read_bytes() == labels_path(output).read_bytes()


def test_ac_19_3_another_seed_changes_the_output(output: Path, tmp_path: Path) -> None:
    assert _generate(tmp_path, seed=7).read_bytes() != output.read_bytes()


def test_ac_19_3_committed_dirty_1k_matches_the_generator(output: Path) -> None:
    committed = REPO_ROOT / "data" / "samples" / "dirty-1k.csv"
    assert committed.read_bytes() == output.read_bytes()
    assert labels_path(committed).read_bytes() == labels_path(output).read_bytes()


def test_ac_19_1_rate_and_fixable_share(labels: dict[int, list[Label]]) -> None:
    assert len(labels) == 50
    corrected = [
        row for row, entries in labels.items() if entries[0]["expected_outcome"] == "AUTO_CORRECTED"
    ]
    assert len(corrected) == 30


def test_every_defect_type_is_present(labels: dict[int, list[Label]]) -> None:
    assert {label["defect_type"] for label in _all(labels)} == set(ALL_TYPES)


def test_ac_19_4_rows_change_exactly_in_their_labelled_fields(
    output: Path, labels: dict[int, list[Label]]
) -> None:
    clean = read_rows(CLEAN_1K)
    with output.open(encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        next(reader)
        dirty_rows = list(reader)
    assert len(dirty_rows) == len(clean)

    for number, (cells, clean_row) in enumerate(zip(dirty_rows, clean, strict=True), start=1):
        entries = labels.get(number, [])
        if len(cells) == len(COLUMNS) + 1:
            assert [e["defect_type"] for e in entries] == ["COLUMN_SHIFT"]
            cells = [*cells[:2], cells[2] + "," + cells[3], *cells[4:]]
        dirty_row = dict(zip(COLUMNS, cells, strict=True))
        changed = {column for column in COLUMNS if dirty_row[column] != clean_row[column]}
        labelled = [_column(entry) for entry in entries]
        assert len(labelled) == len(set(labelled)), f"row {number}: two defects on one field"
        assert changed == set(labelled), f"row {number}"
        for entry in entries:
            assert entry["clean_value"] == clean_row[_column(entry)]
            assert entry["dirty_value"] == dirty_row[_column(entry)]


def test_one_outcome_per_row_and_operation_only_when_corrected(
    labels: dict[int, list[Label]],
) -> None:
    for entries in labels.values():
        outcomes = {entry["expected_outcome"] for entry in entries}
        assert len(outcomes) == 1
        corrected = outcomes == {"AUTO_CORRECTED"}
        assert all((entry["expected_operation"] != "") == corrected for entry in entries)
        assert (entries[0]["defect_type"] in BROKEN_TYPES) == (not corrected)


def test_expected_error_codes(labels: dict[int, list[Label]]) -> None:
    expected = {
        "CASE": {"INVALID_ENUM_VALUE"},
        "WHITESPACE": {"PATTERN_MISMATCH", "INVALID_ENUM_VALUE"},
        "DATE_DMY_UNAMBIGUOUS": {"INVALID_FORMAT"},
        "DATE_ISO_T": {"INVALID_FORMAT"},
        "DATE_AMBIGUOUS": {"INVALID_FORMAT"},
        "DECIMAL_COMMA": {"INVALID_FORMAT"},
        "CURRENCY_SYMBOL": {"INVALID_FORMAT"},
        "FLOAT_ID": {"PATTERN_MISMATCH"},
        "COUNTRY_SYNONYM": {"INVALID_ENUM_VALUE"},
        "NULL_TOKEN_OPTIONAL": {"PATTERN_MISMATCH"},
        "NULL_TOKEN_REQUIRED": {"MISSING_REQUIRED_FIELD"},
        "MISSING_REQUIRED": {"MISSING_REQUIRED_FIELD"},
        "NEGATIVE_PRICE": {"OUT_OF_RANGE"},
        "QUANTITY_OUTLIER": {"OUT_OF_RANGE"},
        "GARBAGE_NUMBER": {"INVALID_TYPE"},
        "FUTURE_DATE": {"OUT_OF_RANGE"},
        "CANCEL_SIGN_MISMATCH": {"BUSINESS_RULE_VIOLATION"},
        "COLUMN_SHIFT": {"MALFORMED_ROW"},
    }
    for label in _all(labels):
        if label["defect_type"] in expected:
            assert label["expected_error_code"] in expected[label["defect_type"]], label


def test_ac_19_4_preconditions(labels: dict[int, list[Label]]) -> None:
    aliases = load_context().aliases
    for label in _all(labels):
        kind, clean, dirty = label["defect_type"], label["clean_value"], label["dirty_value"]
        day, month = (
            (int(clean[8:10]), int(clean[5:7])) if label["field"] == "invoice_date" else (0, 0)
        )
        if kind == "DATE_DMY_UNAMBIGUOUS" or (kind == "COMBINED_FIXABLE" and day):
            assert day > 12
        if kind == "DATE_ISO_T":
            assert day > 12 or day == month
        if kind == "DATE_AMBIGUOUS":
            assert day <= 12
            assert day != month
        if kind == "FLOAT_ID":
            assert clean != ""
        if kind == "NULL_TOKEN_OPTIONAL":
            assert clean == ""
        if label["expected_operation"] == "MAP_TO_ENUM":
            assert dirty in aliases[clean]
        if kind == "NEGATIVE_PRICE":
            assert Decimal(clean) > 0


def test_ac_19_4_whitespace_is_only_space_and_tab(labels: dict[int, list[Label]]) -> None:
    for label in _all(labels):
        if label["expected_operation"] == "TRIM":
            dirty = label["dirty_value"]
            assert dirty.strip(" \t") == label["clean_value"]
            assert dirty != label["clean_value"]
            assert set(dirty) - set(label["clean_value"]) <= {" ", "\t"}


def test_mixed_rows_keep_business_rules_evaluable(labels: dict[int, list[Label]]) -> None:
    for entries in labels.values():
        codes = {entry["expected_error_code"] for entry in entries}
        if "BUSINESS_RULE_VIOLATION" in codes:
            assert not {e["field"] for e in entries} & {"invoice_no", "quantity"}
        if "MALFORMED_ROW" in codes:
            assert len(entries) == 1


def test_defects_option_restricts_the_catalogue(tmp_path: Path) -> None:
    output = _generate(tmp_path, defects=["CASE", "FUTURE_DATE"])
    kinds = Counter(label["defect_type"] for label in _all(_labels(output)))
    assert kinds == Counter({"CASE": 30, "FUTURE_DATE": 20})


def test_missing_candidates_fail_loudly() -> None:
    rows = [dict(row, unit_price="0") for row in read_rows(CLEAN_1K)[:2]]
    with pytest.raises(SystemExit, match="NEGATIVE_PRICE"):
        select(rows, load_context(), random.Random(1), 1.0, ["NEGATIVE_PRICE"])  # noqa: S311
