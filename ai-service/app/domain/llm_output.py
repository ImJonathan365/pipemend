"""What an LLM may answer to a triage prompt: the untrusted boundary of docs/06 and docs/09 7.5.

Stricter than contracts.py in shape and laxer in rules. `recordDecision`, `maxSeverity`, `requestId`
and `meta` are the service's to set, so an LLM that sends them is inventing fields. There is no
length limit (texts are cut, docs/06 rule 5) and no correction/classification check (a fixable
analysis without correction is downgraded, docs/06 rule 3, not an invalid answer).
"""

from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, JsonValue
from pydantic.alias_generators import to_camel

from app.domain.contracts import Classification, Operation, RootCause, Severity


class LlmModel(BaseModel):
    # No populate_by_name, unlike ContractModel: `violation_id` is a field the LLM made up.
    model_config = ConfigDict(alias_generator=to_camel, extra="forbid")


class LlmCorrection(LlmModel):
    operation: Operation
    original_value: str | None
    proposed_value: str | None
    parameters: dict[str, JsonValue]


class LlmAnalysis(LlmModel):
    violation_id: str
    field: str
    root_cause: RootCause
    explanation: str
    severity: Severity
    classification: Classification
    # Strict so that "0.97" and true are invalid output rather than coerced to a number.
    confidence: Annotated[float, Field(ge=0, le=1, strict=True)]
    correction: LlmCorrection | None
    suggested_human_action: str | None


class LlmTriageOutput(LlmModel):
    summary: str
    analyses: list[LlmAnalysis]
