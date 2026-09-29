# PipeMend

**AI-Assisted Self-Healing ETL Pipeline** — an ETL pipeline that, when a record fails validation,
asks an LLM to **explain the error and propose** a fix, and then lets **deterministic code decide**
whether that fix is applied. Anything that is not safe to fix goes to quarantine with a human-readable
diagnosis, not a stack trace.

> **The AI proposes, the pipeline decides.** PipeMend never invents data: a missing or implausible value
> is never filled in automatically. Every fix goes through an allowlist-based policy, a deterministic
> verifier that **recomputes** the value and rejects ambiguity, and a full revalidation.

## Stack

| Component | Technology |
|---|---|
| `pipeline-service` | Java 25 (LTS) · Spring Boot 4.1 · Spring Batch 6 · Spring JDBC + Flyway · Gradle (Kotlin DSL) |
| `ai-service` | Python 3.14 · FastAPI · Pydantic v2 · uv |
| Database | PostgreSQL 18 |
| Local runtime | Docker Compose |
| LLM provider | `mock` by default (no API key, no network); `openai_compatible` and `anthropic` via environment variable |

Versions are pinned in [ADR-0006](docs/adr/0006-versiones-y-build.md).

## Getting started

```bash
cp .env.example .env
docker compose up --build
```

| What | Where |
|---|---|
| Pipeline API (Swagger UI) | http://localhost:8080/swagger-ui.html |
| AI service (Swagger UI) | http://localhost:8000/docs |
| Health | http://localhost:8080/actuator/health · http://localhost:8000/health |

No API key is required: the default provider is `mock`, which is deterministic and makes no outbound calls.
`docker compose down -v` resets the system to a clean state.

## Documentation

The full design lives in [`docs/`](docs/README.md) (written in Spanish): requirements with acceptance
criteria, architecture, data model, API contracts, sprint plan and
[Architecture Decision Records](docs/adr/).

Contributors — human or AI agent — should read [AGENTS.md](AGENTS.md) and
[docs/09-reglas-para-agentes-ia.md](docs/09-reglas-para-agentes-ia.md) first.

## Status

**Work in progress: Sprint 1 of 8** ([plan](docs/07-plan-de-sprints.md)). Both services and
`docker-compose.yml` are being added during this sprint, so the quickstart above does not work on `main` yet.
The full README (architecture diagram, 5-minute demo, evaluation results against ground truth,
known limitations and roadmap) is a Sprint 8 deliverable.

## Dataset and license

This project uses the *Online Retail II* dataset (Chen, D., 2012), available from the
[UCI Machine Learning Repository](https://archive.ics.uci.edu/dataset/502/online+retail+ii) under the
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) license. Defects in the `dirty-*` files were
synthetically injected by the project's generator.
