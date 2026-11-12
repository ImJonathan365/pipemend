"""The key of NFR-11 and the LRU with TTL of AC-08.4."""

import hashlib
from typing import Any

import pytest

from app.domain.contracts import (
    Analysis,
    Classification,
    ErrorCode,
    Expected,
    RootCause,
    Severity,
    Violation,
)
from app.services.cache import TriageCache, cache_key

ANALYSIS = Analysis(
    violation_id="v1",
    field="country",
    root_cause=RootCause.UNKNOWN,
    explanation="text",
    severity=Severity.MEDIUM,
    classification=Classification.NEEDS_HUMAN_REVIEW,
    confidence=0.5,
    correction=None,
    suggested_human_action="Check it.",
)


def _violation(**overrides: Any) -> Violation:
    body: dict[str, Any] = {
        "violation_id": "v1",
        "field": "country",
        "error_code": ErrorCode.INVALID_ENUM_VALUE,
        "received_value": "UK",
        "expected": Expected(type="enum", allowed_values=["France"]),
        "technical_message": "seeded",
    }
    return Violation(**(body | overrides))


def _key(violation: Violation | None = None, **overrides: str) -> str:
    args = {"language": "es", "provider": "mock", "model": "m", "prompt_version": "p"} | overrides
    return cache_key(violation or _violation(), **args)


class _Clock:
    def __init__(self) -> None:
        self.now = 100.0

    def __call__(self) -> float:
        return self.now


def test_the_key_is_the_sha256_of_the_canonical_json_of_nfr_11() -> None:
    """Written out by hand: sorted keys, no spaces, and accents as they are."""
    violation = _violation(received_value="Österreich", expected=Expected(type="enum"))
    expected = (
        '{"errorCode":"INVALID_ENUM_VALUE",'
        '"expected":{"allowedValues":null,"decimalSeparator":null,"expectedColumns":null,'
        '"format":null,"max":null,"maxLength":null,"min":null,"pattern":null,'
        '"receivedColumns":null,"rule":null,"scale":null,"type":"enum"},'
        '"explanationLanguage":"es","field":"country","model":"m","promptVersion":"p",'
        '"provider":"mock","receivedValue":"Österreich"}'
    )

    assert _key(violation) == hashlib.sha256(expected.encode("utf-8")).hexdigest()


@pytest.mark.parametrize(
    "overrides",
    [
        pytest.param({"field": "description"}, id="field"),
        pytest.param({"error_code": ErrorCode.PATTERN_MISMATCH}, id="error-code"),
        pytest.param({"received_value": "uk"}, id="received-value"),
        pytest.param({"received_value": None}, id="null-received-value"),
        pytest.param({"expected": Expected(type="enum")}, id="expected"),
    ],
)
def test_every_part_of_the_violation_signature_changes_the_key(overrides: dict[str, Any]) -> None:
    assert _key(_violation(**overrides)) != _key()


@pytest.mark.parametrize(
    "overrides",
    [
        pytest.param({"language": "en"}, id="language"),
        pytest.param({"provider": "other"}, id="provider"),
        pytest.param({"model": "other"}, id="model"),
        pytest.param({"prompt_version": "other"}, id="prompt-version"),
    ],
)
def test_every_part_of_the_context_changes_the_key(overrides: dict[str, str]) -> None:
    assert _key(**overrides) != _key()


@pytest.mark.parametrize(
    "overrides",
    [
        pytest.param({"violation_id": "v9"}, id="violation-id"),
        pytest.param({"source_header": "Pais"}, id="source-header"),
        pytest.param({"technical_message": "other"}, id="technical-message"),
        pytest.param({"exception_type": "other"}, id="exception-type"),
    ],
)
def test_what_is_not_in_the_signature_does_not_change_the_key(overrides: dict[str, Any]) -> None:
    assert _key(_violation(**overrides)) == _key()


def test_a_missing_key_is_a_miss() -> None:
    assert TriageCache(2, 60).get("a") is None


def test_an_entry_is_found_until_its_ttl_expires() -> None:
    clock = _Clock()
    cache = TriageCache(2, 60, clock)
    cache.put("a", ANALYSIS)

    clock.now += 59.9
    assert cache.get("a") == ANALYSIS

    clock.now += 0.1
    assert cache.get("a") is None
    assert len(cache) == 0


def test_a_read_does_not_extend_the_ttl() -> None:
    clock = _Clock()
    cache = TriageCache(2, 60, clock)
    cache.put("a", ANALYSIS)

    clock.now += 40
    assert cache.get("a") is not None
    clock.now += 40

    assert cache.get("a") is None


def test_the_least_recently_used_entry_is_the_one_evicted() -> None:
    cache = TriageCache(2, 60, _Clock())
    cache.put("a", ANALYSIS)
    cache.put("b", ANALYSIS)
    assert cache.get("a") is not None

    cache.put("c", ANALYSIS)

    assert cache.get("b") is None
    assert cache.get("a") is not None
    assert cache.get("c") is not None
    assert len(cache) == 2


def test_writing_an_entry_again_renews_it() -> None:
    clock = _Clock()
    cache = TriageCache(2, 60, clock)
    cache.put("a", ANALYSIS)
    cache.put("b", ANALYSIS)

    clock.now += 40
    cache.put("a", ANALYSIS)
    cache.put("c", ANALYSIS)
    clock.now += 40

    assert cache.get("b") is None
    assert cache.get("a") is not None
    assert len(cache) == 2


def test_two_caches_share_nothing() -> None:
    first = TriageCache(2, 60)
    first.put("a", ANALYSIS)

    assert TriageCache(2, 60).get("a") is None
