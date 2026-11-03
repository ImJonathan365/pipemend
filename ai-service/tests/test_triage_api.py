"""POST /v1/triage over HTTP (AC-08.1, AC-08.2, AC-08.8, AC-17.3) and the errors of docs/06 A.5.

Request inputs are the contract fixtures or written by hand; nothing here reads data/.
"""

import inspect
import json
from pathlib import Path
from typing import Any

import httpx2
import pytest
import structlog
from fastapi.testclient import TestClient

from app import main
from app.api import triage
from app.api.errors import problem_schema
from app.api.triage import get_provider
from app.config import LlmProvider, Settings, get_settings
from app.domain.contracts import ErrorCode, ProblemDetail
from app.domain.triage_prompt import read_context
from app.main import create_app
from app.providers.base import ProviderResult
from app.providers.mock import MockProvider

FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"
INDEX = json.loads((FIXTURES / "index.json").read_text(encoding="utf-8"))
VALID_REQUESTS = [
    e["fixture"] for e in INDEX if e["kind"] == "request" and e["expected"] == "valid"
]
INVALID_REQUESTS = [
    e["fixture"] for e in INDEX if e["kind"] == "request" and e["expected"] != "valid"
]
REQUEST_ID = "0b6c3f5e-7f0f-4a55-9d0e-2a1f0f1b6a10"
OTHER_ID = "8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f"
# Marks a value of the row: it must not leave through an error or a log.
ROW_VALUE = "ROW-VALUE-536520"


