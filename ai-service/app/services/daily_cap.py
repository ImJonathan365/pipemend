"""LLM_MAX_CALLS_PER_DAY (AC-08.8): ephemeral like the cache, lost on restart (NFR-17)."""

from collections.abc import Callable
from datetime import UTC, date, datetime


def _utc_now() -> datetime:
    return datetime.now(UTC)


class DailyCallCounter:
    """Real LLM calls of the current UTC day. One per app, like the triage cache.

    The cap is passed on each reservation, so it always comes from the settings the request runs
    with. Reserving checks and counts with no await in between, so two requests cannot both take
    the last call.
    """

    def __init__(self, clock: Callable[[], datetime] = _utc_now) -> None:
        self._clock = clock
        self._day: date | None = None
        self._count = 0

    def try_reserve(self, cap: int) -> bool:
        self._roll()
        if self._count >= cap:
            return False
        self._count += 1
        return True

    def today(self) -> int:
        self._roll()
        return self._count

    def _roll(self) -> None:
        day = self._clock().astimezone(UTC).date()
        if day != self._day:
            self._day, self._count = day, 0
