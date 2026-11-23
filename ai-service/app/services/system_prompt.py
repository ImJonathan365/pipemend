"""The versioned system prompts of app/prompts (docs/04 section 6.2, step 4).

Apart from triage_prompt on purpose: the mock imports that one, and must not be able to read files
(AC-17.2).
"""

import re
from functools import lru_cache
from pathlib import Path

PROMPTS = Path(__file__).resolve().parents[1] / "prompts"
# PROMPT_VERSION_TRIAGE comes from the environment and names a file: nothing outside PROMPTS.
_VERSION = re.compile(r"[a-z_]+_v[1-9][0-9]*")


@lru_cache
def load_system_prompt(version: str) -> str:
    """A published version is never edited; a change is a new version (NFR-12, docs/09 7.3)."""
    if not _VERSION.fullmatch(version):
        raise ValueError(f"{version!r} is not a prompt version such as triage_v1")
    return (PROMPTS / f"{version}.md").read_text(encoding="utf-8")