def _load(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def _body(**overrides: Any) -> dict[str, Any]:
    return _load("triage-request.format-errors.json") | {"requestId": REQUEST_ID} | overrides


def _client(settings: Settings | None = None, provider: Any = None, **kwargs: Any) -> TestClient:
    app = create_app()
    if settings is not None:
        app.dependency_overrides[get_settings] = lambda: settings
    if provider is not None:
        app.dependency_overrides[get_provider] = lambda: provider
    return TestClient(app, **kwargs)


def _problem(response: httpx2.Response, status: int, code: str) -> ProblemDetail:
    """Every emitted error has to be valid against ProblemDetail, the model behind the spec."""
    assert response.status_code == status
    assert response.headers["content-type"] == "application/problem+json"
    problem = ProblemDetail.model_validate(response.json())
    assert problem.status == status
    assert problem.code == code
    assert problem.type == "https://pipemend.dev/problems/" + code.lower().replace("_", "-")
    assert problem.title
    assert isinstance(problem.detail, str)
    assert problem.detail
    assert problem.instance == response.request.url.path
    assert problem.request_id == response.headers["X-Request-Id"]
    return problem


class _Canned:
    def __init__(self, content: dict[str, Any]) -> None:
        self.content = content

    async def complete_json(
        self, _system: str, _user: str, _schema: dict[str, Any]
    ) -> ProviderResult:
        return ProviderResult(self.content, None)


class _FailsInside:
    """A provider that breaks with the very ValueError read_context raises: an internal failure."""

    async def complete_json(
        self, _system: str, _user: str, _schema: dict[str, Any]
    ) -> ProviderResult:
        read_context(f"no block here {ROW_VALUE}")
        raise AssertionError("read_context should have raised")


class _FailsQuotingTheRow:
    """A failure whose message quotes the row, as a validation error of a model would."""

    async def complete_json(
        self, _system: str, _user: str, _schema: dict[str, Any]
    ) -> ProviderResult:
        raise ValueError(ROW_VALUE)


def test_the_route_does_not_block_the_event_loop() -> None:
    """docs/09 7.6 and NFR-10."""
    assert inspect.iscoroutinefunction(triage.triage)


@pytest.mark.parametrize("name", VALID_REQUESTS)
def test_every_valid_request_of_the_contract_gets_its_analyses(name: str) -> None:
    """AC-08.1 over HTTP."""
    sent = _load(name)

    response = _client().post("/v1/triage", json=sent)

    assert response.status_code == 200
    body = response.json()
    assert body["requestId"] == sent["requestId"]
    assert body["recordDecision"] in {"AUTO_FIXABLE", "NEEDS_HUMAN_REVIEW"}
    assert body["maxSeverity"] in {"LOW", "MEDIUM", "HIGH", "CRITICAL"}
    assert body["summary"]
    assert [a["violationId"] for a in body["analyses"]] == [
        v["violationId"] for v in sent["violations"]
    ]
    for analysis in body["analyses"]:
        assert {
            "explanation",
            "severity",
            "classification",
            "confidence",
            "correction",
            "suggestedHumanAction",
        } <= analysis.keys()


def test_the_explanation_follows_the_language_of_the_request() -> None:
    """AC-08.1: AI_EXPLANATION_LANGUAGE is only the default."""
    client = _client()

    spanish = client.post("/v1/triage", json=_body(options={"explanationLanguage": "es"}))
    english = client.post("/v1/triage", json=_body(options={"explanationLanguage": "en"}))

    assert spanish.json()["summary"] != english.json()["summary"]


def test_meta_and_null_fields_stay_present_in_the_json() -> None:
    """AC-08.2, and docs/06 declares these as null, not as absent."""
    body = _client().post("/v1/triage", json=_body()).json()

    assert body["meta"] == {
        "provider": "mock",
        "model": "mock-rules-v1",
        "promptVersion": "triage_v1",
        "latencyMs": body["meta"]["latencyMs"],
        "cacheHit": False,
        "inputTokens": None,
        "outputTokens": None,
        "llmCalls": 0,
    }
    assert body["analyses"][0]["suggestedHumanAction"] is None


def test_a_null_correction_stays_present_in_the_json() -> None:
    body = _client().post("/v1/triage", json=_load("triage-request.missing-price.json")).json()

    assert body["analyses"][0]["correction"] is None


def test_the_daily_cap_does_not_apply_to_the_mock() -> None:
    """AC-08.8 over HTTP: never a 429 with LLM_PROVIDER=mock."""
    client = _client(Settings(llm_max_calls_per_day=1))

    for language in ("es", "en"):
        for row in (1, 2):
            sent = _body(options={"explanationLanguage": language})
            sent["record"] = sent["record"] | {"sourceRowNumber": row}
            assert client.post("/v1/triage", json=sent).status_code == 200


def test_a_second_identical_request_is_a_cache_hit() -> None:
    """AC-08.4 a over HTTP. The same analyses, and only meta tells them apart."""
    client = _client()

    first = client.post("/v1/triage", json=_body()).json()
    second = client.post("/v1/triage", json=_body()).json()

    assert first["meta"]["cacheHit"] is False
    assert second["meta"]["cacheHit"] is True
    assert second["meta"]["llmCalls"] == 0
    assert second["analyses"] == first["analyses"]


def test_each_app_has_its_own_cache() -> None:
    """The isolation the other tests rely on."""
    assert _client().post("/v1/triage", json=_body()).json()["meta"]["cacheHit"] is False
    assert _client().post("/v1/triage", json=_body()).json()["meta"]["cacheHit"] is False


def test_info_counts_the_entries_of_the_cache_the_route_uses() -> None:
    """docs/06 A.4."""
    client = _client()
    assert client.get("/v1/info").json()["cache"]["entries"] == 0

    client.post("/v1/triage", json=_body())

    assert client.get("/v1/info").json()["cache"]["entries"] == len(_body()["violations"])


@pytest.mark.parametrize(
    "header",
    [
        pytest.param(REQUEST_ID, id="same"),
        pytest.param(REQUEST_ID.upper(), id="same-in-capitals"),
    ],
)
def test_the_same_uuid_in_header_and_body_is_accepted(header: str) -> None:
    response = _client().post("/v1/triage", json=_body(), headers={"X-Request-Id": header})

    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == header


def test_without_the_header_the_request_is_accepted_and_an_id_is_generated() -> None:
    response = _client().post("/v1/triage", json=_body())

    assert response.status_code == 200
    assert response.headers["X-Request-Id"]


def test_two_different_uuids_are_an_invalid_request() -> None:
    response = _client().post("/v1/triage", json=_body(), headers={"X-Request-Id": OTHER_ID})

    problem = _problem(response, 400, "INVALID_REQUEST")
    assert problem.request_id == OTHER_ID
    assert "errors" not in response.json()


def test_a_header_that_is_not_a_uuid_is_a_validation_error() -> None:
    response = _client().post("/v1/triage", json=_body(), headers={"X-Request-Id": "not-a-uuid"})

    problem = _problem(response, 422, "VALIDATION_ERROR")
    assert problem.request_id == "not-a-uuid"
    assert problem.errors is not None
    assert [(e.loc, e.type) for e in problem.errors] == [
        (["header", "X-Request-Id"], "uuid_parsing")
    ]


@pytest.mark.parametrize("name", INVALID_REQUESTS)
def test_an_invalid_request_of_the_contract_is_a_validation_error(name: str) -> None:
    _problem(_client().post("/v1/triage", json=_load(name)), 422, "VALIDATION_ERROR")


def test_a_validation_error_says_where_and_what_kind_but_never_what() -> None:
    """Neither msg, input nor ctx; and the keys of record.raw are data too."""
    sent = _body()
    sent["record"] = {
        "sourceRowNumber": 1,
        "raw": {f"Header-{ROW_VALUE}": 5, f"Other-{ROW_VALUE}": 6},
    }
    sent["violations"] = [sent["violations"][0] | {"errorCode": ROW_VALUE}]

    response = _client().post("/v1/triage", json=sent)

    problem = _problem(response, 422, "VALIDATION_ERROR")
    assert ROW_VALUE not in response.text
    assert isinstance(response.json()["detail"], str)
    assert response.json()["errors"] == [
        {"loc": ["body", "record", "raw"], "type": "string_type"},
        {"loc": ["body", "violations", 0, "errorCode"], "type": "enum"},
    ]
    assert problem.errors is not None


def test_a_body_that_is_not_json_does_not_come_back() -> None:
    response = _client().post(
        "/v1/triage",
        content=f'{{"a": "{ROW_VALUE}"',
        headers={"content-type": "application/json"},
    )

    _problem(response, 422, "VALIDATION_ERROR")
    assert ROW_VALUE not in response.text


def test_an_invalid_output_is_a_502_that_quotes_nothing() -> None:
    """docs/06 frontier LLM -> ai-service."""
    provider = _Canned({"summary": ROW_VALUE, "analyses": [], "invented": ROW_VALUE})

    response = _client(provider=provider).post("/v1/triage", json=_body())

    _problem(response, 502, "LLM_INVALID_OUTPUT")
    assert ROW_VALUE not in response.text


@pytest.mark.parametrize("provider", [LlmProvider.OPENAI_COMPATIBLE, LlmProvider.ANTHROPIC])
@pytest.mark.parametrize("key", ["", "super-secret-key"])
def test_a_real_provider_is_a_503_with_or_without_key(provider: LlmProvider, key: str) -> None:
    """AC-17.3 without a key; with one, because it does not exist until Sprint 5 (docs/07)."""
    client = _client(Settings(llm_provider=provider, llm_api_key=key))

    response = client.post("/v1/triage", json=_body())

    _problem(response, 503, "PROVIDER_NOT_CONFIGURED")
    assert "super-secret-key" not in response.text


def test_a_real_provider_without_key_is_refused_before_the_factory(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """AC-17.3 must not depend on the factory refusing what does not exist until Sprint 5."""
    monkeypatch.setattr(triage, "create_provider", lambda _settings: MockProvider())
    settings = Settings(llm_provider=LlmProvider.ANTHROPIC, llm_api_key="")

    response = _client(settings).post("/v1/triage", json=_body())

    _problem(response, 503, "PROVIDER_NOT_CONFIGURED")


@pytest.mark.parametrize("raise_server_exceptions", [True, False])
def test_an_internal_failure_of_the_provider_is_a_500_not_a_503(
    raise_server_exceptions: bool,
) -> None:
    """With True, TestClient would raise if the exception were re-raised after the response."""
    client = _client(provider=_FailsInside(), raise_server_exceptions=raise_server_exceptions)

    response = client.post("/v1/triage", json=_body(), headers={"X-Request-Id": REQUEST_ID})

    problem = _problem(response, 500, "INTERNAL_ERROR")
    assert problem.request_id == REQUEST_ID
    assert response.headers["X-Request-Id"] == REQUEST_ID
    assert ROW_VALUE not in response.text
    assert "ValueError" not in response.text
    assert "triage_context" not in response.text


def test_the_message_of_an_internal_failure_stays_out_of_the_body() -> None:
    response = _client(provider=_FailsQuotingTheRow()).post("/v1/triage", json=_body())

    _problem(response, 500, "INTERNAL_ERROR")
    assert ROW_VALUE not in response.text


def test_a_500_without_the_header_carries_the_generated_id_in_both_places() -> None:
    response = _client(provider=_FailsInside()).post("/v1/triage", json=_body())

    problem = _problem(response, 500, "INTERNAL_ERROR")
    assert problem.request_id == response.headers["X-Request-Id"]


def test_no_log_line_carries_a_value_of_the_row(monkeypatch: pytest.MonkeyPatch) -> None:
    """NFR-08, at any level: an error, a validation failure and a normal answer."""
    sent = _body()
    sent["record"] = {"sourceRowNumber": 1, "raw": {"Invoice": ROW_VALUE}}
    invalid = _body(violations=[sent["violations"][0] | {"errorCode": ROW_VALUE}])
    client = _client(provider=_FailsQuotingTheRow())
    mock_client = _client()

    capture = structlog.testing.LogCapture()
    monkeypatch.setattr(main, "_log", structlog.wrap_logger(None, processors=[capture]))

    client.post("/v1/triage", json=sent)
    client.post("/v1/triage", json=invalid)
    mock_client.post("/v1/triage", json=sent)

    logs = capture.entries
    assert ROW_VALUE not in json.dumps(logs, default=str)
    events = [line["event"] for line in logs]
    assert events.count("http_request") == 3
    assert events.count("unhandled_error") == 1
    failure = next(line for line in logs if line["event"] == "unhandled_error")
    assert failure["exceptionType"] == "ValueError"
    assert failure["log_level"] == "error"
    assert failure["frames"]


@pytest.mark.parametrize("header", [REQUEST_ID, None])
def test_an_unknown_path_is_a_problem(header: str | None) -> None:
    headers = {"X-Request-Id": header} if header else {}

    response = _client().get("/v1/nothing", headers=headers)

    problem = _problem(response, 404, "NOT_FOUND")
    if header:
        assert problem.request_id == header


@pytest.mark.parametrize("header", [REQUEST_ID, None])
def test_a_wrong_method_is_a_problem_and_keeps_its_allow_header(header: str | None) -> None:
    headers = {"X-Request-Id": header} if header else {}

    response = _client().get("/v1/triage", headers=headers)

    problem = _problem(response, 405, "METHOD_NOT_ALLOWED")
    assert response.headers["Allow"] == "POST"
    if header:
        assert problem.request_id == header


def test_the_route_declares_every_code_of_docs_06_a5_only_as_problem_json() -> None:
    spec = create_app().openapi()
    responses = spec["paths"]["/v1/triage"]["post"]["responses"]

    assert set(responses) == {"200", "400", "422", "429", "500", "502", "503", "504"}
    # app.openapi() drops every null default of a document, also this one.
    expected = problem_schema()
    del expected["properties"]["errors"]["default"]
    for status, response in responses.items():
        if status != "200":
            assert list(response["content"]) == ["application/problem+json"]
            assert response["content"]["application/problem+json"]["schema"] == expected
    assert "HTTPValidationError" not in spec["components"]["schemas"]


def test_the_inline_schema_comes_from_the_model_and_has_no_loose_reference() -> None:
    schema = problem_schema()

    assert "$ref" not in json.dumps(schema)
    assert "$defs" not in schema
    assert set(schema["properties"]) == set(
        ProblemDetail.model_json_schema(by_alias=True)["properties"]
    )
    assert schema["properties"]["requestId"]["type"] == "string"
    item = schema["properties"]["errors"]["anyOf"][0]["items"]
    assert set(item["properties"]) == {"loc", "type"}


def test_the_declared_descriptions_name_every_code_of_docs_06_a5() -> None:
    responses = create_app().openapi()["paths"]["/v1/triage"]["post"]["responses"]
    described = " ".join(r["description"] for r in responses.values())

    for code in (
        "INVALID_REQUEST",
        "VALIDATION_ERROR",
        "RATE_LIMITED",
        "LLM_BUDGET_EXHAUSTED",
        "INTERNAL_ERROR",
        "LLM_INVALID_OUTPUT",
        "LLM_PROVIDER_ERROR",
        "PROVIDER_NOT_CONFIGURED",
        "LLM_TIMEOUT",
    ):
        assert code in described


def test_every_error_code_of_the_catalogue_gets_an_answer_from_the_route() -> None:
    """No code of docs/02 section 3 can reach a 500 through the mock."""
    client = _client()
    for code in ErrorCode:
        violation = _body()["violations"][0] | {"errorCode": code.value}
        assert client.post("/v1/triage", json=_body(violations=[violation])).status_code == 200
