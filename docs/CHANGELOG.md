# Changelog del proyecto

Registro semanal (ver `07-plan-de-sprints.md` §6). Formato: hecho / recortado / pendiente / decisiones.

## Sprint 0 — Documentación (2026-09-20)
- Hecho: documentación inicial en `/docs` (visión, requisitos, arquitectura, modelo de datos, contratos, plan, DoD, reglas para agentes, datasets) y ADR-0001 a ADR-0007.
- Pendiente: ADR-0006 (versiones y build) se completa en el Sprint 1.

## Sprint 0 — Revisión de versiones (2026-09-20)
- Decisión: ADR-0006 pasa a `Accepted` con Java 25 (LTS), Spring Boot 4.1.x, Spring Batch 6.x, Gradle (Kotlin DSL), Python 3.14, PostgreSQL 18.
- Motivo: Spring Boot 3.5 llegó a fin de soporte open source el 30/06/2026; Spring Boot 4.x soporta JDK 17–25/26.
- Añadido: `09` §7 bis (tabla de señales Spring Batch 5 → 6) y riesgo explícito de que los asistentes de IA generen código de Batch 5.

## Sprint 0 — Revisión técnica de la documentación (2026-09-27)
- Hecho: auditoría de consistencia de requisitos, modelo de datos, contrato, plan y reglas para agentes. 38 cambios trazables `R-01`…`R-38` (detalle en `REVISION-TECNICA.md`).
- Decisiones propuestas: ADR-0008 (verificadores anti-alucinación) y ADR-0009 (reenvío de `REJECTED` + reconciliación), ambos `Proposed`, **pendientes de aceptación del owner antes del Sprint 1**.
- Plan: reestimación realista 82–102 h frente a 64–80 h de capacidad; se agregan puntos de control (`07` §4 bis). El alcance del MVP no cambia.
- Pendiente del owner: aceptar/rechazar ADR-0008 y ADR-0009; revisar las "decisiones abiertas" de `REVISION-TECNICA.md` §4.

## Sprint 1 — Esqueleto del repositorio (2026-09-28)
- Hecho: `.gitignore` creado **antes** de versionar cualquier archivo; `/docs`, `AGENTS.md` y `CLAUDE.md`
  versionados; `.env.example` con las 34 variables de `04` §8 y sus defaults documentados; README raíz
  mínimo; `mise.toml` con el toolchain fijado (Java 25 / Python 3.14 / uv 0.12.19).
- Decisiones:
  - D1: `ai-service` usa **uv** (`pyproject.toml` + `uv.lock` versionados, dependencias de desarrollo con
    `uv add --dev`, comandos con `uv run`; `astral-sh/setup-uv` en CI y `uv sync --frozen --no-dev` en el
    Dockerfile). | Alternativas: pip + requirements.txt, Poetry | Motivo: lockfile reproducible y resolución
    rápida, sin cambiar ninguna versión mayor de runtime de ADR-0006 | Reversible: sí | Aprobado por: owner.
  - D2: `AI_REQUEST_DEADLINE_SECONDS` aparece **una sola vez** en `.env.example` (sección compartida), no
    duplicada por servicio como en la tabla de `04` §8, porque NFR-07 exige definirla una vez y pasarla a
    ambos servicios. | Reversible: sí.
  - D3: `mise.toml` fija `UV_PYTHON_PREFERENCE=only-system` para que uv use el Python de mise en lugar de
    descargar uno propio. | Reversible: sí | Aprobado por: owner.
- Archivos protegidos modificados: `.env.example` (valores por defecto) y `AGENTS.md` / `docs/CHANGELOG.md`
   — con aprobación explícita del owner en la sesión. Los defaults son los ya documentados en `04` §8:
  no se cambió ninguno.
- Pendiente en el Sprint 1: ADR-0008/0009 a `Accepted`; CI mínima; esqueletos de `pipeline-service` y
  `ai-service`; `docker-compose.yml`; descarga y perfilado del dataset con el congelado de schema, catálogo
  y alias; FR-19 (generador de datos sucios).

