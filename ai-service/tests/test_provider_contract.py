"""The battery every real provider passes, each with an HTTP double in its own wire format (FR-17).

No network: the SDK gets an httpx2 client whose transport is a function. B6 adds the Anthropic wire.
"""

import asyncio
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx2
import pytest
from fastapi.testclient import TestClient

from app.config import LlmProvider, Settings, get_settings
from app.domain.contracts import TriageRequest
from app.domain.triage_prompt import CONTEXT_CLOSE, CONTEXT_OPEN, render_context
from app.main import create_app
from app.providers.base import (
    ProviderAuthError,
    ProviderBadRequest,
    ProviderInvalidOutput,
    ProviderRateLimited,
    ProviderResult,
    ProviderTimeout,
    ProviderUnavailable,
)
from app.providers.mock import MockProvider
from app.providers.openai_compatible import OpenAICompatibleProvider

SENTINEL = "sentinel-from-the-provider-body"
SYSTEM = "the system prompt"
USER = f'{CONTEXT_OPEN}\n{{"a": "<"}}\n{CONTEXT_CLOSE}'
FIXTURE = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"
Responder = Callable[[httpx2.Request], httpx2.Response]


class _Server:
    """The far end of the SDK's HTTP client: records each request and answers it."""

    def __init__(self, respond: Responder) -> None:
        self.respond = respond
        self.requests: list[httpx2.Request] = []

    def __call__(self, request: httpx2.Request) -> httpx2.Response:
        self.requests.append(request)
        return self.respond(request)


class _OpenAIWire:
    name = LlmProvider.OPENAI_COMPATIBLE
    settings = Settings(
        llm_provider=LlmProvider.OPENAI_COMPATIBLE,
        llm_api_key="test-key",
        llm_model="test-model",
        llm_base_url="http://127.0.0.1:9/v1",
    )

    def provider(
        self, server: _Server, settings: Settings | None = None
    ) -> OpenAICompatibleProvider:
        client = httpx2.AsyncClient(transport=httpx2.MockTransport(server))
        return OpenAICompatibleProvider(settings or self.settings, http_client=client)

    def answer(
        self, text: str | None, usage: tuple[int, int] | None = (12, 3), finish: str = "stop"
    ) -> httpx2.Response:
        body: dict[str, Any] = {
            "id": "c1",
            "object": "chat.completion",
            "created": 0,
            "model": "test-model",
            "choices": [
                {
                    "index": 0,
                    "finish_reason": finish,
                    "message": {"role": "assistant", "content": text},
                }
            ],
        }
        if usage is not None:
            body["usage"] = {
                "prompt_tokens": usage[0],
                "completion_tokens": usage[1],
                "total_tokens": sum(usage),
            }
        return httpx2.Response(200, json=body)

    def failure(self, status: int, headers: dict[str, str] | None = None) -> httpx2.Response:
        return httpx2.Response(
            status, headers=headers, json={"error": {"message": SENTINEL, "type": "error"}}
        )

    def sent(self, request: httpx2.Request) -> dict[str, Any]:
        body = json.loads(request.content)
        system, user = body["messages"]
        assert (system["role"], user["role"]) == ("system", "user")
        return {
            "model": body["model"],
            "temperature": body["temperature"],
            "max_tokens": body["max_tokens"],
            "system": system["content"],
            "user": user["content"],
        }


WIRES = [_OpenAIWire()]


def _complete(wire: Any, respond: Responder) -> tuple[_Server, ProviderResult]:
    server = _Server(respond)
    result = asyncio.run(wire.provider(server).complete_json(SYSTEM, USER, {}))
    return server, result


def _failing(wire: Any, respond: Responder, error: type[Exception]) -> tuple[_Server, Exception]:
    server = _Server(respond)
    with pytest.raises(error) as caught:
        asyncio.run(wire.provider(server).complete_json(SYSTEM, USER, {}))
    return server, caught.value


@pytest.fixture(params=WIRES, ids=lambda wire: wire.name.value)
def wire(request: pytest.FixtureRequest) -> Any:
    return request.param


def test_an_answer_is_its_json_object_and_its_tokens(wire: Any) -> None:
    _, result = _complete(wire, lambda _: wire.answer('{"summary": "s", "analyses": []}'))

    assert result.content == {"summary": "s", "analyses": []}
    assert (result.usage.input_tokens, result.usage.output_tokens) == (12, 3)


def test_an_answer_without_usage_has_no_tokens(wire: Any) -> None:
    _, result = _complete(wire, lambda _: wire.answer("{}", usage=None))

    assert result.usage is None


def test_the_call_carries_the_settings_and_both_prompts(wire: Any) -> None:
    """S5-9 and NFR-13: temperature 0 by default; the model only comes from LLM_MODEL."""
    server, _ = _complete(wire, lambda _: wire.answer("{}"))

    (request,) = server.requests
    assert wire.sent(request) == {
        "model": "test-model",
        "temperature": 0,
        "max_tokens": 1024,
        "system": SYSTEM,
        "user": USER,
    }


