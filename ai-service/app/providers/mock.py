"""Deterministic LLM stand-in that needs no API key and no network (NFR-02, AC-17.2).

It reads the request from the prompt and answers one analysis per violation, without looking at
`record.raw`: field-level analyses are cached across rows (AC-08.4 c), and that also keeps text
injected in a free-text column out of the decision. Whatever it cannot resolve goes to a human.
"""

import re
from datetime import date, time
from enum import StrEnum
from typing import Any, NamedTuple

from app.domain.contracts import (
    Classification,
    ErrorCode,
    Expected,
    Operation,
    RootCause,
    Severity,
    Violation,
)
from app.domain.triage_prompt import read_context
from app.providers.base import ProviderResult

# The only knowledge beyond the request (AC-17.2), standing in for what an LLM already knows.
# Copied, never read at runtime: a test compares them with the frozen YAML files.
NULL_TOKENS = ("", "N/A", "NA", "null", "NULL", "None", "-", "?")
COUNTRY_ALIASES = {
    "Australia": ("AU", "AUS"),
    "Austria": ("AT", "AUT", "Österreich"),
    "Bahrain": ("BH", "BHR"),
    "Belgium": ("BE", "BEL", "Belgique", "België"),
    "Bermuda": ("BM", "BMU"),
    "Brazil": ("BR", "BRA", "Brasil"),
    "Canada": ("CA", "CAN"),
    "Channel Islands": ("Channel Isles",),
    "Cyprus": ("CY", "CYP", "Kypros"),
    "Czech Republic": ("CZ", "CZE", "Czechia", "Cesko"),
    "Denmark": ("DK", "DNK", "Danmark"),
    "EIRE": ("IE", "IRL", "Ireland", "Republic of Ireland"),
    "Finland": ("FI", "FIN", "Suomi"),
    "France": ("FR", "FRA"),
    "Germany": ("DE", "DEU", "Deutschland"),
    "Greece": ("GR", "GRC", "Hellas", "Ellada"),
    "Hong Kong": ("HK", "HKG"),
    "Iceland": ("IS", "ISL", "Ísland"),
    "Israel": ("IL", "ISR"),
    "Italy": ("IT", "ITA", "Italia"),
    "Japan": ("JP", "JPN", "Nippon"),
    "Korea": ("KR", "KOR", "South Korea", "Republic of Korea"),
    "Lebanon": ("LB", "LBN", "Liban"),
    "Lithuania": ("LT", "LTU", "Lietuva"),
    "Malta": ("MT", "MLT"),
    "Netherlands": ("NL", "NLD", "Nederland", "Holland", "The Netherlands"),
    "Nigeria": ("NG", "NGA"),
    "Norway": ("NO", "NOR", "Norge"),
    "Poland": ("PL", "POL", "Polska"),
    "Portugal": ("PT", "PRT"),
    "RSA": ("ZA", "ZAF", "South Africa", "Republic of South Africa"),
    "Saudi Arabia": ("SA", "SAU"),
    "Singapore": ("SG", "SGP"),
    "Spain": ("ES", "ESP", "España"),
    "Sweden": ("SE", "SWE", "Sverige"),
    "Switzerland": ("CH", "CHE", "Schweiz", "Suisse", "Svizzera"),
    "Thailand": ("TH", "THA"),
    "USA": ("US", "United States", "United States of America"),
    "United Arab Emirates": ("AE", "ARE", "UAE", "Emirates"),
    "United Kingdom": ("UK", "GB", "GBR", "Great Britain", "Britain"),
}
_CATALOGUE_VALUE = {a: country for country, names in COUNTRY_ALIASES.items() for a in names}

# docs/02 section 2: only these two count as space. str.strip() with no argument also removes NBSP.
_SPACE = " \t"

_CLOCK = r"(?P<time>\d{2}:\d{2})(?P<sec>:\d{2})?"
_DATE_FORMATS = (
    (
        re.compile(r"(?P<day>\d{2})/(?P<month>\d{2})/(?P<year>\d{4}) " + _CLOCK, re.ASCII),
        "dd/MM/yyyy HH:mm",
    ),
    (
        re.compile(r"(?P<year>\d{4})-(?P<month>\d{2})-(?P<day>\d{2})T" + _CLOCK, re.ASCII),
        "yyyy-MM-dd'T'HH:mm",
    ),
)
_PRICE = re.compile(r"(?P<symbol>£)?(?P<whole>\d+)(?:(?P<sep>[.,])(?P<fraction>\d+))?", re.ASCII)
_WHOLE_NUMBER = re.compile(r"(\d+)\.0", re.ASCII)


