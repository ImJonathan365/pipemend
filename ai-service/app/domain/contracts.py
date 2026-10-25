"""Wire contract of POST /v1/triage (docs/06 A.1), exported to contracts/ by app.contract_export."""

from enum import StrEnum
from typing import Annotated, Literal, Self
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, JsonValue, field_validator, model_validator
from pydantic.alias_generators import to_camel

RAW_LINE_KEY = "_rawLine"
RAW_LINE_MAX_LENGTH = 2000


class ContractModel(BaseModel):
    """Base for anything on the wire: docs/06 fixes camelCase for every JSON field."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class RequestModel(ContractModel):
    # docs/06: an unknown field in a pipeline request is a contract bug, answered with 422.
    model_config = ConfigDict(extra="forbid")


class SourceType(StrEnum):
    CSV = "CSV"
    JSON = "JSON"


class ErrorCode(StrEnum):
    """docs/02 section 3 without SCHEMA_DRIFT, which is a file-level code."""

    MISSING_REQUIRED_FIELD = "MISSING_REQUIRED_FIELD"
    INVALID_TYPE = "INVALID_TYPE"
    INVALID_FORMAT = "INVALID_FORMAT"
    PATTERN_MISMATCH = "PATTERN_MISMATCH"
    OUT_OF_RANGE = "OUT_OF_RANGE"
    INVALID_ENUM_VALUE = "INVALID_ENUM_VALUE"
    MAX_LENGTH_EXCEEDED = "MAX_LENGTH_EXCEEDED"
    BUSINESS_RULE_VIOLATION = "BUSINESS_RULE_VIOLATION"
    MALFORMED_ROW = "MALFORMED_ROW"


class Severity(StrEnum):
    # Declared from lowest to highest: maxSeverity is the last one present.
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class Classification(StrEnum):
    AUTO_FIXABLE = "AUTO_FIXABLE"
    NEEDS_HUMAN_REVIEW = "NEEDS_HUMAN_REVIEW"


class RootCause(StrEnum):
    FORMAT_MISMATCH = "FORMAT_MISMATCH"
    LOCALE_FORMAT = "LOCALE_FORMAT"
    WHITESPACE = "WHITESPACE"
    CASING = "CASING"
    SYNONYM = "SYNONYM"
    NULL_TOKEN = "NULL_TOKEN"  # noqa: S105 - a null token such as "N/A", not a secret
    MISSING_VALUE = "MISSING_VALUE"
    IMPLAUSIBLE_VALUE = "IMPLAUSIBLE_VALUE"
    TYPE_MISMATCH = "TYPE_MISMATCH"
    AMBIGUOUS_VALUE = "AMBIGUOUS_VALUE"
    BUSINESS_RULE = "BUSINESS_RULE"
    STRUCTURAL = "STRUCTURAL"
    UNKNOWN = "UNKNOWN"


class Operation(StrEnum):
    """The allowlist of docs/02 section 4; RENAME_COLUMN belongs to schema drift only."""

    TRIM = "TRIM"
    NORMALIZE_CASE = "NORMALIZE_CASE"
    PARSE_DATE = "PARSE_DATE"
    PARSE_NUMBER = "PARSE_NUMBER"
    MAP_TO_ENUM = "MAP_TO_ENUM"
    NULLIFY_TOKEN = "NULLIFY_TOKEN"  # noqa: S105 - same: a null token, not a secret


class SchemaRef(RequestModel):
    name: str
    version: str


class Source(RequestModel):
    filename: str
    type: SourceType


class Record(RequestModel):
    source_row_number: Annotated[int, Field(ge=1)]
    raw: dict[str, str | None]

    @field_validator("raw")
    @classmethod
    def raw_line_is_truncated(cls, raw: dict[str, str | None]) -> dict[str, str | None]:
        line = raw.get(RAW_LINE_KEY)
        if line is not None and len(line) > RAW_LINE_MAX_LENGTH:
            raise ValueError(
                f"{RAW_LINE_KEY} must be truncated to {RAW_LINE_MAX_LENGTH} characters"
            )
        return raw


class Expected(RequestModel):
    """What the field should have been (docs/02 section 3.1, rule 4)."""

    type: str | None = None
    format: str | None = None
    pattern: str | None = None
    min: str | None = None
    max: str | None = None
    max_length: int | None = None
    scale: int | None = None
    decimal_separator: str | None = None
    allowed_values: Annotated[list[str], Field(max_length=100)] | None = None
    rule: str | None = None
    # MALFORMED_ROW (AC-07.4, D63); receivedColumns is null when the line cannot be split at all.
    expected_columns: Annotated[int, Field(ge=1)] | None = None
    received_columns: Annotated[int, Field(ge=0)] | None = None


class Violation(RequestModel):
    violation_id: Annotated[str, Field(min_length=1)]
    field: str
    source_header: str | None = None
    error_code: ErrorCode
    received_value: str | None
    expected: Expected
    technical_message: Annotated[str, Field(max_length=1000)]
    exception_type: str | None = None


class TriageOptions(RequestModel):
    explanation_language: Literal["es", "en"] | None = None


class TriageRequest(RequestModel):
    request_id: UUID
    batch_id: UUID
    # `schema` would shadow a BaseModel attribute.
    schema_: Annotated[SchemaRef, Field(alias="schema")]
    source: Source
    record: Record
    violations: Annotated[list[Violation], Field(min_length=1, max_length=20)]
    options: TriageOptions | None = None

    @model_validator(mode="after")
    def violation_ids_are_unique(self) -> Self:
        ids = [violation.violation_id for violation in self.violations]
        if len(ids) != len(set(ids)):
            raise ValueError("violationId must be unique within the request")
        return self


class Correction(ContractModel):
    operation: Operation
    original_value: str | None
    proposed_value: str | None
    # The keys of each operation are checked by the pipeline's verifiers (docs/02 section 4).
    parameters: dict[str, JsonValue]


class Analysis(ContractModel):
    violation_id: str
    field: str
    root_cause: RootCause
    explanation: Annotated[str, Field(max_length=800)]
    severity: Severity
    classification: Classification
    confidence: Annotated[float, Field(ge=0, le=1)]
    correction: Correction | None
    suggested_human_action: str | None

    @model_validator(mode="after")
    def correction_matches_classification(self) -> Self:
        auto_fixable = self.classification is Classification.AUTO_FIXABLE
        if auto_fixable != (self.correction is not None):
            raise ValueError("correction is required for AUTO_FIXABLE and null otherwise")
        if not auto_fixable and not self.suggested_human_action:
            raise ValueError("suggestedHumanAction is required for NEEDS_HUMAN_REVIEW")
        return self


class Meta(ContractModel):
    """Traceability of the answer (NFR-12). `model` is whatever LLM_MODEL holds."""

    provider: str
    model: str
    prompt_version: str
    latency_ms: Annotated[int, Field(ge=0)]
    cache_hit: bool
    input_tokens: Annotated[int, Field(ge=0)] | None
    output_tokens: Annotated[int, Field(ge=0)] | None
    llm_calls: Annotated[int, Field(ge=0)]


class TriageResponse(ContractModel):
    """Unknown fields are ignored on this side: adding an optional field is compatible (docs/06)."""

    request_id: UUID
    record_decision: Classification
    max_severity: Severity
    summary: Annotated[str, Field(max_length=1000)]
    analyses: Annotated[list[Analysis], Field(min_length=1)]
    meta: Meta

    # The pipeline recomputes both and checks everything that needs the request (AC-08.7); this
    # only keeps ai-service from answering something incoherent on its own.
    @model_validator(mode="after")
    def record_level_fields_follow_the_analyses(self) -> Self:
        ids = [analysis.violation_id for analysis in self.analyses]
        if len(ids) != len(set(ids)):
            raise ValueError("analyses must not repeat a violationId")
        all_fixable = all(a.classification is Classification.AUTO_FIXABLE for a in self.analyses)
        expected_decision = (
            Classification.AUTO_FIXABLE if all_fixable else Classification.NEEDS_HUMAN_REVIEW
        )
        if self.record_decision is not expected_decision:
            raise ValueError("recordDecision is AUTO_FIXABLE only if every analysis is")
        severities = list(Severity)
        highest = max((a.severity for a in self.analyses), key=severities.index)
        if self.max_severity is not highest:
            raise ValueError("maxSeverity must be the highest severity of the analyses")
        return self
