"""The triage flow of docs/04 section 6.2. No HTTP here."""

import asyncio
import json
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any, Literal

import structlog
from pydantic import ValidationError

from app.config import LlmProvider, Settings
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
from app.domain.triage_prompt import ERRORS_CLOSE, ERRORS_OPEN, render_context
from app.providers.base import (
    LLMProvider,
    ProviderAuthError,
    ProviderRateLimited,
    ProviderResult,
    ProviderTimeout,
    ProviderUnavailable,
    Usage,
)
from app.services.cache import TriageCache, cache_key
from app.services.daily_cap import DailyCallCounter
from app.services.system_prompt import load_system_prompt

_OUTPUT_SCHEMA = LlmTriageOutput.model_json_schema(by_alias=True)
_CACHED_SUMMARY = {
    "es": "Violaciones analizadas: {total}. Corregibles: {fixable}. Para revisión humana: {human}.",
    "en": "Violations analysed: {total}. Fixable: {fixable}. For human review: {human}.",
}
# docs/03 NFR-07: with less than this left of the deadline, no further LLM call is attempted.
_MIN_REMAINING_SECONDS = 3
# docs/03 NFR-07: the first call, one transient retry and one repair.
_MAX_LLM_CALLS = 3
_MISMATCH = {
    "loc": ["analyses"],
    "type": "violations_mismatch",
    "msg": "analyses must be one per violation, in the same order, with its violationId and field",
}

_log = structlog.get_logger(__name__)


class LlmInvalidOutputError(Exception):
    """The provider answered something outside the LLM boundary of docs/06."""

    def __init__(self, message: str, errors: list[dict[str, Any]]) -> None:
        super().__init__(message)
        # For the repair prompt only: loc, type and msg, never the input that failed.
        self.errors = errors


class LlmBudgetExhaustedError(Exception):
    """429 LLM_BUDGET_EXHAUSTED: LLM_MAX_CALLS_PER_DAY reached (AC-08.8)."""


class LlmTimeoutError(Exception):
    """504 LLM_TIMEOUT (docs/06 A.5)."""


class LlmRateLimitedError(Exception):
    """429 RATE_LIMITED (docs/06 A.5)."""


class LlmProviderError(Exception):
    """502 LLM_PROVIDER_ERROR (docs/06 A.5)."""


@dataclass
class _Run:
    """The LLM calls of one request (docs/03 NFR-07)."""

    deadline: float
    calls: int = 0
    retried: bool = False
    repaired: bool = False
    usage: Usage | None = None
    call_started: float = 0.0

    def add(self, usage: Usage | None) -> None:
        if usage is None:
            return
        if self.usage is None:
            self.usage = usage
        else:
            self.usage = Usage(
                self.usage.input_tokens + usage.input_tokens,
                self.usage.output_tokens + usage.output_tokens,
            )


