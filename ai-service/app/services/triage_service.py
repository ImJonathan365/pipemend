"""The triage flow of docs/04 section 6.2, as far as docs/07 takes it in Sprint 3. No HTTP here."""

import time
from typing import Literal

from pydantic import ValidationError

from app.config import Settings
from app.domain.contracts import (
    Analysis,
    Classification,
    Meta,
    TriageRequest,
    TriageResponse,
    Violation,
)
from app.domain.llm_output import LlmTriageOutput
from app.domain.rules import (
    apply_hard_rules,
    cut_summary,
    is_cacheable,
    max_severity,
    record_decision,
)
from app.domain.triage_prompt import render_context
from app.providers.base import LLMProvider, Usage
from app.services.cache import TriageCache, cache_key

# prompts/triage_v1.md arrives in Sprint 5 with the real providers. Until then create_provider can
# only build the mock, which ignores this, so no LLM ever receives an empty system prompt.
_SYSTEM_PROMPT = ""
_OUTPUT_SCHEMA = LlmTriageOutput.model_json_schema(by_alias=True)
_CACHED_SUMMARY = {
    "es": "Violaciones analizadas: {total}. Corregibles: {fixable}. Para revisión humana: {human}.",
    "en": "Violations analysed: {total}. Fixable: {fixable}. For human review: {human}.",
}
# docs/06 counts real LLM calls, and the mock, the only provider until Sprint 5, is not one.
_LLM_CALLS = 0


class LlmInvalidOutputError(Exception):
    """The provider answered something outside the LLM boundary of docs/06."""


class TriageService:
    def __init__(self, provider: LLMProvider, settings: Settings, cache: TriageCache) -> None:
        self._provider = provider
        self._settings = settings
        self._cache = cache

    async def triage(self, request: TriageRequest) -> TriageResponse:
        started = time.perf_counter()
        options = request.options
        language = (
            options.explanation_language if options else None
        ) or self._settings.ai_explanation_language

        cached = self._from_cache(request, language)
        if cached is not None:
            summary = _summary_of(cached, language)
            return self._respond(request, cached, summary, started, None, cache_hit=True)

        result = await self._provider.complete_json(
            _SYSTEM_PROMPT, render_context(request, language), _OUTPUT_SCHEMA
        )
        try:
            output = LlmTriageOutput.model_validate(result.content)
        except ValidationError as error:
            # The errors quote the answer, and with it values of the row: not for a message.
            raise LlmInvalidOutputError("the output does not match the LLM model") from error
        _check_one_analysis_per_violation(output, request)

        analyses = [
            apply_hard_rules(analysis, violation, language)
            for analysis, violation in zip(output.analyses, request.violations, strict=True)
        ]
        for analysis, violation in zip(analyses, request.violations, strict=True):
            if is_cacheable(analysis, violation, request.record.raw):
                self._cache.put(self._key(violation, language), analysis)
        return self._respond(request, analyses, cut_summary(output.summary), started, result.usage)

    def _from_cache(self, request: TriageRequest, language: str) -> list[Analysis] | None:
        """AC-08.4 d: all or nothing, so a partial hit still calls for the whole request."""
        analyses = []
        for violation in request.violations:
            hit = self._cache.get(self._key(violation, language))
            if hit is None:
                return None
            analyses.append(hit.model_copy(update={"violation_id": violation.violation_id}))
        return analyses

    def _key(self, violation: Violation, language: str) -> str:
        return cache_key(
            violation,
            language,
            self._settings.llm_provider.value,
            self._settings.llm_model,
            self._settings.prompt_version_triage,
        )

    def _respond(
        self,
        request: TriageRequest,
        analyses: list[Analysis],
        summary: str,
        started: float,
        usage: Usage | None,
        *,
        cache_hit: bool = False,
    ) -> TriageResponse:
        return TriageResponse(
            request_id=request.request_id,
            record_decision=record_decision(analyses),
            max_severity=max_severity(analyses),
            summary=summary,
            analyses=analyses,
            meta=Meta(
                provider=self._settings.llm_provider.value,
                model=self._settings.llm_model,
                prompt_version=self._settings.prompt_version_triage,
                latency_ms=round((time.perf_counter() - started) * 1000),
                cache_hit=cache_hit,
                input_tokens=usage.input_tokens if usage else None,
                output_tokens=usage.output_tokens if usage else None,
                llm_calls=_LLM_CALLS,
            ),
        )


def _check_one_analysis_per_violation(output: LlmTriageOutput, request: TriageRequest) -> None:
    """docs/06 A.1: one per violationId, same order, same field. No rule can repair this."""
    received = [(a.violation_id, a.field) for a in output.analyses]
    sent = [(v.violation_id, v.field) for v in request.violations]
    if received != sent:
        raise LlmInvalidOutputError("the analyses are not the violations of the request")


def _summary_of(analyses: list[Analysis], language: Literal["es", "en"]) -> str:
    fixable = sum(a.classification is Classification.AUTO_FIXABLE for a in analyses)
    return _CACHED_SUMMARY[language].format(
        total=len(analyses), fixable=fixable, human=len(analyses) - fixable
    )
