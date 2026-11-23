"""What an LLM may answer to a triage prompt: the untrusted boundary of docs/06 and docs/09 7.5.

Stricter than contracts.py in shape and laxer in rules. `recordDecision`, `maxSeverity`, `requestId`
and `meta` are the service's to set, so an LLM that sends them is inventing fields. There is no
length limit (texts are cut, docs/06 rule 5) and no correction/classification check (a fixable
analysis without correction is downgraded, docs/06 rule 3, not an invalid answer).
"""

from typing import Annotated, TypedDict

from pydantic import AfterValidator, BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.domain.contracts import Classification, Operation, RootCause, Severity


class LlmModel(BaseModel):
    # No populate_by_name, unlike ContractModel: `violation_id` is a field the LLM made up.
    model_config = ConfigDict(alias_generator=to_camel, extra="forbid")


# docs/06 l.222, concrete so the schema sent has no empty subschema (D263). Which keys each
# operation needs is the verifiers' to check (docs/02 section 4). A comment, not a docstring: a
# docstring would travel to the LLM as the schema's description.
class LlmParameters(TypedDict, total=False):
    __pydantic_config__ = ConfigDict(extra="forbid")  # type: ignore[misc]
    sourcePattern: str
    decimalSeparator: str
    groupingSeparator: str | None
    strippedSymbols: list[str]


class LlmCorrection(LlmModel):
    operation: Operation
    original_value: str | None
    proposed_value: str | None
    parameters: LlmParameters


def _unit_interval(value: float) -> float:
    if not 0 <= value <= 1:
        raise ValueError("confidence must be from 0 to 1")
    return value


class LlmAnalysis(LlmModel):
    violation_id: str
    field: str
    root_cause: RootCause
    explanation: str
    severity: Severity
    classification: Classification
    # Strict so that "0.97" and true are invalid output rather than coerced to a number. The range
    # is a validator, not ge/le: structured outputs reject minimum and maximum (D266).
    confidence: Annotated[float, Field(strict=True), AfterValidator(_unit_interval)]
    correction: LlmCorrection | None
    suggested_human_action: str | None


class LlmTriageOutput(LlmModel):
    summary: str
    analyses: list[LlmAnalysis]