class TriageService:
    def __init__(
        self,
        provider: LLMProvider,
        settings: Settings,
        cache: TriageCache,
        *,
        clock: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
        daily_calls: DailyCallCounter | None = None,
    ) -> None:
        self._provider = provider
        self._settings = settings
        self._cache = cache
        # D229: the API passes the one of app.state; never a default without a cap.
        self._daily_calls = daily_calls or DailyCallCounter()
        self._clock = clock
        self._sleep = sleep

    async def triage(self, request: TriageRequest) -> TriageResponse:
        started = time.perf_counter()
        run = _Run(deadline=self._clock() + self._settings.ai_request_deadline_seconds)
        options = request.options
        language = (
            options.explanation_language if options else None
        ) or self._settings.ai_explanation_language

        cached = self._from_cache(request, language)
        if cached is not None:
            summary = _summary_of(cached, language)
            return self._respond(request, cached, summary, started, run, cache_hit=True)

        output = await self._ask(run, request, render_context(request, language))
        analyses = [
            apply_hard_rules(analysis, violation, language)
            for analysis, violation in zip(output.analyses, request.violations, strict=True)
        ]
        for analysis, violation in zip(analyses, request.violations, strict=True):
            if is_cacheable(analysis, violation, request.record.raw):
                self._cache.put(self._key(violation, language), analysis)
        return self._respond(request, analyses, cut_summary(output.summary), started, run)

    async def _ask(self, run: _Run, request: TriageRequest, context: str) -> LlmTriageOutput:
        """docs/04 section 6.2, steps 5 and 6: one transient retry and one repair at most."""
        prompt, attempt = context, "first"
        while True:
            try:
                result = await self._call(run, prompt, attempt)
            except (ProviderUnavailable, ProviderRateLimited) as error:
                await self._before_retry(run, error)
                attempt = "retry"
                continue
            try:
                output = _parse(result.content, request)
            except LlmInvalidOutputError as error:
                self._log_call(run, attempt, "invalid_output", result.usage)
                if run.repaired or not self._may_call_again(run):
                    raise
                run.repaired = True
                prompt = f"{context}\n{_render_errors(error.errors)}"
                attempt = "repair"
                continue
            self._log_call(run, attempt, "ok", result.usage)
            return output

    async def _call(self, run: _Run, prompt: str, attempt: str) -> ProviderResult:
        """The one place every LLM call goes through, the first, the retry and the repair."""
        remaining = run.deadline - self._clock()
        # AC-08.8 before every call, retry and repair included (S5-10); the mock is exempt.
        if (
            self._settings.llm_provider is not LlmProvider.MOCK
            and not self._daily_calls.try_reserve(self._settings.llm_max_calls_per_day)
        ):
            _log.info("llm_budget_exhausted", attempt=attempt)
            raise LlmBudgetExhaustedError("LLM_MAX_CALLS_PER_DAY reached")
        run.calls += 1
        run.call_started = time.perf_counter()
        try:
            async with asyncio.timeout(min(self._settings.llm_timeout_seconds, remaining)):
                result = await self._provider.complete_json(
                    load_system_prompt(self._settings.prompt_version_triage), prompt, _OUTPUT_SCHEMA
                )
        except (TimeoutError, ProviderTimeout) as error:
            self._log_call(run, attempt, "timeout", None)
            raise LlmTimeoutError("the LLM did not answer in time") from error
        except ProviderAuthError as error:
            self._log_call(run, attempt, "auth_error", None)
            raise LlmProviderError("the provider refused the credentials") from error
        except ProviderRateLimited:
            self._log_call(run, attempt, "rate_limited", None)
            raise
        except ProviderUnavailable:
            self._log_call(run, attempt, "unavailable", None)
            raise
        run.add(result.usage)
        return result

    async def _before_retry(
        self, run: _Run, error: ProviderUnavailable | ProviderRateLimited
    ) -> None:
        """docs/03 NFR-07: 429 honouring Retry-After if it fits, 5xx and network errors."""
        wait = error.retry_after if isinstance(error, ProviderRateLimited) else None
        left_after_wait = run.deadline - self._clock() - (wait or 0)
        if run.retried or not self._may_call_again(run) or left_after_wait < _MIN_REMAINING_SECONDS:
            if isinstance(error, ProviderRateLimited):
                raise LlmRateLimitedError("the provider kept limiting the calls") from error
            raise LlmProviderError("the provider kept failing") from error
        run.retried = True
        if wait:
            await self._sleep(wait)

    def _may_call_again(self, run: _Run) -> bool:
        remaining = run.deadline - self._clock()
        return run.calls < _MAX_LLM_CALLS and remaining >= _MIN_REMAINING_SECONDS

    def _log_call(self, run: _Run, attempt: str, outcome: str, usage: Usage | None) -> None:
        """D224: what B7 measures. Never content, exception messages or values of the row."""
        _log.info(
            "llm_call",
            provider=self._settings.llm_provider.value,
            model=self._settings.llm_model,
            promptVersion=self._settings.prompt_version_triage,
            attempt=attempt,
            outcome=outcome,
            latencyMs=round((time.perf_counter() - run.call_started) * 1000),
            inputTokens=usage.input_tokens if usage else None,
            outputTokens=usage.output_tokens if usage else None,
        )

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
        run: _Run,
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
                input_tokens=run.usage.input_tokens if run.usage else None,
                output_tokens=run.usage.output_tokens if run.usage else None,
                # docs/06 counts real LLM calls, and the mock is not one (its example says 0).
                llm_calls=0 if self._settings.llm_provider is LlmProvider.MOCK else run.calls,
            ),
        )


def _parse(content: dict[str, Any], request: TriageRequest) -> LlmTriageOutput:
    try:
        output = LlmTriageOutput.model_validate(content)
    except ValidationError as error:
        # The errors quote the answer, and with it values of the row: not for a message.
        errors = error.errors(include_url=False, include_context=False, include_input=False)
        raise LlmInvalidOutputError(
            "the output does not match the LLM model",
            [{"loc": list(e["loc"]), "type": e["type"], "msg": e["msg"]} for e in errors],
        ) from error
    # docs/06 A.1: one per violationId, same order, same field. No rule can fix this.
    received = [(a.violation_id, a.field) for a in output.analyses]
    sent = [(v.violation_id, v.field) for v in request.violations]
    if received != sent:
        raise LlmInvalidOutputError(
            "the analyses are not the violations of the request", [_MISMATCH]
        )
    return output


def _summary_of(analyses: list[Analysis], language: Literal["es", "en"]) -> str:
    fixable = sum(a.classification is Classification.AUTO_FIXABLE for a in analyses)
    return _CACHED_SUMMARY[language].format(
        total=len(analyses), fixable=fixable, human=len(analyses) - fixable
    )


def _render_errors(errors: list[dict[str, Any]]) -> str:
    """The repair block, data like the context: loc can hold keys the LLM made up (NFR-14)."""
    payload = json.dumps(errors, ensure_ascii=False).replace("<", "\\u003c")
    return f"{ERRORS_OPEN}\n{payload}\n{ERRORS_CLOSE}"
