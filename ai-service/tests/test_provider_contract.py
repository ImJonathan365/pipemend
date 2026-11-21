"""The battery every real provider passes, each with an HTTP double in its own wire format (FR-17).

No network: the SDK gets an httpx2 client whose transport is a function.
"""

import asyncio
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx2
import pytest
import structlog
from fastapi.testclient import TestClient

from app.config import LlmProvider, Settings, get_settings
from app.domain.contracts import TriageRequest
from app.domain.triage_prompt import CONTEXT_CLOSE, CONTEXT_OPEN, render_context
from app.main import create_app
from app.providers.anthropic import AnthropicProvider
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
from app.services import triage_service
from app.services.cache import TriageCache
from app.services.triage_service import LlmProviderError, TriageService

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
    # NFR-13: what each wire sends to make the answer reproducible.
    sampling: dict[str, Any] = {"temperature": 0}  # noqa: RUF012 - read only
    truncated = "length"
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


class _AnthropicWire:
    name = LlmProvider.ANTHROPIC
    # D250: the API rejects temperature, so nothing is sent.
    sampling: dict[str, Any] = {}  # noqa: RUF012 - read only
    truncated = "max_tokens"
    settings = Settings(
        llm_provider=LlmProvider.ANTHROPIC, llm_api_key="test-key", llm_model="test-model"
    )
    _STOP = {"stop": "end_turn", "length": "max_tokens"}  # noqa: RUF012 - read only

    def provider(self, server: _Server, settings: Settings | None = None) -> AnthropicProvider:
        client = httpx2.AsyncClient(transport=httpx2.MockTransport(server))
        return AnthropicProvider(settings or self.settings, http_client=client)

    def answer(
        self,
        text: str | None,
        usage: tuple[int, int] | None = (12, 3),
        finish: str = "stop",
        blocks: list[dict[str, Any]] | None = None,
    ) -> httpx2.Response:
        content = [{"type": "text", "text": text}] if text is not None else []
        body: dict[str, Any] = {
            "id": "m1",
            "type": "message",
            "role": "assistant",
            "model": "test-model",
            "content": (blocks or []) + content,
            "stop_reason": self._STOP.get(finish, finish),
            "stop_sequence": None,
        }
        if usage is not None:
            body["usage"] = {"input_tokens": usage[0], "output_tokens": usage[1]}
        return httpx2.Response(200, json=body)

    def failure(self, status: int, headers: dict[str, str] | None = None) -> httpx2.Response:
        return httpx2.Response(
            status,
            headers=headers,
            json={"type": "error", "error": {"type": "error", "message": SENTINEL}},
        )

    def sent(self, request: httpx2.Request) -> dict[str, Any]:
        body = json.loads(request.content)
        (user,) = body["messages"]
        assert user["role"] == "user"
        sent = {
            "model": body["model"],
            "max_tokens": body["max_tokens"],
            "system": body["system"],
            "user": user["content"],
        }
        return sent | ({"temperature": body["temperature"]} if "temperature" in body else {})


WIRES = [_OpenAIWire(), _AnthropicWire()]


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
    """S5-9 and NFR-13: temperature 0 by default, none with anthropic (D250); the model only
    comes from LLM_MODEL."""
    server, _ = _complete(wire, lambda _: wire.answer("{}"))

    (request,) = server.requests
    assert (
        wire.sent(request)
        == {
            "model": "test-model",
            "max_tokens": 1024,
            "system": SYSTEM,
            "user": USER,
        }
        | wire.sampling
    )


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


def _anthropic(respond: Responder, schema: dict[str, Any]) -> tuple[_Server, ProviderResult]:
    server = _Server(respond)
    provider = _AnthropicWire().provider(server)
    return server, asyncio.run(provider.complete_json(SYSTEM, USER, schema))


