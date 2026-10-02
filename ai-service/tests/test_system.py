from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.config import LlmProvider, Settings, get_settings
from app.main import create_app


@pytest.fixture
def client() -> Iterator[TestClient]:
    app = create_app()
    with TestClient(app) as test_client:
        yield test_client


def _client_with(settings: Settings) -> TestClient:
    app = create_app()
    app.dependency_overrides[get_settings] = lambda: settings
    return TestClient(app)


def test_health_is_ok_with_the_default_mock_provider(client: TestClient) -> None:
    """NFR-02: the default provider needs no API key and no network."""
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "ok",
        "provider": "mock",
        "providerConfigured": True,
    }


def test_health_is_degraded_for_a_real_provider_without_api_key() -> None:
    """AC-17.3: the service still starts, but reports itself degraded."""
    settings = Settings(llm_provider=LlmProvider.ANTHROPIC, llm_api_key="")

    response = _client_with(settings).get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "degraded"
    assert response.json()["providerConfigured"] is False


def test_info_returns_the_contract_shape_of_docs_06_a4(client: TestClient) -> None:
    """AC-17.5, and camelCase keys as docs/06 requires."""
    body = client.get("/v1/info").json()

    assert body["service"] == "pipemend-ai-service"
    assert body["provider"] == "mock"
    assert body["model"] == "mock-rules-v1"
    assert body["promptVersions"] == {"triage": "triage_v1", "schemaDrift": "schema_drift_v1"}
    assert body["cache"]["maxEntries"] == 5000
    assert body["limits"] == {
        "requestDeadlineSeconds": 25,
        "maxLlmCallsPerDay": 2000,
        "llmCallsToday": 0,
    }


def test_info_never_exposes_the_api_key() -> None:
    """NFR-14: a secret must not reach a response body, even by accident."""
    settings = Settings(llm_provider=LlmProvider.ANTHROPIC, llm_api_key="super-secret-key")

    body = _client_with(settings).get("/v1/info").text

    assert "super-secret-key" not in body


def test_request_id_is_echoed_back(client: TestClient) -> None:
    """NFR-08: the pipeline correlates its logs with ours through this header."""
    sent = "0b6c3f5e-7f0f-4a55-9d0e-2a1f0f1b6a10"

    response = client.get("/health", headers={"X-Request-Id": sent})

    assert response.headers["X-Request-Id"] == sent


def test_a_request_id_is_generated_when_the_caller_sends_none(client: TestClient) -> None:
    response = client.get("/health")

    assert response.headers["X-Request-Id"]
