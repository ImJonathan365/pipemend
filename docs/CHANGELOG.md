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