class _Diagnosis(StrEnum):
    STRIP = "STRIP"
    FOLD_CASE = "FOLD_CASE"
    ALIAS = "ALIAS"
    WHOLE_NUMBER = "WHOLE_NUMBER"
    OPTIONAL_TOKEN = "OPTIONAL_TOKEN"  # noqa: S105 - a null token, not a secret
    DATE = "DATE"
    PRICE = "PRICE"
    AMBIGUOUS = "AMBIGUOUS"
    UNRESOLVED = "UNRESOLVED"
    EMPTY = "EMPTY"
    REQUIRED_TOKEN = "REQUIRED_TOKEN"  # noqa: S105 - same
    RANGE = "RANGE"
    LENGTH = "LENGTH"
    WRONG_TYPE = "WRONG_TYPE"
    BUSINESS = "BUSINESS"
    STRUCTURE = "STRUCTURE"


# The fixed table of AC-17.2. Severities follow the scale and the floors of docs/06 A.1; corrections
# stay above the default thresholds of docs/04 section 8 (0.85, and 0.90 for MAP_TO_ENUM).
_TABLE: dict[_Diagnosis, tuple[RootCause, Severity, float]] = {
    _Diagnosis.STRIP: (RootCause.WHITESPACE, Severity.LOW, 0.99),
    _Diagnosis.FOLD_CASE: (RootCause.CASING, Severity.LOW, 0.98),
    _Diagnosis.ALIAS: (RootCause.SYNONYM, Severity.LOW, 0.96),
    _Diagnosis.WHOLE_NUMBER: (RootCause.FORMAT_MISMATCH, Severity.LOW, 0.95),
    _Diagnosis.OPTIONAL_TOKEN: (RootCause.NULL_TOKEN, Severity.LOW, 0.95),
    _Diagnosis.DATE: (RootCause.FORMAT_MISMATCH, Severity.LOW, 0.97),
    _Diagnosis.PRICE: (RootCause.LOCALE_FORMAT, Severity.LOW, 0.95),
    _Diagnosis.AMBIGUOUS: (RootCause.AMBIGUOUS_VALUE, Severity.MEDIUM, 0.60),
    _Diagnosis.UNRESOLVED: (RootCause.UNKNOWN, Severity.MEDIUM, 0.50),
    _Diagnosis.EMPTY: (RootCause.MISSING_VALUE, Severity.HIGH, 0.99),
    _Diagnosis.REQUIRED_TOKEN: (RootCause.NULL_TOKEN, Severity.HIGH, 0.99),
    _Diagnosis.RANGE: (RootCause.IMPLAUSIBLE_VALUE, Severity.HIGH, 0.95),
    _Diagnosis.LENGTH: (RootCause.IMPLAUSIBLE_VALUE, Severity.HIGH, 0.95),
    _Diagnosis.WRONG_TYPE: (RootCause.TYPE_MISMATCH, Severity.HIGH, 0.90),
    _Diagnosis.BUSINESS: (RootCause.BUSINESS_RULE, Severity.CRITICAL, 0.99),
    _Diagnosis.STRUCTURE: (RootCause.STRUCTURAL, Severity.CRITICAL, 0.99),
}