## Sprint 1 — ADR-0008 y ADR-0009 aceptados (2026-09-28)
- Decisión del owner: ADR-0008 (verificadores que recalculan y rechazan ambigüedad) y ADR-0009 (reenvío de
  lotes `REJECTED` + reconciliación de arranque) pasan de `Proposed` a **`Accepted`**. Queda resuelto el
  "Pendiente del owner" de la entrada del 2026-09-27.
- Efecto: se implementa con normalidad lo que dependía de ellos — `country-aliases.v1.yaml`, los verificadores
  con rechazo de ambigüedad (`AMBIGUOUS_DATE`, `AMBIGUOUS_NUMBER`, `CURRENCY_NOT_CONVERTIBLE`,
  `UNVERIFIABLE_MAPPING`), AC-05.7, la suite adversarial AC-10.6, el índice único parcial
  `uq_batch_checksum_active`, AC-04.3b y AC-04.6.
- Archivos protegidos modificados (todos con aprobación explícita del owner en la sesión):
  - `docs/adr/0008-*.md`, `docs/adr/0009-*.md`: `Estado` → `Accepted`, `Aprobado por: owner (2026-09-28)`.
    `Fecha` se mantiene en 2026-09-27, que es la fecha de redacción y la que referencia `REVISION-TECNICA.md`.
  - `docs/adr/0003-*.md`, `docs/adr/0007-*.md`: **solo** la línea de metadatos que apunta a ADR-0008/0009,
    `(Proposed)` → `(Accepted)`. Sustancia intacta; mismo criterio que `REVISION-TECNICA.md` §6, que ya trató
    esa línea como metadato.
  - `docs/README.md`: la nota de "Estado transitorio" pasa a declararlos aceptados. Antes prohibía
    implementar lo que dependiera de ellos, lo que habría bloqueado los Sprints 1 y 4.
  - `docs/REVISION-TECNICA.md`: una línea de cierre en §5; la tabla se conserva como registro histórico.
- Sin cambios de código ni de contrato: ninguna decisión congelada de `09` §2 se modifica, y ambos ADR ya
  estaban incorporados en `02`, `03`, `04`, `05`, `06`, `08` y `10`.

## Sprint 1 — Esqueleto de pipeline-service (2026-09-28)
- Hecho: proyecto Gradle (Kotlin DSL) con toolchain Java 25, Spring Boot 4.1.1, Actuator, Flyway
  configurado sin migraciones, logs JSON estructurados y Dockerfile multi-stage con usuario no-root.
- Versiones de parche fijadas, como pide ADR-0006 para el Sprint 1 (verificadas en Maven Central el
  2026-09-28): Spring Boot **4.1.1** (última estable de la rama 4.1), que gestiona Spring Framework 7.0.9,
  **Spring Batch 6.0.5**, Flyway 12.4.0, driver PostgreSQL 42.7.13, Testcontainers 2.0.5 y Micrometer 1.17.1.
  Gradle 9.8.0. Spotless 8.9.0 con palantir-java-format 2.100.0.
  **Nota para el Sprint 2:** el `V1__spring_batch_schema.sql` debe copiarse del jar de Spring Batch **6.0.5**.
- Decisiones:
  - D4: el Sprint 1 **no** incluye Spring Batch. Añadir `spring-boot-starter-batch-jdbc` sin la migración `V1`
    dejaría la app exigiendo tablas `BATCH_*` inexistentes. | Alternativas: adelantar `V1` al Sprint 1 |
    Motivo: `docs/07` pone migraciones y job en el Sprint 2 | Reversible: sí.
  - D5: **reordenado el Sprint 1**: los esqueletos (d, e) van antes de la CI (c). La CI llamaría a
    `./gradlew test` y `uv run pytest` sobre servicios inexistentes, y su PR nacería rojo, incumpliendo el DoD
    de `docs/08` §1. | Alternativas: job `detect` con `if:` que saltara los jobs; aceptar CI roja dos ramas |
    Motivo: evita guards temporales y PRs rojos; el resto del orden del owner se mantiene | Reversible: sí |
    Aprobado por: owner.
  - D6: Spotless con `palantirJavaFormat` (NFR-17 admite Google Java Format o Palantir). Palantir tolera
    mejor las versiones nuevas del lenguaje y usa 120 columnas. | Reversible: sí (una línea).
  - D7: el Dockerfile instala `curl` explícitamente en la etapa de runtime. `docs/04` §10 pedía verificar en
    el Sprint 1 si la imagen lo trae; instalarlo elimina la duda en vez de depender de la imagen base.
    | Reversible: sí.
  - D8: el test de humo desactiva Flyway y apunta el datasource a un host inexistente; HikariCP conecta de
    forma diferida, así que el contexto carga sin PostgreSQL. Solo prueba el *wiring*; la base real se prueba
    desde el Sprint 2 con Testcontainers. | Reversible: sí.