def test_anthropic_asks_for_the_schema_it_receives_and_nothing_else() -> None:
    """D251 and D253: structured output, no tools or prefill, no sampling, thinking or effort."""
    schema = {"type": "object", "properties": {"summary": {"type": "string"}}}

    server, _ = _anthropic(lambda _: _AnthropicWire().answer("{}"), schema)

    body = json.loads(server.requests[0].content)
    assert body["output_config"] == {"format": {"type": "json_schema", "schema": schema}}
    assert set(body) == {"model", "max_tokens", "system", "messages", "output_config"}


def test_anthropic_reads_the_json_from_the_text_block_after_a_thinking_block() -> None:
    """A model that thinks by default answers with its thinking first (D253)."""
    thinking = [{"type": "thinking", "thinking": "", "signature": "sig"}]

    _, result = _anthropic(lambda _: _AnthropicWire().answer('{"a": 1}', blocks=thinking), {})

    assert result.content == {"a": 1}
    assert result.stop_reason == "end_turn"


def test_anthropic_a_refusal_is_a_bad_request() -> None:
    """D252: asking again gets the same refusal."""
    _failing(
        _AnthropicWire(),
        lambda _: _AnthropicWire().answer("", finish="refusal"),
        ProviderBadRequest,
    )


def test_anthropic_a_refusal_is_a_502_after_one_call_without_repair() -> None:
    body, _ = _valid_answer()
    wire = _AnthropicWire()
    server = _Server(lambda _: wire.answer("", finish="refusal"))
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: wire.settings
    app.state.providers[wire.settings] = wire.provider(server)

    with TestClient(app) as client:
        response = client.post("/v1/triage", json=body)

    assert response.status_code == 502
    assert response.json()["code"] == "LLM_PROVIDER_ERROR"
    assert len(server.requests) == 1


def test_a_truncated_answer_carries_its_stop_reason(wire: Any) -> None:
    """D253: the llm_call log shows it."""
    _, raised = _failing(wire, lambda _: wire.answer("{}", finish="length"), ProviderInvalidOutput)

    assert isinstance(raised, ProviderInvalidOutput)
    assert raised.stop_reason == wire.truncated


def test_anthropic_ignores_llm_base_url(monkeypatch: pytest.MonkeyPatch) -> None:
    """D254: LLM_BASE_URL is for openai_compatible only (docs/04 section 8)."""
    monkeypatch.delenv("ANTHROPIC_BASE_URL", raising=False)
    settings = Settings(
        llm_provider=LlmProvider.ANTHROPIC, llm_api_key="k", llm_base_url="http://127.0.0.1:9/v1"
    )

    provider = AnthropicProvider(settings)

    assert provider._client.base_url.host == "api.anthropic.com"


def _logged_bad_request(
    wire: Any, respond: Responder, monkeypatch: pytest.MonkeyPatch
) -> dict[str, Any]:
    capture = structlog.testing.LogCapture()
    monkeypatch.setattr(triage_service, "_log", structlog.wrap_logger(None, processors=[capture]))
    body, _ = _valid_answer()
    service = TriageService(wire.provider(_Server(respond)), wire.settings, TriageCache(10, 60))
    with pytest.raises(LlmProviderError):
        asyncio.run(service.triage(TriageRequest.model_validate(body)))
    (call,) = [log for log in capture.entries if log["event"] == "llm_call"]
    assert call["outcome"] == "bad_request"
    assert SENTINEL not in str(capture.entries)
    return call


@pytest.mark.parametrize("status", [400, 404])
def test_a_bad_request_logs_its_http_status(
    wire: Any, status: int, monkeypatch: pytest.MonkeyPatch
) -> None:
    """D253: a rejected schema (400) and an unknown model (404) apart in B7."""
    call = _logged_bad_request(wire, lambda _: wire.failure(status), monkeypatch)

    assert (call["httpStatus"], call["stopReason"]) == (status, None)


def test_anthropic_a_refusal_logs_its_stop_reason(monkeypatch: pytest.MonkeyPatch) -> None:
    wire = _AnthropicWire()

    call = _logged_bad_request(wire, lambda _: wire.answer("", finish="refusal"), monkeypatch)

    assert (call["httpStatus"], call["stopReason"]) == (None, "refusal")
