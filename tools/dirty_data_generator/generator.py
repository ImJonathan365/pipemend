"""Pick rows, inject defects and write the dirty CSV with its labels (AC-19.1 to AC-19.4)."""

import csv
import random
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

import yaml

from dirty_data_generator.defects import (
    BROKEN_TYPES,
    BY_NAME,
    COLUMNS,
    COMBINED_FIXABLE,
    COMBINED_FIXABLE_PARTS,
    COMBINED_MIXED,
    DEFECTS,
    FIXABLE_TYPES,
    RECORD,
    Context,
    Defect,
    Row,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_DIR = REPO_ROOT / "pipeline-service" / "src" / "main" / "resources" / "schemas"
HEADERS = (
    "Invoice",
    "StockCode",
    "Description",
    "Quantity",
    "InvoiceDate",
    "Price",
    "Customer ID",
    "Country",
)
LABEL_HEADERS = (
    "source_row_number",
    "defect_type",
    "field",
    "clean_value",
    "dirty_value",
    "expected_error_code",
    "expected_outcome",
    "expected_operation",
)
FIXABLE_SHARE = 0.6
FIELD_ORDER = {name: position for position, name in enumerate((*COLUMNS, RECORD))}


@dataclass(frozen=True)
class Change:
    defect: Defect
    column: str
    dirty: str


@dataclass(frozen=True)
class Injection:
    defect_type: str
    changes: tuple[Change, ...]

    @property
    def outcome(self) -> str:
        fixable = all(change.defect.fixable for change in self.changes)
        return "AUTO_CORRECTED" if fixable else "QUARANTINED"


def load_context() -> Context:
    schema = yaml.safe_load((SCHEMA_DIR / "sales_transaction.v1.yaml").read_text(encoding="utf-8"))
    null_tokens = frozenset(schema["nullTokens"])
    lines = (SCHEMA_DIR / "countries.v1.txt").read_text(encoding="utf-8").splitlines()
    catalogue = frozenset(line for line in lines if line)
    document = yaml.safe_load((SCHEMA_DIR / "country-aliases.v1.yaml").read_text(encoding="utf-8"))
    aliases = {country: tuple(names) for country, names in document["aliases"].items()}
    for country, names in aliases.items():
        for alias in names:
            # An unquoted NO in YAML 1.1 loads as False; a valid or null alias would not be dirty.
            if (
                not isinstance(alias, str)
                or alias in catalogue
                or alias.strip(" \t") in null_tokens
            ):
                raise SystemExit(f"alias {alias!r} of {country!r} cannot be used as a synonym")
    return Context(
        null_tokens=tuple(token for token in schema["nullTokens"] if token),
        catalogue=catalogue,
        aliases=aliases,
    )


def read_rows(path: Path) -> list[Row]:
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.reader(handle)
        if tuple(next(reader)) != HEADERS:
            raise SystemExit(f"{path} does not carry the schema v1 headers")
        return [dict(zip(COLUMNS, cells, strict=True)) for cells in reader]


def _columns(defect: Defect, row: Row, ctx: Context) -> list[str]:
    return [column for column in defect.errors if defect.applies(row[column], row, ctx)]


def _mixed_pairs(row: Row, ctx: Context) -> Iterator[tuple[Defect, str, Defect, str]]:
    for fixable in (d for d in DEFECTS if d.fixable):
        for fixed_column in _columns(fixable, row, ctx):
            # A malformed row is never evaluated further, so it cannot carry a second defect.
            for broken in (d for d in DEFECTS if not d.fixable and not d.breaks_row):
                for broken_column in _columns(broken, row, ctx):
                    if fixed_column != broken_column and fixed_column not in broken.requires_valid:
                        yield fixable, fixed_column, broken, broken_column


def _applies(defect_type: str, row: Row, ctx: Context) -> bool:
    if defect_type == COMBINED_FIXABLE:
        return all(part.applies(row[column], row, ctx) for part, column in COMBINED_FIXABLE_PARTS)
    if defect_type == COMBINED_MIXED:
        return next(_mixed_pairs(row, ctx), None) is not None
    return bool(_columns(BY_NAME[defect_type], row, ctx))


def _inject(defect_type: str, row: Row, ctx: Context, rng: random.Random) -> Injection:
    parts: tuple[tuple[Defect, str], ...]
    if defect_type == COMBINED_FIXABLE:
        parts = COMBINED_FIXABLE_PARTS
    elif defect_type == COMBINED_MIXED:
        fixable, fixed_column, broken, broken_column = rng.choice(list(_mixed_pairs(row, ctx)))
        parts = ((fixable, fixed_column), (broken, broken_column))
    else:
        defect = BY_NAME[defect_type]
        parts = ((defect, rng.choice(_columns(defect, row, ctx))),)
    changes = tuple(Change(d, column, d.dirty(row[column], ctx, rng)) for d, column in parts)
    return Injection(defect_type, changes)


def _spread(types: list[str], count: int) -> list[str]:
    return [types[i % len(types)] for i in range(count)]


def select(
    rows: list[Row], ctx: Context, rng: random.Random, rate: float, defect_types: list[str]
) -> dict[int, Injection]:
    fixable = [t for t in FIXABLE_TYPES if t in defect_types]
    broken = [t for t in BROKEN_TYPES if t in defect_types]
    dirty_rows = round(rate * len(rows))
    if fixable and broken:
        fixable_rows = round(dirty_rows * FIXABLE_SHARE)
    else:
        fixable_rows = dirty_rows if fixable else 0
    # Types are spread evenly rather than drawn, so a 1k sample still covers the whole catalogue.
    plan = _spread(fixable, fixable_rows) + _spread(broken, dirty_rows - fixable_rows)
    rng.shuffle(plan)

    injections: dict[int, Injection] = {}
    for defect_type in plan:
        candidates = [
            index
            for index, row in enumerate(rows)
            if index not in injections and _applies(defect_type, row, ctx)
        ]
        if not candidates:
            raise SystemExit(f"no row left where {defect_type} can be injected")
        index = rng.choice(candidates)
        injections[index] = _inject(defect_type, rows[index], ctx, rng)
    return injections


def write(rows: list[Row], injections: dict[int, Injection], output: Path, labels: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(HEADERS)
        for index, row in enumerate(rows):
            values = dict(row)
            injection = injections.get(index)
            changes = injection.changes if injection else ()
            for change in changes:
                values[change.column] = change.dirty
            cells = [values[column] for column in COLUMNS]
            if any(change.defect.breaks_row for change in changes):
                # By hand: csv.writer would quote the comma that makes the row malformed.
                handle.write(",".join(cells) + "\n")
            else:
                writer.writerow(cells)

    with labels.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(LABEL_HEADERS)
        for index in sorted(injections):
            injection = injections[index]
            outcome = injection.outcome
            entries = sorted(
                injection.changes,
                key=lambda c: FIELD_ORDER[c.defect.label_field or c.column],
            )
            for change in entries:
                writer.writerow(
                    [
                        index + 1,
                        injection.defect_type,
                        change.defect.label_field or change.column,
                        rows[index][change.column],
                        change.dirty,
                        change.defect.errors[change.column],
                        outcome,
                        change.defect.operation if outcome == "AUTO_CORRECTED" else "",
                    ]
                )


def labels_path(output: Path) -> Path:
    return output.with_name(output.stem + ".labels.csv")


def run(source: Path, output: Path, rate: float, seed: int, defect_types: list[str]) -> None:
    rows = read_rows(source)
    injections = select(rows, load_context(), random.Random(seed), rate, defect_types)  # noqa: S311
    write(rows, injections, output, labels_path(output))
    print(f"  {output.name}: {len(rows)} rows, {len(injections)} with defects (seed {seed})")