- Hallazgo relevante para todo el proyecto: **Spring Boot 4 renombró los starters**. Es
  `spring-boot-starter-webmvc` (no `-web`), existe `spring-boot-starter-flyway`, y el antigu
  `spring-boot-starter-test` se dividió en un test-starter por módulo
  (`-webmvc-test`, `-actuator-test`, `-jdbc-test`, `-flyway-test`). Cualquier ejemplo con los nombres
  antiguos es de Boot 3 y se rechaza, igual que el código de Batch 5 (`09` §7 bis).
- Archivos protegidos modificados: ninguno.
- Pendiente en el Sprint 1: (e) esqueleto de `ai-service`, (c) CI, (f) `docker-compose.yml`,
  (g) dataset y perfilado, (h) FR-19.

## Sprint 1 — Esqueleto de ai-service (2026-09-28)
- Hecho: FastAPI con `GET /health` (docs/06 A.3) y `GET /v1/info` (A.4), configuración por variables de
  entorno con pydantic-settings, logs JSON con structlog y correlación por `X-Request-Id`/`X-Batch-Id`,
  Dockerfile multi-stage con uv y usuario no-root, y `ruff`/`mypy --strict`/`pytest` configurados.
- Versiones exactas fijadas (verificadas en PyPI el 2026-09-28, como pide ADR-0006 para el Sprint 1):
  FastAPI 0.141.1, Uvicorn 0.54.0, Pydantic 2.13.5, pydantic-settings 2.15.0, structlog 26.1.0,
  httpx 0.28.1, ruff 0.16.9, mypy 2.3.1, pytest 9.1.1. `uv.lock` commiteado.
- Decisiones:
  - D9: solo se crean los módulos del Sprint 1 (`main.py`, `config.py`, `api/system.py`). No se crean
    `domain/`, `services/`, `providers/` ni `prompts/` vacíos: `docs/01` prohíbe stubs "preparados para".
    | Reversible: sí.
  - D10: `providerConfigured` es `True` para `mock` y `bool(LLM_API_KEY)` para el resto. La excepción de
    `docs/04` §8 ("salvo endpoints locales sin auth") **no** se implementa todavía, porque
    `openai_compatible` no existe hasta el Sprint 5 y adivinar ahora qué cuenta como "local" sería
    especular. | Alternativas: usar `LLM_BASE_URL` no vacío como señal de endpoint local | Motivo: la
    opción conservadora es la estricta (`09` §4.2) | Reversible: sí.
  - D11: `cache.entries` y `limits.llmCallsToday` devuelven 0 porque todavía no hay caché (Sprint 3) ni
    contador diario (Sprint 5). Es el valor verdadero, no un stub. | Reversible: sí.
  - D12: la configuración **no** lee `.env`; solo el entorno del proceso, que compose inyecta por
    servicio (NFR-14). | Reversible: sí.
  - D13: `__version__` vive en `app/__init__.py` y debe coincidir con `version` de `pyproject.toml`; con
    `package = false` el proyecto no se instala, así que `importlib.metadata` no es una opción.
    | Reversible: sí.
  - D14: ruff con `DTZ` y `ASYNC` activados a propósito: `DTZ` sostiene la regla de "nada de `now()`
    implícito" (`09` §6) y `ASYNC` la de "ningún cliente síncrono dentro de un `async def`" (NFR-10).
    | Reversible: sí.
