"""In-memory LRU with TTL for field-level analyses (NFR-11, AC-08.4); it dies with the process."""

import hashlib
import json
import time
from collections import OrderedDict
from collections.abc import Callable

from app.domain.contracts import Analysis, Violation


def cache_key(
    violation: Violation, language: str, provider: str, model: str, prompt_version: str
) -> str:
    canonical = json.dumps(
        {
            "field": violation.field,
            "errorCode": violation.error_code.value,
            "receivedValue": violation.received_value,
            "expected": violation.expected.model_dump(mode="json", by_alias=True),
            "explanationLanguage": language,
            "provider": provider,
            "model": model,
            "promptVersion": prompt_version,
        },
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


class TriageCache:
    def __init__(
        self,
        max_entries: int,
        ttl_seconds: int,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._max_entries = max_entries
        self._ttl_seconds = ttl_seconds
        self._clock = clock
        self._entries: OrderedDict[str, tuple[float, Analysis]] = OrderedDict()

    def __len__(self) -> int:
        return len(self._entries)

    def get(self, key: str) -> Analysis | None:
        entry = self._entries.get(key)
        if entry is None:
            return None
        stored_at, analysis = entry
        if self._clock() - stored_at >= self._ttl_seconds:
            del self._entries[key]
            return None
        self._entries.move_to_end(key)
        return analysis

    def put(self, key: str, analysis: Analysis) -> None:
        self._entries[key] = (self._clock(), analysis)
        self._entries.move_to_end(key)
        while len(self._entries) > self._max_entries:
            self._entries.popitem(last=False)
