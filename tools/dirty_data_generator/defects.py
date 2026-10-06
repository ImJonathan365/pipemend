"""Single-field defects of docs/10 section 3, each with the preconditions of AC-19.4."""

import random
from collections.abc import Callable
from dataclasses import dataclass, field
from decimal import Decimal

COLUMNS = (
    "invoice_no",
    "stock_code",
    "description",
    "quantity",
    "invoice_date",
    "unit_price",
    "customer_id",
    "country",
)
RECORD = "_record"

Row = dict[str, str]


@dataclass(frozen=True)
class Context:
    null_tokens: tuple[str, ...]
    catalogue: frozenset[str]
    aliases: dict[str, tuple[str, ...]]


@dataclass(frozen=True)
class Defect:
    name: str
    errors: dict[str, str]
    operation: str
    applies: Callable[[str, Row, Context], bool]
    dirty: Callable[[str, Context, random.Random], str]
    label_field: str | None = None
    # Other columns that must stay valid for the expected violation to fire (AC-06.3).
    requires_valid: frozenset[str] = field(default_factory=frozenset)
    breaks_row: bool = False

    @property
    def fixable(self) -> bool:
        return self.operation != ""


def _always(_value: str, _row: Row, _ctx: Context) -> bool:
    return True


def _day(value: str) -> int:
    return int(value[8:10])


def _month(value: str) -> int:
    return int(value[5:7])


def _dmy(value: str, _ctx: Context, _rng: random.Random) -> str:
    return f"{value[8:10]}/{value[5:7]}/{value[0:4]} {value[11:16]}"


_PADS = ("", " ", "  ", "\t", " \t")
_PAD_PAIRS = [(left, right) for left in _PADS for right in _PADS if left or right]


def _pad(value: str, _ctx: Context, rng: random.Random) -> str:
    left, right = rng.choice(_PAD_PAIRS)
    return left + value + right


def _needs_quoting(row: Row) -> bool:
    return any(char in value for value in row.values() for char in ',"\r\n')