@pytest.mark.parametrize(
    ("status", "error"),
    [
        (401, ProviderAuthError),
        (403, ProviderAuthError),
        (400, ProviderBadRequest),
        (404, ProviderBadRequest),
        (429, ProviderRateLimited),
        (500, ProviderUnavailable),
        (503, ProviderUnavailable),
    ],
)
def test_each_status_is_its_own_error_after_one_request(
    wire: Any, status: int, error: type[Exception]
) -> None:
    """D243: one request, because the service owns the retries. No message quotes the body."""
    server, raised = _failing(wire, lambda _: wire.failure(status), error)

    assert len(server.requests) == 1
    assert SENTINEL not in str(raised)


@pytest.mark.parametrize(
    ("header", "seconds"), [({"retry-after": "2"}, 2.0), ({}, None), ({"retry-after": "Wed"}, None)]
)
def test_a_429_carries_its_retry_after_in_seconds(
    wire: Any, header: dict[str, str], seconds: float | None
) -> None:
    """D248."""
    _, raised = _failing(wire, lambda _: wire.failure(429, header), ProviderRateLimited)

    assert isinstance(raised, ProviderRateLimited)
    assert raised.retry_after == seconds


def test_a_connection_error_is_unavailable(wire: Any) -> None:
    def refuse(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ConnectError("refused", request=request)

    _failing(wire, refuse, ProviderUnavailable)


def test_a_read_timeout_is_a_timeout(wire: Any) -> None:
    def hang(request: httpx2.Request) -> httpx2.Response:
        raise httpx2.ReadTimeout("slow", request=request)

    _failing(wire, hang, ProviderTimeout)


@pytest.mark.parametrize("text", ["not json", "[1, 2]", "", None, '"a string"'])
def test_an_answer_that_is_not_one_json_object_is_invalid_output(
    wire: Any, text: str | None
) -> None:
    """D245: it goes to the repair, with the tokens it cost."""
    _, raised = _failing(wire, lambda _: wire.answer(text), ProviderInvalidOutput)

    assert isinstance(raised, ProviderInvalidOutput)
    assert [error["type"] for error in raised.errors] == ["json_invalid"]
    assert raised.usage is not None


def test_an_answer_cut_at_the_token_limit_is_invalid_output(wire: Any) -> None:
    _, raised = _failing(wire, lambda _: wire.answer("{}", finish="length"), ProviderInvalidOutput)

    assert isinstance(raised, ProviderInvalidOutput)
    assert [error["type"] for error in raised.errors] == ["truncated"]


def _valid_answer() -> tuple[dict[str, Any], dict[str, Any]]:
    body = json.loads((FIXTURE / "triage-request.format-errors.json").read_text(encoding="utf-8"))
    request = TriageRequest.model_validate(body)
    prompt = render_context(request, "es")
    return body, asyncio.run(MockProvider().complete_json("", prompt, {})).content


def test_the_api_answers_with_a_real_provider_chosen_by_settings(wire: Any) -> None:
    """docs/07 l.130: only the settings change; the route, the service and the contract do not."""
    body, answer = _valid_answer()
    server = _Server(lambda _: wire.answer(json.dumps(answer)))
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: wire.settings
    app.state.providers[wire.settings] = wire.provider(server)

    with TestClient(app) as client:
        response = client.post("/v1/triage", json=body)
        again = client.post("/v1/triage", json=body | {"options": {"explanationLanguage": "en"}})

    assert response.status_code == 200
    meta = response.json()["meta"]
    assert meta["provider"] == wire.name.value
    assert (meta["llmCalls"], meta["inputTokens"], meta["outputTokens"]) == (1, 12, 3)
    assert again.status_code == 200
    assert len(server.requests) == 2
    assert list(app.state.providers) == [wire.settings]


def test_the_provider_is_closed_when_the_app_stops(wire: Any) -> None:
    """D246: the client lives as long as the app."""
    provider = wire.provider(_Server(lambda _: wire.answer("{}")))
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: wire.settings
    app.state.providers[wire.settings] = provider

    with TestClient(app):
        assert not provider._client.is_closed()

    assert provider._client.is_closed()


def test_an_empty_base_url_is_the_official_endpoint() -> None:
    settings = Settings(llm_provider=LlmProvider.OPENAI_COMPATIBLE, llm_api_key="k")

    provider = OpenAICompatibleProvider(settings)

    assert provider._client.base_url.host == "api.openai.com"


def test_llm_base_url_points_the_client_elsewhere() -> None:
    """AC-17.4: Ollama, Groq, LM Studio..."""
    provider = OpenAICompatibleProvider(_OpenAIWire.settings)

    assert str(provider._client.base_url) == "http://127.0.0.1:9/v1/"


def test_openai_compatible_asks_for_a_json_object() -> None:
    """S5-7."""
    server, _ = _complete(_OpenAIWire(), lambda _: _OpenAIWire().answer("{}"))

    assert json.loads(server.requests[0].content)["response_format"] == {"type": "json_object"}