# Constant texts: no value of any column can leak into an analysis that is reused on other rows.
_EXPLANATIONS = {
    "es": {
        _Diagnosis.STRIP: "El valor es correcto salvo por espacios al inicio o al final.",
        _Diagnosis.FOLD_CASE: "El valor coincide con uno del catálogo salvo por las mayúsculas.",
        _Diagnosis.ALIAS: "El valor es un sinónimo conocido de un valor del catálogo.",
        _Diagnosis.WHOLE_NUMBER: "El identificador viene como número con el sufijo decimal .0.",
        _Diagnosis.OPTIONAL_TOKEN: "El campo es opcional y trae un token que significa vacío.",
        _Diagnosis.DATE: "La fecha está en otro formato, y el día y el mes no se pueden confundir.",
        _Diagnosis.PRICE: "El precio trae el símbolo de libra o usa coma como separador decimal.",
        _Diagnosis.AMBIGUOUS: "El valor admite más de una interpretación razonable.",
        _Diagnosis.UNRESOLVED: "No se reconoce una transformación segura para este valor.",
        _Diagnosis.EMPTY: "El campo es obligatorio y está vacío.",
        _Diagnosis.REQUIRED_TOKEN: "El campo es obligatorio y trae un token que significa vacío.",
        _Diagnosis.RANGE: "El valor está fuera del rango permitido.",
        _Diagnosis.LENGTH: "El valor supera la longitud máxima del campo.",
        _Diagnosis.WRONG_TYPE: "El valor no se puede convertir al tipo del campo.",
        _Diagnosis.BUSINESS: "Los valores de la fila incumplen una regla de negocio.",
        _Diagnosis.STRUCTURE: "La fila no tiene el número de columnas de la cabecera.",
    },
    "en": {
        _Diagnosis.STRIP: "The value is correct except for leading or trailing spaces.",
        _Diagnosis.FOLD_CASE: "The value matches a catalogue entry except for its case.",
        _Diagnosis.ALIAS: "The value is a known synonym of a catalogue entry.",
        _Diagnosis.WHOLE_NUMBER: "The identifier comes as a number with a .0 decimal suffix.",
        _Diagnosis.OPTIONAL_TOKEN: "The field is optional and holds a token that means empty.",
        _Diagnosis.DATE: "The date is in another format, and day and month cannot be confused.",
        _Diagnosis.PRICE: "The price has a pound sign or uses a comma as decimal separator.",
        _Diagnosis.AMBIGUOUS: "The value admits more than one reasonable reading.",
        _Diagnosis.UNRESOLVED: "No safe transformation is known for this value.",
        _Diagnosis.EMPTY: "The field is required and empty.",
        _Diagnosis.REQUIRED_TOKEN: "The field is required and holds a token that means empty.",
        _Diagnosis.RANGE: "The value is outside the allowed range.",
        _Diagnosis.LENGTH: "The value exceeds the maximum length of the field.",
        _Diagnosis.WRONG_TYPE: "The value cannot be converted to the type of the field.",
        _Diagnosis.BUSINESS: "The values of the row break a business rule.",
        _Diagnosis.STRUCTURE: "The row does not have the number of columns of the header.",
    },
}
_HUMAN_ACTION = {
    "es": "Revisar el valor en el sistema de origen y reenviar la fila corregida.",
    "en": "Check the value in the source system and resend the corrected row.",
}
_SUMMARY = {
    "es": "Violaciones analizadas: {total}. Corregibles: {fixable}. Para revisión humana: {human}.",
    "en": "Violations analysed: {total}. Fixable: {fixable}. For human review: {human}.",
}

_BY_ERROR_CODE = {
    ErrorCode.OUT_OF_RANGE: _Diagnosis.RANGE,
    ErrorCode.MAX_LENGTH_EXCEEDED: _Diagnosis.LENGTH,
    ErrorCode.INVALID_TYPE: _Diagnosis.WRONG_TYPE,
    ErrorCode.BUSINESS_RULE_VIOLATION: _Diagnosis.BUSINESS,
    ErrorCode.MALFORMED_ROW: _Diagnosis.STRUCTURE,
}


class _Fix(NamedTuple):
    operation: Operation
    proposed: str | None
    parameters: dict[str, Any]


def _number_parameters(decimal_separator: str, *stripped: str) -> dict[str, Any]:
    return {
        "decimalSeparator": decimal_separator,
        "groupingSeparator": None,
        "strippedSymbols": list(stripped),
    }


def _diagnose_date(value: str) -> tuple[_Diagnosis, _Fix | None]:
    for pattern, source_pattern in _DATE_FORMATS:
        if not (match := pattern.fullmatch(value)):
            continue
        year, month, day = match["year"], match["month"], match["day"]
        seconds = match["sec"]
        try:
            date.fromisoformat(f"{year}-{month}-{day}")
            time.fromisoformat(match["time"] + (seconds or ""))
        except ValueError:
            return _Diagnosis.UNRESOLVED, None
        # The same swap the PARSE_DATE verifier tries (docs/02 section 4).
        if int(day) <= 12 and day != month:
            return _Diagnosis.AMBIGUOUS, None
        proposed = f"{year}-{month}-{day} {match['time']}{seconds or ':00'}"
        parameters = {"sourcePattern": source_pattern + (":ss" if seconds else "")}
        return _Diagnosis.DATE, _Fix(Operation.PARSE_DATE, proposed, parameters)
    return _Diagnosis.UNRESOLVED, None


def _diagnose_price(value: str) -> tuple[_Diagnosis, _Fix | None]:
    match = _PRICE.fullmatch(value)
    if not match:
        return _Diagnosis.UNRESOLVED, None
    symbol, whole, separator, fraction = (
        match["symbol"],
        match["whole"],
        match["sep"],
        match["fraction"],
    )
    # One separator and three digits may be thousands or too many decimals: never guess.
    if fraction and len(fraction) == 3:
        return _Diagnosis.AMBIGUOUS, None
    if (fraction and len(fraction) > 2) or (not symbol and separator != ","):
        return _Diagnosis.UNRESOLVED, None
    proposed = f"{whole}.{fraction}" if fraction else whole
    stripped = (symbol,) if symbol else ()
    parameters = _number_parameters(separator or ".", *stripped)
    return _Diagnosis.PRICE, _Fix(Operation.PARSE_NUMBER, proposed, parameters)