DEFECTS = [
    Defect(
        "WHITESPACE",
        {
            "stock_code": "PATTERN_MISMATCH",
            "invoice_no": "PATTERN_MISMATCH",
            "country": "INVALID_ENUM_VALUE",
        },
        "TRIM",
        _always,
        _pad,
    ),
    Defect(
        "CASE",
        {"country": "INVALID_ENUM_VALUE"},
        "NORMALIZE_CASE",
        lambda value, _row, _ctx: value.lower() != value,
        lambda value, _ctx, _rng: value.lower(),
    ),
    Defect(
        "DATE_DMY_UNAMBIGUOUS",
        {"invoice_date": "INVALID_FORMAT"},
        "PARSE_DATE",
        # The dirty form drops the seconds, which PARSE_DATE restores as :00.
        lambda value, _row, _ctx: _day(value) > 12 and value.endswith(":00"),
        _dmy,
    ),
    Defect(
        "DATE_ISO_T",
        {"invoice_date": "INVALID_FORMAT"},
        "PARSE_DATE",
        # Otherwise yyyy-dd-MM also parses and the verifier rejects it as AMBIGUOUS_DATE (P1).
        lambda value, _row, _ctx: _day(value) > 12 or _day(value) == _month(value),
        lambda value, _ctx, _rng: value.replace(" ", "T"),
    ),
    Defect(
        "DATE_AMBIGUOUS",
        {"invoice_date": "INVALID_FORMAT"},
        "",
        lambda value, _row, _ctx: (
            _day(value) <= 12 and _day(value) != _month(value) and value.endswith(":00")
        ),
        _dmy,
    ),
    Defect(
        "DECIMAL_COMMA",
        {"unit_price": "INVALID_FORMAT"},
        "PARSE_NUMBER",
        lambda value, _row, _ctx: "." in value,
        lambda value, _ctx, _rng: value.replace(".", ","),
    ),
    Defect(
        "CURRENCY_SYMBOL",
        {"unit_price": "INVALID_FORMAT"},
        "PARSE_NUMBER",
        _always,
        lambda value, _ctx, _rng: "£" + value,
    ),
    Defect(
        "FLOAT_ID",
        {"customer_id": "PATTERN_MISMATCH"},
        "PARSE_NUMBER",
        lambda value, _row, _ctx: value != "",
        lambda value, _ctx, _rng: value + ".0",
    ),
    Defect(
        "COUNTRY_SYNONYM",
        {"country": "INVALID_ENUM_VALUE"},
        "MAP_TO_ENUM",
        lambda value, _row, ctx: value in ctx.aliases,
        lambda value, ctx, rng: rng.choice(ctx.aliases[value]),
    ),
    Defect(
        "NULL_TOKEN_OPTIONAL",
        {"customer_id": "PATTERN_MISMATCH"},
        "NULLIFY_TOKEN",
        # Only on rows without a customer, so the expected null equals clean_value (P2, AC-20.3).
        lambda value, _row, _ctx: value == "",
        lambda _value, ctx, rng: rng.choice(ctx.null_tokens),
    ),
    Defect(
        "NULL_TOKEN_REQUIRED",
        {"unit_price": "MISSING_REQUIRED_FIELD", "quantity": "MISSING_REQUIRED_FIELD"},
        "",
        _always,
        lambda _value, ctx, rng: rng.choice(ctx.null_tokens),
    ),
    Defect(
        "MISSING_REQUIRED",
        {
            "unit_price": "MISSING_REQUIRED_FIELD",
            "invoice_date": "MISSING_REQUIRED_FIELD",
            "country": "MISSING_REQUIRED_FIELD",
        },
        "",
        _always,
        lambda _value, _ctx, _rng: "",
    ),
    Defect(
        "NEGATIVE_PRICE",
        {"unit_price": "OUT_OF_RANGE"},
        "",
        # "-0" would still be a valid price.
        lambda value, _row, _ctx: Decimal(value) > 0,
        lambda value, _ctx, _rng: "-" + value,
    ),
    Defect(
        "QUANTITY_OUTLIER",
        {"quantity": "OUT_OF_RANGE"},
        "",
        _always,
        lambda value, _ctx, _rng: "-999999" if value.startswith("-") else "999999",
    ),
    Defect(
        "GARBAGE_NUMBER",
        {"quantity": "INVALID_TYPE"},
        "",
        _always,
        lambda value, _ctx, rng: rng.choice([value + "x", "--" + value.lstrip("-")]),
    ),
    Defect(
        "FUTURE_DATE",
        {"invoice_date": "OUT_OF_RANGE"},
        "",
        # 2099 is not a leap year.
        lambda value, _row, _ctx: value[5:10] != "02-29",
        lambda value, _ctx, _rng: "2099" + value[4:],
    ),
    Defect(
        "CANCEL_SIGN_MISMATCH",
        {"quantity": "BUSINESS_RULE_VIOLATION"},
        "",
        _always,
        lambda value, _ctx, _rng: str(-int(value)),
        label_field=RECORD,
        requires_valid=frozenset({"invoice_no"}),
    ),
    Defect(
        "COLUMN_SHIFT",
        {"description": "MALFORMED_ROW"},
        "",
        lambda value, row, _ctx: " " in value and not _needs_quoting(row),
        lambda value, _ctx, _rng: value.replace(" ", ",", 1),
        label_field=RECORD,
        breaks_row=True,
    ),
]
BY_NAME = {defect.name: defect for defect in DEFECTS}

COMBINED_FIXABLE = "COMBINED_FIXABLE"
COMBINED_FIXABLE_PARTS = (
    (BY_NAME["DATE_DMY_UNAMBIGUOUS"], "invoice_date"),
    (BY_NAME["CURRENCY_SYMBOL"], "unit_price"),
    (BY_NAME["COUNTRY_SYNONYM"], "country"),
)
COMBINED_MIXED = "COMBINED_MIXED"

FIXABLE_TYPES = [d.name for d in DEFECTS if d.fixable] + [COMBINED_FIXABLE]
BROKEN_TYPES = [d.name for d in DEFECTS if not d.fixable] + [COMBINED_MIXED]
ALL_TYPES = FIXABLE_TYPES + BROKEN_TYPES