- Archivos protegidos modificados: `AGENTS.md` línea 59 — el comando de tests de `ai-service` pasa a
  `uv run ...`, con aprobación explícita del owner en la sesión. `docs/09` no fija ese comando, así que no
  hay nada que sincronizar allí.
- Pendiente en el Sprint 1: (c) CI, (f) `docker-compose.yml`, (g) dataset y perfilado, (h) FR-19.
- D15: los tests viven en `ai-service/tests/`, nunca dentro de `app/`. Con `COPY app/ app/` en el
    Dockerfile, un test dentro de `app/` viajaría a la imagen de producción importando `pytest` y
    `fastapi.testclient`, que con `--no-dev` no están instalados. Es además la ubicación de `04` §11.
    | Reversible: sí.
  - D16: `[tool.pytest.ini_options] pythonpath = ["."]`. Con `package = false` el proyecto no se instala
    y el modo `prepend` de pytest pone `tests/` en `sys.path`, no la raíz, así que `import app` falla.
    | Alternativas: un `conftest.py` vacío en la raíz; `package = true` con backend de build | Motivo:
    una línea explícita y sin archivo fantasma | Reversible: sí.
  - D17: dependencia de test `httpx2==2.13.1` en lugar de `httpx`. `starlette.testclient` marcó como
    obsoleto el backend httpx 0.x. Verificado: `TestClient` funciona sin advertencias. | Reversible: sí.

## Sprint 1 — CI mínima (2026-10-01)
- Hecho: `.github/workflows/ci.yml` con dos jobs en paralelo. `pipeline-service`: `spotlessCheck`,
  `test` y `bootJar` sobre Temurin 25. `ai-service`: `uv sync --locked`, `ruff check`,
  `ruff format --check`, `mypy app` y `pytest` sobre Python 3.14.
- Versiones de las actions (verificadas el 2026-10-01): `actions/checkout@v7`, `actions/setup-java@v6`,
  `gradle/actions/setup-gradle@v6`, `astral-sh/setup-uv@v10` con uv `0.12.19` (la misma de `mise.toml` y
  del Dockerfile).
- Decisiones:
  - D18: CI con `uv sync --locked`, no `--frozen`. `--locked` afirma que `uv.lock` está sincronizado con
    `pyproject.toml` y falla si no; `--frozen` solo consume el lock sin comprobarlo. Así una dependencia
    editada sin re-lockear no llega a `main`. El Dockerfile conserva `--frozen` porque allí el lock ya
    viene validado por CI. | Reversible: sí.
  - D19: sin filtros por ruta (`paths`). Un PR que solo toca `docs/` ejecuta las dos suites; son segundos
    y evita que un cambio de contrato o de documento pase sin probar nada. | Alternativas: `paths` por
    servicio | Motivo: un filtro mal puesto oculta roturas cruzadas | Reversible: sí.
  - D20: no se activa `continue-on-error` en ningún paso, ni se permite que un job quede en `skipped`
    por defecto. `docs/09` §2.1 lo prohíbe explícitamente para `.github/workflows/*`.
  - D21: `permissions: contents: read` y `concurrency` con `cancel-in-progress`. El workflow no escribe
    nada en el repositorio. | Reversible: sí.
  - D22: el build de las imágenes, la verificación de contrato (AC-18.4) y `gitleaks` **no** se incluyen:
    `docs/07` los asigna al Sprint 8. Se añaden allí, nunca se quitan de aquí.
- Archivos protegidos modificados: `.github/workflows/ci.yml` — creación inicial, con aprobación
  explícita del owner en la sesión. No se quita ningún paso ni se baja ningún umbral.
- Pendiente en el Sprint 1: (f) `docker-compose.yml`, (g) dataset y perfilado, (h) FR-19.
- D23: `astral-sh/setup-uv` se fija a la release exacta `v10.2.0`. La action no publica tags
    flotantes de major más allá de `v7` (`v8`, `v9` y `v10` devuelven 404), así que `@v10` no resuelve y
    el job fallaba antes de ejecutar nada. Las `actions/*` y `gradle/actions` se quedan en major flotante,
    que sí mantienen. | Reversible: sí.