def _diagnose_pattern(field: str, value: str, expected: Expected) -> tuple[_Diagnosis, _Fix | None]:
    if not expected.pattern:
        return _Diagnosis.UNRESOLVED, None
    stripped = value.strip(_SPACE)
    if field == "customer_id" and stripped and stripped in NULL_TOKENS:
        return _Diagnosis.OPTIONAL_TOKEN, _Fix(Operation.NULLIFY_TOKEN, None, {})
    # re.ASCII because \d only means ASCII digits in the Java validator that wrote this pattern.
    if stripped != value and re.fullmatch(expected.pattern, stripped, re.ASCII):
        return _Diagnosis.STRIP, _Fix(Operation.TRIM, stripped, {})
    whole = _WHOLE_NUMBER.fullmatch(stripped)
    if field == "customer_id" and whole and re.fullmatch(expected.pattern, whole[1], re.ASCII):
        fix = _Fix(Operation.PARSE_NUMBER, whole[1], _number_parameters("."))
        return _Diagnosis.WHOLE_NUMBER, fix
    return _Diagnosis.UNRESOLVED, None


def _diagnose_enum(field: str, value: str, expected: Expected) -> tuple[_Diagnosis, _Fix | None]:
    allowed = expected.allowed_values or []
    stripped = value.strip(_SPACE)
    if stripped in allowed:
        return _Diagnosis.STRIP, _Fix(Operation.TRIM, stripped, {})
    for candidate in allowed:
        if candidate.lower() == stripped.lower():
            return _Diagnosis.FOLD_CASE, _Fix(Operation.NORMALIZE_CASE, candidate, {})
    catalogue_value = _CATALOGUE_VALUE.get(stripped)
    if field == "country" and catalogue_value in allowed:
        return _Diagnosis.ALIAS, _Fix(Operation.MAP_TO_ENUM, catalogue_value, {})
    return _Diagnosis.UNRESOLVED, None


def _diagnose(violation: Violation) -> tuple[_Diagnosis, _Fix | None]:
    code, field, value = violation.error_code, violation.field, violation.received_value
    if code in _BY_ERROR_CODE:
        return _BY_ERROR_CODE[code], None
    if code is ErrorCode.MISSING_REQUIRED_FIELD:
        blank = value is None or not value.strip(_SPACE)
        return (_Diagnosis.EMPTY if blank else _Diagnosis.REQUIRED_TOKEN), None
    if value is None:
        return _Diagnosis.UNRESOLVED, None
    if code is ErrorCode.PATTERN_MISMATCH:
        return _diagnose_pattern(field, value, violation.expected)
    if code is ErrorCode.INVALID_ENUM_VALUE:
        return _diagnose_enum(field, value, violation.expected)
    if field == "invoice_date":
        return _diagnose_date(value.strip(_SPACE))
    if field == "unit_price":
        return _diagnose_price(value.strip(_SPACE))
    return _Diagnosis.UNRESOLVED, None


def _analyze(violation: Violation, language: str) -> dict[str, Any]:
    diagnosis, fix = _diagnose(violation)
    root_cause, severity, confidence = _TABLE[diagnosis]
    fixable = fix is not None
    return {
        "violationId": violation.violation_id,
        "field": violation.field,
        "rootCause": root_cause.value,
        "explanation": _EXPLANATIONS[language][diagnosis],
        "severity": severity.value,
        "classification": (
            Classification.AUTO_FIXABLE if fixable else Classification.NEEDS_HUMAN_REVIEW
        ).value,
        "confidence": confidence,
        "correction": fix
        and {
            "operation": fix.operation.value,
            "originalValue": violation.received_value,
            "proposedValue": fix.proposed,
            "parameters": fix.parameters,
        },
        "suggestedHumanAction": None if fixable else _HUMAN_ACTION[language],
    }


class MockProvider:
    async def complete_json(
        self, system_prompt: str, user_prompt: str, json_schema: dict[str, Any]
    ) -> ProviderResult:
        request = read_context(user_prompt)
        if request.options is None or request.options.explanation_language is None:
            raise ValueError("the triage context block has no resolved explanation language")
        language = request.options.explanation_language
        analyses = [_analyze(violation, language) for violation in request.violations]
        fixable = sum(analysis["correction"] is not None for analysis in analyses)
        summary = _SUMMARY[language].format(
            total=len(analyses), fixable=fixable, human=len(analyses) - fixable
        )
        return ProviderResult({"summary": summary, "analyses": analyses}, usage=None)
