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

## Sprint 1 — Docker Compose y corrección de configuración (2026-10-01)
- Hecho: `docker-compose.yml` con los tres servicios de `04` §10, healthchecks y `depends_on` por
  `service_healthy`; `cp .env.example .env && docker compose up --build` levanta el stack (NFR-01).
- Corregido: `application.yml` tenía dos valores truncados al pegarlos. `spring.flyway.locations` decía
  `classpath:db/migra`, y como `fail-on-missing-locations` es `false` por defecto, Flyway **no fallaba**:
  habría ignorado en silencio la `V1` del Sprint 2. Y `management.endpoints.web.exposure.include` decía
  `health,info,metr`, dejando `/actuator/metrics` sin exponer (NFR-09). Ninguna de las dos rompía la CI.
- Decisiones:
  - D24: el healthcheck de `pipeline-service` es `curl -fsS /actuator/health`, no el
    `wget ... | grep -q UP` de `04` §10. La imagen trae `curl` (D7, que resolvió el "verificar en el
    Sprint 1" de ese mismo comentario), y el `grep UP` daría verde falso ante
    `{"status":"DOWN","components":{"db":{"status":"UP"}}}` porque Actuator mapea DOWN a HTTP 503.
    | Reversible: sí.
  - D25: compose pasa a `pipeline-service` todas las `AI_*`/`PIPELINE_*` de `04` §8, aunque varias no
    las lea ningún código hasta los Sprints 2-4. No son stubs de código (prohibidos por `01` X-*), son
    variables de entorno que ese documento prescribe; así la configuración documentada y el stack real
    no divergen. | Reversible: sí.
  - D26: verificado contra el Dockerfile oficial de `docker-library/postgres`: en 18+ `PGDATA` es
    `/var/lib/postgresql/18/docker` y el `VOLUME` es `/var/lib/postgresql`. El volumen se monta ahí.
- Archivos protegidos modificados (todos con aprobación explícita del owner en la sesión):
  - `docker-compose.yml` — creación inicial con los tres servicios de `04` §10, sin añadir ninguno.
  - `docs/04-arquitectura.md` §10 — el healthcheck de referencia pasa de `wget ... | grep -q UP` a
    `curl -fsS`, y el comentario "Verificar en el Sprint 1 si la imagen trae wget o curl" se sustituye
    por el resultado de esa verificación. Alinea el documento con el archivo real y elimina un falso
    positivo que cualquiera habría heredado al copiarlo.
  - `docs/04-arquitectura.md` §1 — el diagrama decía `red: pipemend-net`, pero §10 no declara bloque
    `networks`, así que compose crea `pipemend_default`. Pasa a decir `proyecto: pipemend`, que es el
    `name:` real. Sin cambio de comportamiento.
- Pendiente en el Sprint 1: (g) descarga y perfilado del dataset, (h) FR-19.

## Sprint 1 — Descarga y perfilado del dataset (2026-10-02)
- Hecho: proyecto `tools/` con uv (`pyproject.toml` + `uv.lock`), `tools/download_dataset.py` con
  subcomandos `download` / `convert` / `profile` / `all`, y `docs/perfilado-dataset.md` generado sobre
  1.067.371 filas reales.
- **Cabeceras confirmadas** (tarea del Sprint 1 en `10` §2): el archivo real usa `Invoice`, `StockCode`,
  `Description`, `Quantity`, `InvoiceDate`, `Price`, `Customer ID`, `Country`, exactamente las de `02` §2.
  La ficha de UCI publica las de la versión anterior, que es el caso de drift de FR-05.
- Decisiones:
  - D27: `tools/` es un tercer proyecto uv con lockfile propio, no un grupo dev de `ai-service`.
    `ai-service` es un servicio stateless y no tiene nada que ver con preparar datasets (`04` §6.2), y
    AC-19.3 exige fijar también las versiones de librerías, que solo un lockfile garantiza.
    | Reversible: sí | Aprobado por: owner.
  - D28: lector de xlsx con **openpyxl**, nunca pandas. En un .xlsx todo número es un float; la
    conversión de pandas reintroduciría justo los defectos que `10` §2 paso 2 advierte
    (`Customer ID` 13085 → `"13085.0"`, `Price` 2.55 → 2.5499999999999998). La conversión usa
    `Decimal(repr(v))`, que es el valor exacto de la celda sin redondeo ni artefactos.
    | Reversible: sí.
  - D29: el perfilado se hace sobre el **CSV generado**, no sobre el xlsx, para medir exactamente el
    texto que ingerirá el pipeline. | Reversible: sí.
  - D30: el script **no valida** nada. La autoridad de validación es el validador Java, que lee el
    mismo YAML declarativo; aquí solo se mide cuánto encaja el dato real con las reglas propuestas.
    | Reversible: sí.
  - D31: (g) se parte en dos ramas. El contenido de los tres archivos de schema depende del perfilado
    como evidencia, así que congelarlos va en su propio PR revisable. | Aprobado por: owner.
  - D32: `ruff` con `DTZ001/DTZ005/DTZ007` ignorados **solo en `tools/`**. El dataset no trae zona
    horaria (`05` §1 documenta `invoice_date` como hora local del origen) y una fecha tz-aware
    inventaría información. La exención no alcanza al código de validación, que recibe un `Clock`
    inyectado (`09` §6). | Reversible: sí.
- Archivos protegidos modificados: `AGENTS.md` — dos líneas de comandos para `tools/`, con
  aprobación explícita del owner. `docs/09` no fija esos comandos, así que no hay nada que sincronizar.
- Pendiente: (g2) congelar `countries.v1.txt`, `country-aliases.v1.yaml` y `sales_transaction.v1.yaml`
  con la evidencia del perfilado, y construir las muestras del baseline; luego (h) FR-19.

## Sprint 1 — Congelado del schema v1 (2026-10-04)
- Hecho: `countries.v1.txt` (41 valores), `country-aliases.v1.yaml` (118 alias en 40 países) y
  `sales_transaction.v1.yaml`, en el orden obligatorio de `10` §2 paso 3 bis. A partir de aquí son
  🔴: cambiarlos exige un ADR Accepted.
- Verificado sobre las 1.067.371 filas reales: baseline limpio de **1.063.072 filas (99,60 %)**. Las
  4.299 restantes cuadran con el perfilado: 3.457 `BR-02`, 817 `INVALID_ENUM_VALUE`
  (= 756 `Unspecified` + 61 `European Community`), 18 precios con 3 decimales, 5 precios negativos,
  1 `BR-01` y 1 `stock_code` con espacio final.
- Decisiones:
  - D35: se excluyen del catálogo **solo** `Unspecified` (756 filas) y `European Community` (61). El
    criterio no es "¿es un estado soberano?" sino "¿identifica un destino?": esos dos no dicen dónde
    fue la mercancía, mientras que `West Indies` (54) y `Channel Islands` (1.664) sí, aunque no sean
    estados. Excluirlos mandaría a cuarentena filas legítimas sin que un humano pueda resolverlas.
    | Reversible: no sin ADR | Aprobado por: owner.
  - D36: la regex de `stock_code` se congela tal como la propone `02` §2, sin ajustar. Estaba marcada
    como "ajustable tras perfilado" y la evidencia dice que no hace falta: falla 1 fila de 1.067.371,
    y es precisamente el caso de `TRIM` que se quiere detectar. El margen hasta 20 caracteres se
    mantiene frente a los 12 observados, porque acortarlo rechazaría códigos legítimos futuros sin
    ninguna evidencia. | Aprobado por: owner.
  - D37: `country-aliases.v1.yaml` omite los alias que `normalize` ya resuelve (`Eire`, `Éire`,
    `U.S.A.`). El verificador acepta el par si está en el archivo **o** por igualdad normalizada, así
    que listarlos sería peso muerto. | Reversible: no sin ADR.
  - D38: `England`, `Scotland` y `Wales` **no** se mapean a `United Kingdom`. Nombran lugares
    distintos; mapearlos descartaría información en lugar de reformatearla (ADR-0008). | Reversible:
    no sin ADR.
  - D39: el archivo de alias se estructura por valor de catálogo, no como mapa plano alias→valor, para
    que una colisión (dos países reclamando el mismo alias) se vea en la revisión. El cargador debe
    fallar al arrancar si hay colisión o si una clave no está en el catálogo.
- Cabeceras confirmadas contra el archivo real y regex de `stock_code` cerrada: las dos tareas que
  `02` §2 dejaba abiertas para el Sprint 1 quedan resueltas.
- Archivos protegidos: los tres de `schemas/` nacen aquí y pasan a 🔴 tras este sprint (`09` §2.1).
- Pendiente: las muestras del baseline (paso 4-6 de `10` §2) y luego (h) FR-19.

## Sprint 1 — Muestras del baseline (2026-10-04)
- Hecho: subcomando `samples` en `tools/download_dataset.py` (pasos 4-6 de `10` §2), semilla 42.
  `data/samples/clean-1k.csv`, `raw-natural-2k.csv` y `drift-legacy-headers.csv` versionados;
  `clean-10k.csv` y `clean-100k.csv` en `data/raw/`. Dos ejecuciones dan archivos idénticos (sha256).
- Baseline: 1.063.072 de 1.067.371 filas, la misma cifra y desglose que el congelado del schema.
- Hallazgos naturales medidos: `raw-natural-2k` trae 9 filas inválidas (7 `BR-02`, 1 `BR-01`,
  1 `Unspecified`); `drift-legacy-headers` trae 2 (`BR-02`), lejos del 20 % de AC-05.7.
- Decisiones:
  - D40: enmienda D30. El script filtra el baseline leyendo `sales_transaction.v1.yaml` y
    `countries.v1.txt`, pero no es un validador: solo acepta la forma canónica y aborta ante
    cualquier regla del YAML que no implemente. La autoridad sigue siendo el validador Java, que lo
    comprueba en el Sprint 2 (`clean-1k` → 1000 `DIRECT`) y en AC-19.4. Nueva dependencia de
    `tools/`: PyYAML 6.0.3. | Alternativas: reglas escritas a mano en Python; esperar al validador
    Java | Reversible: sí | Aprobado por: owner.
  - D41: una sola extracción de 100k sobre el baseline, cortada en prefijos (`clean-1k` ⊂ `clean-10k`
    ⊂ `clean-100k`); cada muestra se escribe en el orden original del archivo, y la muestra natural y
    la de drift usan cada una su propio generador con semilla 42. | Reversible: sí.
  - D42: `raw-natural-2k` y `drift-legacy-headers` se muestrean sin filtrar (`10` §2 pasos 5-6).
  - D43: `clean-10k` no se versiona: `10` §5 no lo incluye entre lo que va a git. | Reversible: sí.
- Archivos protegidos modificados: `data/samples/**` (creación), con aprobación explícita del owner.
- Pendiente: (h) FR-19.

## Sprint 1 — Generador de datos sucios (2026-10-06)
- Hecho: FR-19. `tools/dirty_data_generator` inyecta los 20 defectos de `10` §3 con semilla 42
  (`--rate`, `--seed`, `--defects`); sin argumentos regenera `dirty-1k` (50 filas con defectos: 30
  corregibles, 20 no) y `dirty-10k` (500), ambas con su `.labels.csv`. pytest en `tools/` (13 tests:
  AC-19.1, AC-19.3 y AC-19.4 puntos 1-3) y nuevo job `tools` en la CI.
- Decisiones del owner (P1-P6):
  - `DATE_ISO_T` solo si día > 12 o día = mes: con otro día `yyyy-dd-MM` también parsea y el
    verificador de `PARSE_DATE` lo rechazaría con `AMBIGUOUS_DATE`. AC-19.4 y `10` §3 actualizados.
  - `NULL_TOKEN_OPTIONAL` solo en filas sin `customer_id`, para que la corrección `null` coincida con
    `clean_value` (AC-20.3). AC-19.4 y `10` §3 actualizados.
  - El test de integración de AC-19.4 (validador Java sobre cada fila etiquetada) se escribe en el
    Sprint 2, junto con el validador.
  - `dirty-1k.json` se genera en el Sprint 6, con FR-02.
  - El directorio es `dirty_data_generator` (un paquete Python no admite guiones); AC-19.1 corregido.
- Decisiones:
  - D44: en los defectos combinados cada entrada lleva `defect_type` `COMBINED_FIXABLE` o
    `COMBINED_MIXED`; en las filas que van a cuarentena `expected_operation` queda vacío. | Reversible: sí.
  - D45: los tipos se reparten a partes iguales dentro de cada grupo, no al azar, para que `dirty-1k`
    cubra los 20 tipos que el mock debe atender. | Reversible: sí.
  - D46: `FUTURE_DATE` usa el año 2099 conservando mes, día y hora; con el 2031 del ejemplo, la muestra
    se volvería válida en cinco años. | Reversible: sí.
  - D47: `COMBINED_MIXED` nunca incluye `COLUMN_SHIFT` (una fila malformada no se evalúa más), y
    `CANCEL_SIGN_MISMATCH` solo se combina si el defecto corregible no está en `invoice_no` ni en
    `quantity`, porque si no BR-xx no se evalúa (AC-06.3). | Reversible: sí.
  - D48: un test exige que `dirty-1k` versionado coincida byte a byte con el generador (`09` §2.3).
    `dirty-10k` no puede comprobarse en la CI porque su entrada vive en `data/raw/`.
- Archivos protegidos modificados (todos con aprobación explícita del owner en la sesión):
  `data/samples/dirty-{1k,10k}.csv` y sus `.labels.csv` (creación); `.github/workflows/ci.yml` (job
  nuevo, sin quitar pasos); `docs/02` AC-19.1 y AC-19.4; `docs/10` §3; `AGENTS.md` (`uv run pytest` en
  los comandos de `tools/`). El PR supera las ~400 líneas fuera de tests de `09` §2.2 (unas 505, la
  mitad es el catálogo declarativo); el owner aprobó mantenerlo en un solo PR.
- Pendiente: Sprint 1 cerrado en código; queda el tag `v0.1`. Siguiente: Sprint 2.

## Sprint 2 — Validador (2026-10-06)
- Hecho: FR-06. `schema/` carga `sales_transaction.v1.yaml` y `countries.v1.txt` en records (un
  `FieldRule` sellado por tipo) y falla al arrancar ante cualquier clave, tipo o valor que no aplique
  (AC-06.5). `validation/` es Java puro: `Validator` con las reglas de emisión de `02` §3.1, `Clock`
  inyectado (AC-06.7) y `expected` con las claves de `06` A.1. 68 tests en verde.
- Verificación cruzada: el validador acepta las 1.000 filas de `clean-1k` (lado Java de D40) y da en
  `dirty-1k` exactamente los códigos etiquetados en las 48 filas que no son `MALFORMED_ROW`. Las 2 de
  `COLUMN_SHIFT` las cubre el test de AC-19.4 con el lector real, en la rama del job.
- Decisiones:
  - D49: SnakeYAML (la 2.6 que gestiona Boot 4.1.1) declarado explícitamente, en lugar de depender de
    la transitiva; Jackson YAML habría sido una dependencia nueva. | Reversible: sí.
  - D50: `INVALID_FORMAT` frente a `INVALID_TYPE` en números se decide por la forma del número (dígitos
    con un separador decimal, o grupos de tres con un separador distinto del decimal), con signo y,
    solo en `unit_price`, un símbolo `£ $ €`. Es la única lectura que cumple a la vez el texto de
    `02` §3.1 y sus ejemplos (`"2.5.5"` → `INVALID_TYPE` aunque solo tiene dígitos y puntos).
    | Reversible: sí | Aprobado por: owner (2026-10-07).
  - D51: el validador acepta enteros con ceros a la izquierda (`"06"`) y rechaza `"+6"`
    (`INVALID_FORMAT`). El filtro de Python (D40) es más estricto a propósito. | Reversible: sí.
  - D52: las violaciones `BR-xx` llevan `receivedValue = null`, `expected = {"rule": "BR-0n"}` y los
    valores en `technicalMessage`; nunca se cachean (AC-08.4b). | Reversible: sí.
- Archivos protegidos modificados: ninguno.

## Sprint 2 — Migraciones de base de datos (2026-10-19)
- Hecho: FR-13 (parte de base de datos). `spring-boot-starter-batch-jdbc` en el build;
  `V1__spring_batch_schema.sql`, copia byte a byte de `org/springframework/batch/core/schema-postgresql.sql`
  de `spring-batch-core-6.0.5.jar` (comprobada con `cmp` contra el jar de Maven Central, con el SHA-1
  verificado); `V2__core_tables.sql`, idéntico al DDL de `05` §4; `spring.batch.jdbc.initialize-schema=never`.
  Test con Testcontainers (`postgres:18-alpine`): Flyway aplica `V1` y `V2`, existen las tablas y
  secuencias `BATCH_*` y las cinco tablas, y cada `CHECK` de `sales_transaction` que replica el schema
  falla en la BD (SQLState `23514`, con el nombre del constraint) y en el validador con el código
  esperado (AC-13.4, `05` §1). Los límites inclusivos se aceptan en ambos lados.
- Decisiones del owner (2026-10-07):
  - "V2 con las cinco tablas": `V2__core_tables.sql` crea `ingestion_batch`, `sales_transaction`,
    `quarantine_record`, `correction_log` y `ai_call_log`. Quedan adelantadas la tarea "Migración
    `ai_call_log`" del Sprint 3 y la migración de "`correction_log` (migración + writer)" del Sprint 4.
  - "apruebo añadir dev en ci.yml": la CI también se ejecuta en los push a `dev`.
  - "D50 confirmado": D50 pasa a aprobada.
  - "sí, modifica docs/07 para marcar como adelantadas a V2 las migraciones de ai_call_log y
    correction_log": `07` lo refleja en las tareas de los Sprints 2, 3 y 4.
- Decisiones:
  - D53: el test-starter es `spring-boot-starter-batch-jdbc-test` (verificado en Maven Central), pero
    entra en la rama del job, cuando haya un job que lo use. | Reversible: sí | Aprobado por: owner.
  - D54: cada caso del test identifica el `CHECK` por su nombre, no solo por el SQLState, para que no
    pase por un `NOT NULL` u otro constraint. Los `CHECK` de columna llevan el nombre automático de
    PostgreSQL (`sales_transaction_<columna>_check`); solo los `ck_sales_*` tienen nombre explícito.
    | Reversible: sí.
  - D55: el test de humo pasa a Testcontainers. El repositorio JDBC de Spring Batch lee el tipo de base
    de datos de una conexión real al arrancar el contexto, así que el truco de D8 (datasource a un host
    inexistente) deja de funcionar. Comparte configuración con el test de migraciones: un solo
    contexto y un solo contenedor. | Reversible: sí.
  - D56: los `CHECK` sobre columnas que calcula el pipeline (`source_row_number`, `load_origin`,
    `ck_sales_cancellation`, `ck_sales_line_total`) solo se prueban en la BD: el validador no tiene
    contraparte porque no son datos de entrada. | Reversible: sí.
- Cubierto también: la parte de base de datos de AC-04.3b (ADR-0009). Dos lotes activos no pueden
  compartir checksum; los `REJECTED` no lo bloquean. El `409` llega con FR-01.
- El build usa Spotless 8.10.3 (parche posterior a lo registrado en el Sprint 1).
- Archivos protegidos modificados (con aprobación explícita del owner): `.github/workflows/ci.yml`
  (`on.push.branches` pasa de `[main]` a `[main, dev]`; ningún paso quitado), `docs/07` (tareas de
  migración marcadas como adelantadas).
- Pendiente para la rama del job (`feat/FR-13-ingestion-job`):
  - Test que demuestre que un precio con 3 decimales no llega a la BD. `numeric(12,2)` redondea en
    silencio y un `CHECK` no lo detectaría, porque PostgreSQL convierte el valor antes de evaluarlo.
    La defensa es el validador más `setScale(2, RoundingMode.UNNECESSARY)` en el transformador: la
    `ArithmeticException` termina en `PROCESSING_ERROR`, nunca en un redondeo.
  - Test que compruebe filas en `BATCH_JOB_EXECUTION` tras un job (`04` §3.1), con
    `spring-boot-starter-batch-jdbc-test` (D53).

## Sprint 2 — Convenciones del owner en el repositorio (2026-10-20)
- Hecho: `09` §6 amplía las filas "Idioma" y "Git" y añade "Comentarios", "Emojis" y "CI"; `AGENTS.md`
  las resume en inglés en una sección "Conventions". Hasta ahora solo vivían en los prompts del owner.
- Archivos protegidos modificados: `docs/09-reglas-para-agentes-ia.md` y `AGENTS.md`, con aprobación
  explícita del owner: "Apruebo añadir a docs/09 §6 y a AGENTS.md las convenciones del owner: idioma,
  comentarios, emojis, flujo de git con dev y sin atribución."
- Pendiente:
  - Los emojis de advertencia en 05/10 y de estrella en 07 quedan fuera de la regla de emojis; limpieza en un PR docs/ aparte con aprobación.
  - Si el agente puede usar git para leer (`status`, `diff`, `log`): la fila "Git" solo dice que el
    owner ejecuta los comandos que modifican el repo, a la espera de su decisión.

## Sprint 2 — Lector CSV (2026-10-21)
- Hecho: FR-01 (lectura). `batch/RawRecordReader` lee CSV UTF-8 con cabecera y comillas RFC 4180 y
  devuelve `RawRecord.Parsed` o `RawRecord.Malformed`; nunca lanza por el contenido de un registro
  (AC-01.5). Quita el BOM, acepta `\n` y `\r\n` y numera registros lógicos (AC-01.7). Una cabecera vacía
  o repetida, un archivo sin cabecera o un UTF-8 inválido lanzan `InvalidFileFormatException`, que la
  rama del job traducirá a `REJECTED` con `INVALID_FILE_FORMAT` (AC-01.8). Es reiniciable: la posición
  vive en el `ExecutionContext` (la parte de NFR-04 que corresponde al lector).
- AC-19.4 completo: `SampleValidationTest` lee `dirty-1k` con el lector real y comprueba las 50 filas
  etiquetadas, incluidas las 2 de `COLUMN_SHIFT` (`MALFORMED_ROW`), que antes se saltaba.
- Decisión del owner (2026-10-21): "Apruebo partir la rama 3 en 3a, 3b y 3c; que 3b toque FR-13, FR-11
  y FR-12 (DisabledAiTriagePort) y que 3c toque FR-13, FR-05 (solo el rechazo por identidad), NFR-04 y
  NFR-06."
- Decisiones:
  - D57: el lector delega en `FlatFileItemReader` (reinicio y conteo de registros) y separa columnas con
    `DelimitedLineTokenizer` en modo estricto, con los nombres de la cabecera; un número de columnas
    distinto llega como `IncorrectTokenCountException` y se convierte en `Malformed`. Sin dependencias
    nuevas. | Alternativas: un parser propio; Commons CSV | Reversible: sí.
  - D58: se desactivan dos comportamientos por defecto de Batch 6.0.5 que alteran datos en silencio:
    las líneas que empiezan por `#` se descartaban como comentarios, y una línea terminada en `\` se
    unía con la siguiente. | Reversible: sí.
  - D59: un salto de línea dentro de un campo entre comillas llega como `\n`, también si el archivo usa
    `\r\n`. | Reversible: sí.
  - D60: una línea vacía es un registro `MALFORMED_ROW` con 0 columnas, no se ignora; el conteo de
    NFR-06 la cuenta igual porque usa el mismo lector. | Reversible: sí.
  - D61: una comilla sin cerrar hace que el archivo no se pueda partir en registros, así que lanza
    `InvalidFileFormatException` (lote `REJECTED`, ninguna fila cargada) en lugar de `MALFORMED_ROW`.
    | Alternativas: un `MALFORMED_ROW` con todo el resto del archivo | Reversible: sí | Aprobado por:
    owner (2026-10-21), "Acepto el D61."

## Sprint 2 — Procesamiento por registro (2026-10-21)
- Hecho: FR-13 (transformar y cargar), FR-11 (cuarentena, sin IA) y FR-12 en parte. `transform/`
  convierte un registro válido en su fila canónica (AC-13.1) con `setScale(2, UNNECESSARY)`.
  `triage/DisabledAiTriagePort` responde siempre "no disponible". `batch/RecordProcessor` decide un único
  resultado por registro: válido → `DIRECT`; inválido o `MALFORMED_ROW` → cuarentena con
  `AI_UNAVAILABLE`; cualquier `Exception` → cuarentena con `PROCESSING_ERROR` (NFR-06), sin skip policy.
  `persistence/` escribe cada resultado solo en su tabla (AC-13.2, AC-11.1, AC-11.2); un error de BD en
  un writer hace fallar el chunk, nunca acaba en cuarentena.
- Decisiones del owner (2026-10-21):
  - "Apruebo ON CONFLICT DO NOTHING con assertUpdates(false) en SalesTransactionWriter y
    QuarantineWriter, con un test que demuestre que un conflicto real termina en INVARIANT_VIOLATION."
  - "Acepto el D61."
- Decisiones:
  - D62: un writer propio (`ProcessedRecordWriter`) en lugar del `ClassifierCompositeItemWriter` que
    nombra `04` §3. En Batch 6.0.5, este último escribe en el orden en que aparece cada tipo en el chunk,
    y `05` §4 exige escribir primero las filas limpias, porque `correction_log` las referencia.
    | Reversible: sí.
  - D63: la violación `MALFORMED_ROW` lleva `expected = {}` y las columnas esperadas y recibidas en
    `technicalMessage`. AC-07.4 dice que `expected` lleva "columnas esperadas vs recibidas", pero la lista
    de claves de `06` A.1 no tiene ninguna para eso. Se resuelve con el contrato en el Sprint 3.
    | Reversible: sí.
  - D64: `PROCESSING_ERROR` conserva las violaciones encontradas antes de la excepción, y el `summary`
    nombra la excepción. | Reversible: sí.
  - D65: en un campo opcional, el transformador solo convierte en `null` la cadena vacía, igual que el
    validador; cualquier otro token nulo se carga como texto ya validado. | Reversible: sí.
  - D66: sin IA no hay llamada, así que `DisabledAiTriagePort` no escribe en `ai_call_log`, y las
    columnas de IA de `quarantine_record` quedan en `null`. | Reversible: sí.
  - D67: los enums `LoadOrigin` y `QuarantineReason` copian los catálogos completos de `02` §5 y `05`,
    como ya hace `ErrorCode`; ningún valor nuevo. | Reversible: sí.
- Hallazgo (sin cambiar el lector): una comilla suelta dentro de un campo sin comillas, como
  `5" TILE`, abre una sección entre comillas que solo cierra la siguiente comilla. Con una sola, el
  archivo se rechaza (D61). Con dos en filas distintas, las filas intermedias se funden en un único
  `Parsed` y desaparecen sin error. El dataset real tiene 2.493 descripciones con `"`; las muestras las
  escapan bien (`""`), pero un CSV exportado sin escapar perdería filas. Tests en `RawRecordReaderTest`.
  Pendiente de decisión del owner.
- Origen del límite de líneas citado en 3a: `FlatFileItemReader.DEFAULT_MAX_LINES_PER_RECORD = 1_000`,
  que se aplica en el método privado `applyRecordSeparatorPolicy(String)` de
  `spring-batch-infrastructure` 6.0.5.
- Archivos protegidos modificados: ninguno.
- Pendiente para la rama del job (`feat/FR-13-ingestion-job`):
  - El intake recorre el archivo entero con el mismo lector y traduce `InvalidFileFormatException` a
    `REJECTED`/`INVALID_FILE_FORMAT` antes de `processRecordsStep`. Tests de extremo a extremo con un
    UTF-8 inválido al final y con una comilla sin cerrar en la última fila de un archivo de más de 100
    filas: en ambos, 0 filas en `sales_transaction` y en `quarantine_record`.
  - Si la excepción aparece dentro de `processRecordsStep`, el lote termina `FAILED`, nunca `REJECTED`.
  - `schemaCheckStep` propio que solo comprueba la identidad, con la aprobación ya dada por el owner:
    "Apruebo que, hasta FR-05, unas cabeceras distintas de las esperadas dejen el lote REJECTED con
    SCHEMA_DRIFT_UNRESOLVED en un schemaCheckStep propio." Test con `drift-legacy-headers.csv`.
  - Test de que un conflicto real en el `UNIQUE` de la BD termina en `FAILED INVARIANT_VIOLATION`, sin
    manipular contadores.
  - Test de reinicio (AC-04.4), adelantado desde el Sprint 7.

## Sprint 2 — Comillas sueltas en el lector CSV (2026-10-21)
- Corregido: FR-01 (AC-01.5). Una comilla suelta dentro de un campo sin comillas (`5" TILE`) abría
  una sección entre comillas que solo cerraba la siguiente comilla del archivo. Con dos comillas
  sueltas en filas distintas, las filas intermedias se fundían en un único registro válido y se
  perdían sin error, y el invariante de NFR-06 no lo detectaba.
- Decisión del owner (2026-10-21): "Apruebo que una comilla en medio de un campo sin comillas convierta
  esa línea en MALFORMED_ROW, en una rama fix/FR-01-stray-quote antes de 3c."
- Decisiones:
  - D68: el lector sigue RFC 4180 estricto. Una comilla solo abre un campo entre comillas si es su
    primer carácter, y tras la comilla de cierre solo puede venir una coma o el fin del registro.
    Cualquier otra comilla convierte esa línea física en `RawRecord.Malformed` con la línea original,
    y nunca la une con otras. La separación en columnas y el fin de registro salen del mismo método, así
    que aplican la misma regla. Sustituye al `DelimitedLineTokenizer` de D57; `FlatFileItemReader`
    sigue a cargo del reinicio y del conteo. Consecuencia: un espacio antes de una comilla de apertura
    (`a, "b"`) también es una comilla suelta. D61 no cambia: una comilla de apertura que nunca se
    cierra sigue rechazando el lote. `RawRecord.Malformed` pasa a llevar el motivo en texto, que llega
    tal cual a `technicalMessage` (D63). | Alternativas: mantener `DelimitedLineTokenizer` y validar
    las comillas aparte, con dos reglas que podrían divergir | Reversible: sí.
- Archivos protegidos modificados: ninguno.

## Sprint 2 — Casos límite de la cabecera CSV (2026-10-21)
- Corregido: FR-01 (AC-01.8). Desde D68, una primera línea vacía daba una cabecera sin columnas que se
  aceptaba, y cada fila terminaba en `MALFORMED_ROW` en lugar de rechazar el lote con
  `INVALID_FILE_FORMAT`. Ahora lanza `InvalidFileFormatException`.
- Decisiones:
  - D69: la cabecera se rechaza si su separación no termina limpia, ya sea por una comilla suelta o por
    una comilla abierta. El segundo caso no puede darse: en `spring-batch-infrastructure` 6.0.5,
    `FlatFileItemReader.doOpen()` lee las líneas que salta (`setLinesToSkip`) con el mismo método
    privado `readLine()` que los registros, y `readLine()` aplica `applyRecordSeparatorPolicy`. Así, una
    cabecera con un salto de línea entre comillas se lee entera, y si la comilla nunca se cierra el
    archivo se rechaza (D61). La comprobación queda como defensa y hay un test para cada caso.
    | Reversible: sí.
  - El BOM se escribe como `(char) 0xFEFF` en el código y en los tests, porque el formateador
    convertía el escape `\uFEFF` en el carácter literal, que es invisible.
- Archivos protegidos modificados: ninguno.

## Sprint 2 — Job de ingesta (2026-10-21)
- Hecho: FR-13, NFR-04, NFR-06 y FR-05 solo en el rechazo por identidad. `ingestionJob` con los cuatro
  steps de `04` §3, lanzado de forma asíncrona con `JobOperator` sobre un ejecutor de un hilo (NFR-10):
  - `intakeStep` cuenta los registros con el mismo lector antes de procesar nada (NFR-06). Si el
    archivo no se puede leer, el lote termina `REJECTED` con `INVALID_FILE_FORMAT`.
  - `schemaCheckStep` acepta solo cabeceras idénticas (AC-05.1). Si no lo son, el lote termina
    `REJECTED` con `SCHEMA_DRIFT_UNRESOLVED` y un `schema_drift_report`.
  - `processRecordsStep` procesa por chunks de `PIPELINE_CHUNK_SIZE`, sin skip policy ni reintentos.
  - `finalizeStep` comprueba las tres partes del invariante y cierra el lote como `COMPLETED`, o lo
    deja `FAILED` con `INVARIANT_VIOLATION`.
  Tests de extremo a extremo: `clean-1k` → 1000 `DIRECT`; `dirty-1k` → 950 `DIRECT` y 50 en
  cuarentena con `AI_UNAVAILABLE`; filas en `BATCH_JOB_EXECUTION`; cabecera repetida, UTF-8 inválido al
  final y comilla sin cerrar en la última fila → `REJECTED` con 0 filas en las dos tablas;
  `drift-legacy-headers.csv` → `SCHEMA_DRIFT_UNRESOLVED`.
- Adelantado desde el Sprint 7: el test de reinicio (AC-04.4). Un fallo real de la BD en la fila 250
  deja 200 filas confirmadas; `JobOperator.restart` retoma desde la 201 (el step lee 800 registros), sin
  duplicados y con el invariante cumplido.
- Decisiones del owner (2026-10-21):
  - "Apruebo que, hasta FR-05, unas cabeceras distintas de las esperadas dejen el lote REJECTED con
    SCHEMA_DRIFT_UNRESOLVED en un schemaCheckStep propio."
  - "Apruebo actualizar docs/04 §3 y docs/05 para que nombren ProcessedRecordWriter en lugar de
    ClassifierCompositeItemWriter, en la rama 3c."
- Decisiones:
  - D70: el lote pasa a `RUNNING` y guarda su `job_execution_id` en el `JobExecutionListener`
    (`beforeJob`), no en `intakeStep` como dice `04` §3. En un reinicio, `intakeStep` ya está
    `COMPLETED` y no se vuelve a ejecutar, así que el lote se quedaría en `FAILED` mientras se reprocesa.
    Actualizar `04` §3 queda pendiente de aprobación (🟠). | Reversible: sí.
  - D71: "idénticas" significa los mismos nombres, sin uno de más ni de menos, en cualquier orden: las
    columnas se leen por nombre, así que el orden no cambia nada. Una cabecera opcional ausente o una
    cabecera extra también rechazan el lote hasta FR-05 (AC-05.4 y AC-05.8 son de FR-05).
    | Reversible: sí.
  - D72: con cabeceras idénticas se guarda `schema_mapping` con `strategy = IDENTITY` y la forma de
    `05` §5 (AC-05.1). | Reversible: sí.
  - D73: cualquier fallo que no sea el del invariante, incluida una `InvalidFileFormatException`
    dentro de `processRecordsStep`, deja el lote `FAILED` con `INFRASTRUCTURE_ERROR`, nunca `REJECTED`.
    El listener distingue `INVARIANT_VIOLATION` por el tipo de la excepción. | Reversible: sí.
  - D74: enmienda D53. `spring-boot-starter-batch-jdbc-test` no se añade: los tests usan `JobOperator`
    y `JobRepository` directamente, y `@SpringBatchTest` crearía un segundo contexto de Spring y un
    segundo contenedor. La única dependencia nueva es `awaitility`, de test y gestionada por el BOM.
    | Reversible: sí.
  - D75: los tests provocan los fallos con triggers temporales de PostgreSQL limitados al lote del test:
    un `RAISE EXCEPTION` en la fila 250, y una renumeración de la fila 6 a la 5 para provocar un
    conflicto real en el `UNIQUE`. Son errores reales de la BD; no se simula el validador ni los
    writers. | Reversible: sí.
  - D76: los beans `@StepScope` se declaran por su interfaz (`ItemStreamReader`, `ItemProcessor`),
    porque el proxy de clase no puede extender `RawRecordReader` ni `RecordProcessor`, que son `final`.
    | Reversible: sí.
  - D77: propiedades `pipemend.inbox-dir` (por defecto `/data/inbox`, sin variable de entorno nueva) y
    `pipemend.chunk-size`, que lee `PIPELINE_CHUNK_SIZE` con su valor por defecto de 100, el mismo de
    `.env.example`. Además, `spring.batch.job.enabled: false`: Boot lanzaría el job al arrancar, sin
    parámetros. | Reversible: sí.
- Verificado en el código fuente de Spring Boot 4.1.1: un bean `@BatchTaskExecutor` llega al
  `JobOperator` por `BatchJdbcAutoConfiguration.SpringBootBatchJdbcConfiguration.getTaskExecutor()`,
  que lo pasa a `DefaultBatchConfiguration.jobOperator()` (`setTaskExecutor(getTaskExecutor())`, Spring
  Batch 6.0.5).
- Hallazgo: el invariante detecta un conflicto que descarta un registro distinto (falta su número: el
  test lo prueba). No detecta, y no debe detectar, que se reprocese un número ya escrito con el mismo
  contenido: es justo lo que la red de seguridad cubre en un reinicio (AC-04.4).
- Archivos protegidos modificados (con aprobación explícita del owner): `docs/04-arquitectura.md` §3 y
  `docs/05-modelo-de-datos.md` §4 (notas), que ahora nombran `ProcessedRecordWriter` y el orden
  `sales_transaction` → `correction_log` → `quarantine_record`.
- El PR supera las ~400 líneas fuera de tests: unas 536 de Java, de las cuales unas 130 son SQL y la
  configuración del flujo.

## Sprint 2 — Pendientes de documentación (2026-10-22)
- Decisión del owner (2026-10-22): "Apruebo en una rama docs/pending-doc-fixes: actualizar docs/04 §3
  por D70, quitar ⚠ y ⭐ de docs/05, docs/07, docs/10 y del CHANGELOG, y cerrar el pendiente de git
  en lectura."
- Hecho:
  - `04` §3: `intakeStep` ya no marca el lote `RUNNING`. Lo hace el `JobExecutionListener` al empezar
    cada ejecución, también en un reinicio, y guarda el `job_execution_id` (D70). Cierra el pendiente
    de D70.
  - Los emojis de advertencia de `05` y `10` pasan a "Atención:" y la estrella de `07` pasa a "núcleo
    del proyecto", sin cambiar el sentido. El texto pendiente de la entrada del 2026-10-20 se reescribe
    con palabras. Cierra el pendiente de la regla de emojis; la cita literal de la frase del owner, más
    arriba, conserva sus caracteres.
  - Pendiente de git en lectura (entrada del 2026-10-20), cerrado sin cambiar `09`: la fila "Git" dice
    que el owner ejecuta los comandos de git que modifican el repo, así que leer (`status`, `diff`,
    `log`) ya está permitido tal como está escrita.
- Archivos protegidos modificados (con la aprobación del owner citada arriba): `docs/04-arquitectura.md`
  §3, `docs/05-modelo-de-datos.md` §6, `docs/07-plan-de-sprints.md` (Sprint 4) y
  `docs/10-datasets.md` §2.

## Sprint 2 — Endpoint de ingesta (2026-10-22)
- Hecho: FR-01 (AC-01.1 a AC-01.4, AC-01.6) y NFR-20. `POST /api/v1/ingestions` comprueba la extensión,
  guarda el archivo en `inbox/{batchId}/source.csv` mientras calcula el SHA-256, rechaza un archivo
  vacío o con solo cabecera, registra el lote en `RECEIVED` y lanza el job solo con
  `JobOperator.start(ingestionJob, batchId + sourceChecksum)`. Responde `202` con `Location` y el cuerpo
  de `06` B.1. Los errores salen en `problem+json` con `code` y `requestId`: `400 EMPTY_FILE`,
  `400 MISSING_FILE`, `413 FILE_TOO_LARGE` y `415 UNSUPPORTED_MEDIA_TYPE`. Swagger UI en
  `/swagger-ui.html`.
- Decisiones del owner (2026-10-22):
  - "Apruebo añadir springdoc-openapi-starter-webmvc-ui 3.1.1 como dependencia de runtime de
    pipeline-service."
  - "Apruebo que, hasta FR-02, POST /api/v1/ingestions responda 415 a los archivos .json." Hasta
    entonces, el comportamiento es más estricto que AC-01.2, que acepta `.json`.
- Decisiones:
  - D78: el `status` del `202` es `RECEIVED` si el lote sigue en cola y `RUNNING` si el job ya arrancó,
    aunque ya haya terminado: `06` B.1 solo admite esos dos valores, y el estado final lo da la consulta
    del lote (FR-14). | Reversible: sí.
  - D79: la comprobación de archivo vacío lee solo hasta el primer registro, con el lector del job. Un
    archivo que no se puede leer (UTF-8 inválido, cabecera vacía o repetida) no es un `400`: el lote se
    crea y el job lo deja `REJECTED` (AC-01.7, AC-01.8). | Reversible: sí.
  - D80: el `INSERT` del lote se confirma antes de `JobOperator.start`, porque el job corre en otro
    hilo. Endurece el código de 3c: los `UPDATE` de `IngestionBatchRepository` exigen haber actualizado
    exactamente una fila, y si no, lanzan una excepción en lugar de dejar el lote en un estado viejo.
    | Reversible: sí.
  - D81: cualquier fallo después de escribir el archivo borra `inbox/{batchId}/`. Si
    `JobOperator.start` falla, el lote pasa a `FAILED` con `INFRASTRUCTURE_ERROR` y la respuesta es
    `500`. | Reversible: sí.
  - D82: `X-Request-Id` solo se acepta si es un UUID; si no, se genera uno. Va al MDC (`requestId`), a la
    cabecera de respuesta y al `problem+json`. La propagación entre servicios es del Sprint 5.
    | Reversible: sí.
  - D83: el `type` de cada error sigue el patrón documentado en B.1
    (`https://pipemend.dev/problems/<code>`). Los errores propios de Spring (404, 405, tipo de contenido
    incorrecto) conservan su estado y su cuerpo, más el `requestId`, sin inventar códigos.
    | Reversible: sí.
  - D84: `max-file-size` y `max-request-size` salen los dos de `PIPELINE_MAX_UPLOAD_MB`, como pide
    AC-01.4. Un archivo de exactamente 50 MB puede rechazarse, porque la cabecera multipart también
    cuenta. | Reversible: sí.
  - D85: `server.tomcat.max-swallow-size: -1`. Tomcat rechaza una subida demasiado grande a partir de
    su `Content-Length` y, con más de 2 MB sin leer, corta la conexión mientras el cliente sigue
    enviando, así que muchos clientes no llegan a ver el `413` (lo reprodujo el test con el cliente HTTP
    del JDK). Con `-1`, Tomcat lee y descarta el resto antes de responder. Coste: el servidor recibe la
    subida entera aunque la rechace; aceptable en un servicio local sin autenticación (X-02).
    | Alternativas: un tope fijo, a cambio de que las subidas mayores vuelvan a cortarse
    | Reversible: sí | Aprobado por: owner (2026-10-22), "Confirmo D85: server.tomcat.max-swallow-size -1."
  - D86: `source_filename` guarda solo el último segmento del nombre, sin caracteres de control y con 255
    caracteres como máximo (se recorta por el principio y la extensión se conserva). | Reversible: sí.
  - D87: el contenedor de test es estático y lo comparten todos los contextos. El test del endpoint
    necesita un servidor HTTP real (MockMvc no aplica los límites de multipart) y por eso abre un segundo
    contexto. La suite sigue arrancando un solo contenedor. | Reversible: sí.
- TODO(owner-decision): `06` no define `code` para un `500` inesperado. Se responde `problem+json` sin
  `code`, porque añadir uno sería un valor nuevo de contrato (`09` §2.2).
- Pendiente para 4b (`feat/FR-04-duplicate-ingestion`):
  - Traducir el `23505` de `uq_batch_checksum_active` a `409 DUPLICATE_INGESTION`, con
    `existingBatchId` y `existingStatus`. Hasta entonces, un archivo duplicado devuelve `500` sin crear
    lote ni dejar archivo. `dev` no debe pasar a `main` entre 4a y 4b.
  - Para un lote previo `FAILED`, el `detail` dice que el lote anterior falló y que reenviarlo
    duplicaría datos, sin nombrar ninguna ruta. Añadir la sugerencia de restart cuando llegue FR-21, o
    documentar la limitación en el README si FR-21 se recorta.
- Archivos protegidos modificados: ninguno. `build.gradle.kts` recibe una dependencia de runtime nueva
  con aprobación explícita del owner; no cambia ninguna versión mayor.
- Tamaño: unas 435 líneas fuera de tests más esta entrada, frente a las ~360 estimadas. Supera las ~400.

## Sprint 2 — Ingestas duplicadas (2026-10-22)
- Hecho: FR-04 (AC-04.2, AC-04.3, AC-04.3b y AC-04.5).
  - Si `uq_batch_checksum_active` devuelve un `23505`, la respuesta es `409 DUPLICATE_INGESTION` con
    `existingBatchId` y `existingStatus`. Antes no se consulta nada: insertar y traducir el error
    impide que dos subidas simultáneas pasen las dos.
  - Si el lote previo está `FAILED`, el `detail` dice que falló y que reenviarlo duplicaría datos, sin
    nombrar ninguna ruta.
  - Un lote previo `REJECTED` no bloquea: se crea uno nuevo.
  - Dos subidas simultáneas del mismo archivo dan exactamente un `202` y un `409`.
  - El `500` lleva ahora `code = INTERNAL_ERROR`.
- Decisiones del owner (2026-10-22):
  - "Confirmo D85: server.tomcat.max-swallow-size -1."
  - "Apruebo añadir el code INTERNAL_ERROR para el 500 en docs/06 B.1 y contracts/, y anotar en
    docs/02 AC-01.2 que .json responde 415 hasta FR-02." `contracts/` todavía no existe, así que el
    código va solo en `06` B.1; se exportará con el contrato del Sprint 3. Cierra el
    `TODO(owner-decision)` de la 4a.
- Decisiones:
  - D88: enmienda D81. Si `JobOperator.start` falla, el job no llegó a ejecutarse ni escribió nada, así
    que se borran la fila del lote (que sigue en `RECEIVED`) y su directorio, y la respuesta es `500`.
    Antes el lote quedaba `FAILED` y, como `uq_batch_checksum_active` solo excluye los `REJECTED`,
    bloqueaba el checksum para siempre, sin archivo con el que reiniciarlo. Si el lote ya no está en
    `RECEIVED`, no se borra nada. | Reversible: sí.
  - D89: la limpieza del directorio solo cubre lo que pasa antes de que `JobOperator.start` termine
    bien. Después, el directorio pertenece al job y no se borra nunca, aunque falle la respuesta (por
    ejemplo, la lectura del estado). | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner): `docs/06-contrato-api.md` B.1
  (`500 INTERNAL_ERROR`, un cambio compatible del contrato) y `docs/02-requisitos-funcionales.md`
  AC-01.2 (nota sobre `.json` hasta FR-02).
- Pendiente:
  - Añadir la sugerencia de restart al `409` de un lote `FAILED` cuando llegue FR-21, o documentar la
    limitación en el README (`docker compose down -v`) si FR-21 se recorta (AC-04.3).
  - Los errores propios de Spring (404, 405, tipo de contenido incorrecto) salen en `problem+json`
    pero sin `code`. Se resuelve con FR-14 o FR-18.
  - `dev` ya puede pasar a `main`: con esta rama, un duplicado vuelve a ser un `409`.

## Sprint 2 — Reconciliación de arranque (2026-10-22)
- Hecho: FR-04 (AC-04.6). Al arrancar, `pipeline-service` pasa por `JobOperator.recover(...)` cada
  ejecución de `ingestionJob` que quedó `STARTING`, `STARTED` o `STOPPING`. Las busca con
  `JobRepository.findRunningJobExecutions`, sin SQL sobre las tablas `BATCH_*`. Después:
  - un lote `RUNNING` pasa a `FAILED` con `INTERRUPTED`, y su archivo se queda en el inbox para FR-21;
  - un lote `RECEIVED` (en cola) se reencola con `JobOperator.restart(...)`;
  - un lote `RECEIVED` sin ejecución se lanza con `JobOperator.start(...)` (AC-04.6 y ADR-0009 §4);
  - un lote en un estado final no se toca: solo se recupera su ejecución.
  - Test con un apagado real: el estado se siembra en PostgreSQL con los repositorios y después arranca
    un segundo contexto Spring contra la misma BD y el mismo inbox, sin mocks. Un tercer arranque no
    cambia nada.
- Decisión del owner (2026-10-22): "Apruebo que la reconciliación de AC-04.6 corra antes de arrancar
  el servidor web, que los lotes RECEIVED con ejecución en STARTING se recuperen y se reencolen con
  JobOperator.restart, y actualizar docs/04 §3 y la tabla de docs/02 §5."
- Decisiones:
  - D90: un lote `RECEIVED` con una ejecución en curso no ha ejecutado ningún step, porque
    `markRunning` está en `beforeJob` y los steps corren después (`AbstractJob.execute`, 6.0.5). Por
    eso se reencola en vez de quedar `FAILED`, que bloquearía su checksum. Incluye la ejecución en
    `STARTED`: `AbstractJob` la pone en `STARTED` antes de llamar a `beforeJob`. El restart vuelve a
    empezar en `intakeStep`, porque `SimpleStepHandler.shouldStart` ejecuta todo step sin una
    `StepExecution` previa. | Reversible: sí.
  - D91: la reconciliación es un `SmartLifecycle` de fase 0, no un `ApplicationRunner` ni un
    `SmartInitializingSingleton`. Corre antes del servidor web (fase `Integer.MAX_VALUE - 2048`) y
    después de que `MapJobRegistry` registre los jobs en `afterSingletonsInstantiated`, que es lo que
    necesita `restart`. | Reversible: sí.
  - D92: si la reconciliación falla, el arranque falla. Seguir arrancando junto a lotes en un estado
    desconocido sería un "catch que tiene éxito". | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner): `docs/04-arquitectura.md` §3
  (la reconciliación) y `docs/02-requisitos-funcionales.md` §5 (la fila `RECEIVED` de la tabla).
- Pendiente:
  - FR-21: rechazar el restart de un lote `COMPLETED` aunque su ejecución figure `FAILED`. Ocurre si
    el proceso muere entre `afterJob` y el guardado final de Spring Batch (caso D).
  - Los contextos de test comparten la BD, así que cada contexto nuevo también reconcilia lo que
    dejaron otras clases de test. Ningún test lo comprueba hoy, pero conviene saberlo si aparece un
    test inestable.

## Sprint 2 — Aislamiento por lote en la reconciliación de arranque (2026-10-24)
- Hecho: FR-04 (AC-04.6). Un lote que la reconciliación no puede reconciliar ya no detiene el
  arranque.
  - Si su `recover`, `restart` o `start` lanza, se registra un `log.error` y el lote pasa a
    `FAILED` con `INFRASTRUCTURE_ERROR`. Ese UPDATE solo actúa desde `RECEIVED` o `RUNNING`, así que
    nunca pisa un estado final. Luego sigue con el siguiente lote.
  - Una ejecución cuyo `batchId` no se puede leer, o cuyo lote no tiene fila, se recupera igualmente
    con `recover` y deja un `log.error`. No hay lote que marcar.
  - Solo detiene el arranque un fallo de las consultas iniciales (`findRunningJobExecutions`,
    `findReceived`) o del propio UPDATE: los tres significan que la BD no está disponible.
  - Test con los rechazos reales de Spring Batch (`JobInstanceAlreadyCompleteException` en el
    restart y en el start), una ejecución sin fila, otra con un `batchId` ilegible y un lote normal
    que termina `COMPLETED`. Sin mocks del `JobOperator`.
- Decisión del owner (2026-10-24): "Apruebo que la reconciliación de arranque aísle cada lote: si su
  recover, restart o start falla, el lote queda FAILED con INFRASTRUCTURE_ERROR y el arranque sigue;
  solo un fallo de las consultas iniciales detiene el arranque."
- Decisiones:
  - D93: enmienda D92. Con D92, un solo lote que hacía fallar `recover`, `restart` o `start` detenía
    el arranque, y en cada reinicio volvía a encontrarse el mismo lote. La única salida era
    `docker compose down -v` o editar `BATCH_*` a mano, que 09 §3 prohíbe. No es un catch que
    convierta un error en éxito: el lote queda `FAILED` y visible, y la causa queda en el log. No
    puede repetirse en bucle, porque el lote sale de `RECEIVED`/`RUNNING` y su ejecución deja de
    estar en curso. | Reversible: sí.
- Archivos protegidos modificados: ninguno, salvo este CHANGELOG. La fila `INFRASTRUCTURE_ERROR` de
  `02` §5 ya cubre las excepciones no controladas, y D92 solo constaba aquí.

## Sprint 2 — Estado de lotes (2026-10-24)
- Hecho: FR-14 (AC-14.1 a AC-14.4) y el `code` de NFR-20 en los errores del framework.
  - `GET /api/v1/ingestions/{batchId}` devuelve el objeto de `06` B.2. Mientras el lote no está
    `COMPLETED`, los contadores y `aiCalls` salen de `COUNT(*)` sobre las tablas; al terminar, de las
    columnas guardadas (AC-14.2).
  - `GET /api/v1/ingestions?status=&page=&size=` usa la misma forma por elemento y ordena por
    `received_at DESC, id`, así que el orden es estable entre páginas. Usa el índice
    `ix_batch_status_received`, que ya existía.
  - Respuestas de error:
    - `404 BATCH_NOT_FOUND` para un lote inexistente;
    - `400 INVALID_PARAMETER` para un `batchId` que no es UUID, un `status` desconocido, `page` < 0 o
      `size` fuera de 1..200;
    - los errores propios de Spring llevan como `code` el nombre de su estado HTTP.
  - Corrige de paso que un parámetro con un tipo inválido acababa en `500`.
- Decisión del owner (2026-10-24): "Apruebo los DTO de contrato de GET /api/v1/ingestions/{batchId}
  y del listado (cada elemento con la forma de B.2), añadir a docs/06 B los codes BATCH_NOT_FOUND,
  NOT_FOUND, METHOD_NOT_ALLOWED e INVALID_PARAMETER con size > 200 → 400, la regla de que los demás
  errores del framework usan como code el nombre del estado HTTP en mayúsculas, y que esta rama
  incluya el code de NFR-20."
- Decisiones:
  - D94: los contadores de un lote `FAILED`, `REJECTED`, `RECEIVED` o `RUNNING` también salen de
    `COUNT(*)`, porque solo `complete` guarda las columnas. Así, un lote `FAILED` muestra los chunks
    que llegó a confirmar. | Reversible: sí.
  - D95: `read` no tiene caso `null`. `read_count` es `NOT NULL DEFAULT 0` en V2, así que vale `0`
    en `RECEIVED` y en un `REJECTED` del intake sin tratamiento especial. | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner): `docs/06-contrato-api.md` B
  (regla de los errores del framework, los codes nuevos, el límite de `size`, las notas de B.2 y la
  forma del listado) y los DTO de contrato `BatchResponse` y `PageResponse`.
- Pendiente:
  - Cuando llegue FR-08, `complete` debe guardar también `ai_calls_count`. Hoy no lo guarda, y un
    lote `COMPLETED` mostraría `aiCalls = 0`.
  - Cerrado: el `code` en los 404 y 405 de Spring (pendiente de la entrada de ingestas duplicadas).

## Sprint 3 — Contrato de triage (2026-10-24)
- Hecho: FR-08, la parte del contrato (`06` A.1). Todavía no hay ruta `/v1/triage`.
  - `ai-service/app/domain/contracts.py` contiene `TriageRequest` y `TriageResponse`, con sus enums.
    - La request rechaza los campos desconocidos (`extra="forbid"`, `422`). La respuesta los
      ignora, así que añadir un campo opcional sigue siendo compatible (`09` §7.5).
    - Los validadores de la respuesta solo cubren lo que no necesita la request: `correction` ⇔
      `AUTO_FIXABLE`, `suggestedHumanAction` en `NEEDS_HUMAN_REVIEW`, `recordDecision` y
      `maxSeverity` coherentes y ningún `violationId` repetido. El resto de AC-08.7 queda para
      `TriageResponseValidator`, en el pipeline.
  - `contracts/ai-service.openapi.json` se genera con `uv run python -m app.contract_export` (desde
    `ai-service`). Un test lo compara byte a byte con el archivo versionado, así que una
    desincronización hace fallar el paso "Test" del CI sin tocar `ci.yml`.
  - `contracts/fixtures/` contiene 24 fixtures escritas a mano a partir del contrato, sin usar
    `data/` ni `labels.csv`. Cada inválida rompe una sola regla. `index.json` dice, para cada una,
    qué request la acompaña, el resultado esperado y si Pydantic la acepta, para que la usen pytest
    y, más adelante, los tests Java.
- Decisión del owner (2026-10-24): "Apruebo crear ai-service/app/domain/contracts.py con los modelos
  de TriageRequest y TriageResponse de docs/06 A.1, generar contracts/ai-service.openapi.json con
  app.contract_export, escribir a mano las fixtures de contracts/fixtures/, modificar docs/06 para
  añadir a expected las claves expectedColumns y receivedColumns (D63), las filas violationId y
  field a la tabla de la respuesta y la regla de que la request rechaza campos desconocidos con
  422, y alinear docs/02 AC-08.7 con el orden de 06."
  - Respuestas a las preguntas del plan: export con `app.openapi()` más los modelos añadidos;
    orden de las `analyses` opción A (el pipeline también rechaza un orden distinto);
    `violationId` y `field` obligatorios; `extra="forbid"` en la request, escrito en `06` junto a
    las otras dos fronteras; `expectedColumns` y `receivedColumns`.
- Decisiones:
  - D96: cierra D63. `expected` de `MALFORMED_ROW` lleva `expectedColumns` y `receivedColumns`.
    `receivedColumns` es `null` si la línea no se pudo separar en columnas (comilla suelta). El
    pipeline empieza a emitirlas en su rama de FR-07. | Reversible: sí (claves opcionales).
  - D97: los esquemas añadidos se generan como los generará FastAPI 0.141.1 cuando exista la ruta.
    Lo verifiqué en el código fuente de FastAPI:
    - `fastapi/_compat/v2.py`, `get_definitions` (l.285-336): la request en modo `validation` y la
      respuesta en modo `serialization`, porque `separate_input_output_schemas=True` conserva el
      modo de cada campo (l.329);
    - `REF_TEMPLATE = "#/components/schemas/{model}"` (`fastapi/openapi/constants.py:3`);
    - las descripciones se recortan en el primer `\f` (l.337-340).

    Los nombres los pone Pydantic, que solo añade `-Input` o `-Output` cuando un modelo se usa en
    los dos modos. Ningún modelo del contrato está en los dos lados, y el export falla si aparece
    un sufijo. | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner): `ai-service/app/domain/contracts.py`
  (nuevo), `contracts/**` (nuevo), `docs/06-contrato-api.md` (las tres fronteras, A.1) y
  `docs/02-requisitos-funcionales.md` AC-08.7 (el orden).
- Pendiente:
  - Rama de la ruta `POST /v1/triage`: un test que confirme que `build_spec()` es igual a
    `app.openapi()` sin el añadido. Cuando ese test pase, el añadido se puede quitar.
  - Rama del pipeline (FR-07 y AC-08.7): emitir `expectedColumns` y `receivedColumns`, y usar las
    fixtures de `contracts/fixtures/` en `TriageResponseValidator`.

## Sprint 3 — Proveedor LLM y mock (2026-11-01)
- Hecho: FR-17 (AC-17.1, AC-17.2) y la frontera LLM → `ai-service` de `06`. Sigue sin haber ruta
  `/v1/triage`, `triage_service`, `rules.py` ni caché.
  - `ai-service/app/providers/`: protocolo `LLMProvider` asíncrono, `create_provider` y `MockProvider`,
    que cubre los 20 `defect_type` de `10` §3 sin conocerlos: mira `errorCode`, `field`,
    `receivedValue` y `expected`, y nunca `record.raw`.
  - `ai-service/app/domain/llm_output.py`: salida del LLM con `extra="forbid"`.
  - `ai-service/app/domain/triage_prompt.py`: bloque `<triage_context>` con la solicitud como dato.
- Decisión del owner (2026-11-01): "Apruebo crear ai-service/app/domain/llm_output.py con la salida
  del LLM de la decisión 4 aunque esa frontera sea de FR-08 y el PR sea de FR-17, modificar docs/04
  (árbol de §6, async en §9 y timeout con asyncio.timeout en el paso 5 de §6), modificar docs/02
  AC-17.2 para que el mock pueda llevar una tabla fija de alias y tokens nulos en mock.py, añadir la
  entrada al CHANGELOG, copiar en mock.py los alias y los tokens nulos con un test contra los YAML,
  añadir pyyaml==6.0.3 al grupo dev de ai-service/pyproject.toml y actualizar uv.lock, y superar las
  400 líneas en esta rama (hasta ~600)."
- Decisiones:
  - D98: el mock lleva una copia fija de los alias y de los tokens nulos, única excepción al "solo a
    partir de la solicitud" de AC-17.2. No lee archivos en ejecución; un test la compara con los
    YAML de `pipeline-service/src/main/resources/schemas/`. `pyyaml` queda declarado en el grupo
    dev. | Alternativas: solo los pares de ejemplo, que no bastan porque el generador elige
    cualquier alias; leer el YAML, que no está en la imagen de `ai-service`. | Reversible: sí.
  - D99: la solicitud llega al mock dentro del `user_prompt`, porque cambiar la firma de `04` §9
    exige ADR. El JSON del bloque escribe cada `<` como `\u003c`, así que ningún dato puede contener
    el marcador de cierre, tampoco para un LLM real. Sin bloque, el mock lanza una excepción y nunca
    responde con un análisis vacío. `render_context` deja resuelto el idioma, y el mock no consulta
    `Settings`. | Reversible: sí.
  - D100: el timeout es del que llama, con `asyncio.timeout`, y `temperature` es del constructor de
    cada proveedor real. La firma de `04` §9 solo gana `async` (NFR-10, `09` §7.6). | Reversible: sí.
  - D101: la salida del LLM solo tiene `summary` y `analyses`. `recordDecision`, `maxSeverity`,
    `requestId` y `meta` los pone el servicio. No lleva `max_length` (`06` regla 5) ni la comprobación
    `correction` ⇔ `AUTO_FIXABLE` (`06` regla 3). Solo acepta camelCase, y `confidence` es estricto:
    `"0.97"` y `true` son salida inválida. | Reversible: sí.
  - D102: la factory solo tiene la rama de `mock`; cualquier otro valor lanza `ValueError` con su
    nombre. No se invoca al arrancar, así que AC-17.3 no cambia. | Reversible: sí.
  - D103: lo que queda fuera del catálogo (`quantity = "6.0"`, `$2.55`, `2,550`, `2.555`,
    `12/25/2010`) va a humano en `MEDIUM`, por encima del piso de esos códigos. `DATE_AMBIGUOUS`
    también: el mock no propone para que luego rechace el verificador. `MAX_LENGTH_EXCEEDED` va a
    humano en `HIGH`, y un test recorre todos los `ErrorCode`, con valores dentro y fuera del
    catálogo, contra los pisos de `06` A.1. | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner):
  `ai-service/app/domain/llm_output.py` (nuevo), `docs/04-arquitectura.md` (§6 y §9),
  `docs/02-requisitos-funcionales.md` AC-17.2.
- Pendiente:
  - Rama de `triage_service`:
    - comprobar que las `analyses` son exactamente las violaciones enviadas, en el mismo orden;
    - anular `correction` en `NEEDS_HUMAN_REVIEW`: `06` regla 1 solo cubre los códigos no
      corregibles, y `TriageResponse` rechazaría el resto;
    - fijar `llmCalls` con el mock: el ejemplo de `06` dice 0;
    - decidir qué hacer hasta que exista `prompts/triage_v1.md`, que es del Sprint 5, aunque `04` §6
      paso 4 lo use.
  - Sprint 5: hasta entonces, un `LLM_PROVIDER` real termina en `ValueError` al crear el proveedor.
  - Sprint 4 (AC-10.6): la fixture de `contracts/fixtures/` con una instrucción inyectada que pide
    NFR-14. Por ahora, el test del mock lleva la instrucción en línea.

## Sprint 3 — `triage_service`, reglas duras y caché (2026-11-01)
- Hecho: el flujo de `04` §6.2 hasta donde `07` lo lleva en el Sprint 3, con AC-08.3 y AC-08.4 completos
  y AC-08.1, AC-08.2 y AC-08.8 probados contra el servicio. Sin HTTP: el servicio lanza excepciones
  propias y sigue sin haber ruta `/v1/triage`.
  - `ai-service/app/domain/rules.py`: reglas 1, 2, 3, 5 y 6 de `06` A.1 y descarte de caché de AC-08.4 c.
    La regla 4 ya la cumple `LlmCorrection`.
  - `ai-service/app/services/cache.py`: clave de NFR-11 y LRU con TTL.
  - `ai-service/app/services/triage_service.py`: caché, llamada, validación, reglas y `meta`.
  - Cierra los cuatro pendientes de esta rama en la entrada anterior.
- Decisión del owner (2026-11-01): "Apruebo crear ai-service/app/domain/rules.py, modificar
  ai-service/app/domain/contracts.py para añadir las constantes de longitud, modificar docs/04 §6.2
  (nota del paso 4 sobre el prompt de sistema vacío hasta el Sprint 5, alcance de los pasos 1, 3, 5 y 6
  según docs/07, y recorte del paso 7 solo para textos con máximo), y añadir la entrada al CHANGELOG,
  en esta rama y hasta ~450 líneas sin tests."
- Decisión del owner (2026-11-01): "Apruebo modificar docs/09 §7.4 para que remita a la excepción de la
  tabla fija de AC-17.2, y corregir en docs/CHANGELOG.md la línea de D99 para que diga \u003c."
- Decisiones:
  - D104: sin `prompts/triage_v1.md`, el prompt de sistema va vacío. Solo es aceptable porque
    `create_provider` únicamente construye el mock, que lo ignora: un proveedor real no puede llegar a
    ese código. | Alternativa: un borrador con ese nombre, que quedaría 🔴 al llegar a `main` y
    adelantaría el Sprint 5. | Reversible: sí.
  - D105: se sigue a `07` donde `04` §6.2 describe más: plazo total, tope diario, `asyncio.timeout` y
    reparación son del Sprint 5. Con el mock nada de eso puede ocurrir. Las excepciones del proveedor
    se propagan hasta el "mapeo de errores del SDK" del Sprint 5. | Reversible: sí.
  - D106: `suggestedHumanAction` no se recorta: `06` y el contrato no le fijan máximo, y añadirlo
    cambiaría el contrato. Lo acota `LLM_MAX_OUTPUT_TOKENS`. | Reversible: sí.
  - D107: `meta.llmCalls` es 0 con el mock, también si no hay acierto de caché: `06` cuenta "llamadas
    reales al LLM" y su ejemplo con mock dice 0. AC-08.6 cuenta por `cacheHit`, así que el presupuesto
    no cambia. | Reversible: sí.
  - D108: AC-08.4 c usa contención literal sin distinguir mayúsculas, sobre valores no vacíos. Un
    valor corto como `"1"` descarta un texto con `2010`, y eso solo cuesta una llamada; limitar por
    palabra podría dejar pasar un valor. Sin `sourceHeader`, la columna propia cuenta como ajena.
    | Reversible: sí.
  - D109: `analyses` distintas de las violaciones (conjunto, orden o `field`) son salida inválida y no
    se guardan: ninguna regla lo arregla. Una `correction` con `originalValue` distinto de
    `receivedValue`, o con `proposedValue` nulo fuera de `NULLIFY_TOKEN`, no es "válida" (`06` regla 3)
    y va a humano. Cualquier `NEEDS_HUMAN_REVIEW` pierde su `correction`, y si no trae acción recibe un
    texto fijo es/en, porque `Analysis` la exige. | Reversible: sí.
  - D110: `explanation` y `summary` se recortan contando caracteres, sin puntos suspensivos y antes de
    guardar en caché. La plantilla de `summary` de un acierto usa las mismas frases que el mock, de
    modo que con mock solo `meta` distingue un acierto de un fallo (NFR-13). | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner):
  `ai-service/app/domain/rules.py` (nuevo), `ai-service/app/domain/contracts.py` (constantes, sin cambio
  en la spec), `docs/04-arquitectura.md` (§6.2), `docs/09-reglas-para-agentes-ia.md` (§7.4).
- Pendiente:
  - Rama `feat/FR-08-triage-route`: la ruta `POST /v1/triage`, `errors.py`, `ProblemDetail`, `main.py`,
    `cache.entries` en `system.py`, el cambio en `contract_export.py` y la spec; el test de que el
    export es igual a `app.openapi()` puro; las respuestas HTTP de AC-08.8, AC-08.1 y AC-08.2;
    `LlmInvalidOutputError` → `502 LLM_INVALID_OUTPUT`; `503` para un proveedor real;
    `400 INVALID_REQUEST` si `requestId` difiere de `X-Request-Id`. El owner aceptó las decisiones de
    esa rama con dos correcciones: la spec declara todos los códigos de `06` A.5, también 429 y 504, y
    la fila 500 lleva todas sus columnas, alineada con NFR-07.
  - Sprint 4: riesgo de NFR-13. La caché de `ai-service` sobrevive a una base limpia si no se reinicia
    el proceso: la segunda ejecución tendría más `cacheHit = true` y menos `ai_calls_count`. Sin
    resolver.
  - Sprint 5: cumplir `09` §7.11 con `triage_v1.md` antes de añadir un proveedor real; contar
    `llmCalls` reales; plazo total, `asyncio.timeout`, tope diario, reparación y mapeo de errores.

## Sprint 3 — Ruta `POST /v1/triage` y errores (2026-11-03)
- Hecho: FR-08 por HTTP (AC-08.1, AC-08.2, AC-08.8 y un segundo envío idéntico con `cacheHit = true`),
  AC-17.3 y NFR-20 en `ai-service`, con los códigos de `06` A.5 declarados en la spec.
  - `ai-service/app/api/triage.py`: ruta `async def`.
  - `ai-service/app/api/errors.py`: `problem+json`, también para 404 y 405.
  - `contract_export.py` ya no añade nada: la spec es `app.openapi()`.
  - `cache.entries` de `GET /v1/info` es real.
- Decisión del owner (2026-11-03): "Apruebo modificar ai-service/app/domain/contracts.py para añadir
  ProblemDetail, regenerar contracts/ai-service.openapi.json con la ruta POST /v1/triage y los códigos
  de 06 A.5 como esquema inline, aceptando que los esquemas existentes pierdan únicamente sus default
  null al pasar a app.openapi() puro; modificar docs/06 A.5 para añadir la fila 500 INTERNAL_ERROR,
  alinear la fila de transporte a 502/503/504 sin problem+json y aclarar en las cabeceras que un
  X-Request-Id ausente se genera y uno que no es UUID da 422; modificar docs/03 NFR-07 para añadir 500
  a la lista de fallas del circuit breaker, sin cambiar sus reintentos; modificar el árbol de docs/04
  §6.2 para añadir errors.py; capturar Exception en el middleware para responder 500 INTERNAL_ERROR;
  responder 404 y 405 en problem+json con NOT_FOUND y METHOD_NOT_ALLOWED en ai-service, documentándolo
  en 06 A.5; cambiar en test_provider_factory.py el ValueError por la excepción propia; y añadir la
  entrada al CHANGELOG."
- Corrección: **D97 estaba equivocada.** Decía que los esquemas añadidos se generaban como los generaría
  FastAPI 0.141.1 cuando existiera la ruta, y lo apoyaba en `get_definitions`. Faltó el último paso de
  `get_openapi` (`fastapi/openapi/utils.py:679`), con `exclude_none=True`, que quita todo `default: null`.
  Con la ruta puesta y el añadido todavía en el código, el test de igualdad falló:
  `ValueError: schema Expected already exists with another definition`. La entrada de D97 no se reescribe.
- Decisiones:
  - D111: la spec pasa a ser `app.openapi()` puro y sus esquemas pierden 16 `default: null` (12 en
    `Expected`, 2 en `Violation`, 1 en `TriageOptions`, 1 en `TriageRequest`). `required`, tipos y enums
    no cambian. Comprobado una vez contra `dev`: sin esos `default`, los 23 esquemas y las rutas
    existentes son idénticos. | Reversible: sí.
  - D112: un `X-Request-Id` que no es UUID da `422 VALIDATION_ERROR`, por el tipo de la cabecera; si
    falta, no hay error. `requestId` del error repite lo recibido, así que en `ProblemDetail` es texto.
    | Reversible: sí.
  - D113: `ProblemDetail` va inline en cada código y se genera desde el modelo, con sus `$defs` resueltos.
    `model` en `responses` declararía también `application/json`, que es falso. | Reversible: sí.
  - D114: el 500 se responde desde el middleware y no desde un handler de `Exception`, que Starlette
    ejecuta fuera del middleware, sin `X-Request-Id`, y relanza. El log `ERROR` lleva tipo y líneas del
    traceback, sin el mensaje, que podría citar la fila (NFR-08). | Reversible: sí.
  - D115: `detail` siempre es texto. El 422 añade `errors: [{loc, type}]`, sin `msg`, `input` ni `ctx`, y
    con `loc` cortado en `body.record.raw`. | Reversible: sí.
  - D116: `ProviderNotConfiguredError` reemplaza al `ValueError` de D102, porque `read_context` también
    lanza `ValueError` y un fallo interno sería un 503. Da 503 sin key (AC-17.3) y con un proveedor
    real aún sin implementar. | Reversible: sí.
  - D117: una caché por `create_app()`, en `app.state`, creada al arrancar. Su tamaño sale de
    `get_settings()` aunque un test sustituya esa dependencia. | Reversible: sí.
  - D118: 404 y 405 usan como `code` el nombre del estado, igual que `06` Parte B, y el 405 conserva
    `Allow`. En A.5 sus columnas dicen "igual que 400": no reintenta, no cuenta para el circuit breaker
    y `AI_INVALID_RESPONSE`. NFR-07 no los nombra. | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner):
  `ai-service/app/domain/contracts.py`, `contracts/ai-service.openapi.json`,
  `docs/06-contrato-api.md` (cabeceras y A.5), `docs/03-requisitos-no-funcionales.md` (NFR-07),
  `docs/04-arquitectura.md` (§6.2).
- Test existente modificado (con aprobación explícita del owner): `test_provider_factory.py` espera
  `ProviderNotConfiguredError` en lugar de `ValueError`, con el mismo `match`.
- Pendiente:
  - Sprint 4: el pipeline trata 500 como `AI_UNAVAILABLE`, sin reintento y contando para el circuit
    breaker (`06` A.5). Esta rama no lleva código de reintentos.
  - Sprint 5: hasta entonces, un `LLM_PROVIDER` real con key da `/health` `ok`, pero `/v1/triage`
    responde 503. La spec ya declara 429 y 504, que hasta entonces no se emiten.

## Sprint 3 — Contexto de FR-07 y petición de triage en el pipeline (2026-11-04)
- Hecho: FR-07 (AC-07.2, AC-07.3; AC-07.1 y AC-07.4 parciales, ver D121) y el DTO de la petición de `06` A.1.
  Sigue sin haber cliente HTTP: `DisabledAiTriagePort` recibe la petición y responde "no disponible".
  - `triage/contract/TriageRequest.java` y `triage/TriageRequestBuilder.java`.
  - `RawRecord.Malformed` lleva `expectedColumns` y `receivedColumns`, y el pipeline los emite en
    `expected` y en `quarantine_record.violations` (D96).
  - `validation/Text.truncate` recorta por code points; también lo usa `technicalMessage`.
- Decisión del owner (2026-11-04): "Apruebo crear el DTO TriageRequest en
  pipeline-service/src/main/java/com/pipemend/pipeline/triage/contract/, que expected siga siendo un Map
  en la petición, que AiTriagePort reciba TriageRequest, aplazar al Sprint 5 AI_REDACT_FIELDS y
  AI_SEND_FULL_RECORD dejando AC-07.1 parcial, añadir la entrada al CHANGELOG, y cambiar
  RawRecordReaderTest, RecordProcessorTest y ProcessedRecordWriterTest por las columnas de D96."
- Decisión del owner (2026-11-04): "Apruebo modificar docs/03 NFR-07 para añadir 404 y 405 a lo que no
  cuenta para el circuit breaker, en la primera rama del pipeline."
- Decisión del owner (2026-11-04): "Apruebo corregir en docs/CHANGELOG.md la entrada de FR-07 para dejar
  AC-07.4 como parcial, y añadir a la condición previa del Sprint 5 el envío sin _rawLine con
  AI_SEND_FULL_RECORD=false y la limitación en el README."
- Decisiones:
  - D119: `AiTriagePort` recibe `TriageRequest`, como `04` §4 (`triage(context)`). El builder ya corre
    con cada registro inválido, y un fallo suyo termina en `PROCESSING_ERROR`. | Reversible: sí.
  - D120: `expected` sigue siendo un `Map` en la petición, porque una clave ausente y un valor nulo
    difieren en `receivedColumns`. Es la excepción a "DTO como `record`" de `04` §6.1. Un test compara
    cada tipo de regla del YAML real con el esquema `Expected` de `contracts/ai-service.openapi.json`, sin
    dependencias nuevas. | Reversible: sí.
  - D121: `AI_REDACT_FIELDS` y `AI_SEND_FULL_RECORD` se aplazan al Sprint 5 (`07`), y AC-07.1 y AC-07.4
    quedan parciales: AC-07.4 también depende de `AI_SEND_FULL_RECORD`. `customer_id` llega sin redactar
    a `ai-service`, que es local y cuyo mock no lee `record.raw`. | Reversible: sí.
  - D122: el límite de 1 000 de `technicalMessage` y el de 2 000 de `_rawLine` cuentan code points, como
    `ai-service`, y nunca parten un emoji. `_rawLine` solo se recorta en la petición; `raw_payload`
    queda íntegro. | Reversible: sí.
  - D123: el pipeline no envía `explanationLanguage` (`options` es `null`), y `ai-service` usa
    `AI_EXPLANATION_LANGUAGE`. `QuarantineWriter` reutiliza los ids de la petición, de modo que `v1` es
    la misma violación en ambos lados. | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner):
  `pipeline-service/.../triage/contract/TriageRequest.java` (nuevo), `docs/03-requisitos-no-funcionales.md`
  (NFR-07), `docs/CHANGELOG.md`.
- Tests existentes modificados (con aprobación explícita del owner): `RawRecordReaderTest`,
  `RecordProcessorTest` y `ProcessedRecordWriterTest`, por las columnas de D96. También cambia
  `RecordTestSupport`, el helper que construye el procesador.
- Pendiente:
  - Rama `feat/FR-08-response-validator`: `TriageResponse`, sus enums, `TriageJson`,
    `TriageResponseValidator` y las 18 fixtures de respuesta. Aceptadas de forma provisional: el mapper
    estricto, que además debe fallar con contenido tras el JSON, y las comprobaciones más allá de AC-08.7.
  - Rama `feat/FR-08-triage-client`: `AI_REVIEW_REQUIRED` para un `AUTO_FIXABLE`, provisional hasta el
    Sprint 4, y `ai_analysis` con el cuerpo recibido. Su plan revisa el riesgo de 30 s por fila y WireMock.
  - **Condición previa del Sprint 5, junto a `09` §7.11:** ningún proveedor real antes de aplicar
    `AI_REDACT_FIELDS` y `AI_SEND_FULL_RECORD`, incluido el envío sin `_rawLine` con
    `AI_SEND_FULL_RECORD=false`, y de documentar esa limitación en el README (AC-07.4).

## Sprint 3 — `TriageResponse` y validación de la respuesta (2026-11-05)
- Hecho: FR-08, AC-08.7 y la parte de formato de AC-12.3 (JSON inválido, enum desconocido, campos faltantes).
  Nadie llama todavía a este código: lo cablea `feat/FR-08-triage-client`.
  - `triage/contract/`: `TriageResponse`, `Severity`, `Classification`, `RootCause`, `Operation` y `TriageJson`.
  - `triage/TriageResponseValidator.java`: clase pura. Las 18 fixtures de respuesta están asignadas: 4
    válidas, 3 las rechaza `TriageJson` y 11 el validador, cada una por su motivo.
- Decisión del owner (2026-11-05): "Apruebo crear en
  pipeline-service/src/main/java/com/pipemend/pipeline/triage/contract/ los DTO TriageResponse, sus enums
  Severity, Classification, RootCause y Operation, y TriageJson como mapper estricto solo para la respuesta
  de triage, que rechaza contenido tras el JSON y lanza una excepción propia sin el mensaje de Jackson; que
  TriageResponseValidator aplique AC-08.7 más confidence en [0,1], acción no vacía en NEEDS_HUMAN_REVIEW,
  proposedValue nulo solo con NULLIFY_TOKEN y los límites de summary, explanation y meta de docs/06; y añadir
  la entrada al CHANGELOG."
- Decisión del owner (2026-11-05): "Apruebo cambiar
  pipeline-service/src/main/java/com/pipemend/pipeline/triage/contract/TriageJson.java para que rechace la cadena
  vacía o en blanco en campos numéricos y un cuerpo null con InvalidTriageJsonException, y añadir la nota al
  CHANGELOG."
- Corregido después de fusionar (hallado por el owner): `"inputTokens": ""` y `"   "`, igual que `outputTokens`,
  se aceptaban y quedaban como `null`, aunque la spec solo admite entero o `null`; y `TriageJson.parse(null)` lanzaba
  `IllegalArgumentException`. Ahora ambos lanzan `InvalidTriageJsonException`. El resto de las claves que no son
  texto ya fallaba con `""` y `"   "`, y lo cubre un test.
- Decisiones:
  - D124: `TriageJson` es un mapper estático propio y no un bean. El `JsonMapper` de Boot 4.1.1 (Jackson 3.1.5)
    acepta `"maxSeverity": 0` como `LOW`, convierte `"confidence": "0.97"` y `"summary": 5`, y con una clave
    repetida gana la última; el resto de la aplicación lo sigue usando. | Alternativas: configurar el mapper
    de Boot, que cambiaría a toda la aplicación. | Reversible: sí.
  - D125: todas las claves son `required = true`, y los constructores rechazan el `null` donde la spec no lo
    permite. Medido antes de escribir el código: `required` falla con la clave ausente y deja pasar el `null`
    explícito, también en `correction`, `suggestedHumanAction`, `originalValue`, `proposedValue`, `inputTokens`
    y `outputTokens`. | Reversible: sí.
  - D126: `TriageJson` lanza una excepción con mensaje fijo y sin causa, porque el mensaje de Jackson puede citar
    lo recibido. | Reversible: sí.
  - D127: el validador devuelve `Valid` con `recordDecision` y `maxSeverity` calculados, o `Invalid` con todas
    las incidencias, sin valores de la fila. El orden de `Severity` sostiene ese cálculo, y un test lo compara
    con la spec en orden exacto. | Reversible: sí.
  - D128: **los límites van más allá de AC-08.7**: `confidence` en [0,1], `summary` ≤ 1000 y `explanation` ≤ 800
    en code points, `meta` no negativo, `proposedValue` nulo solo con `NULLIFY_TOKEN` y acción no en blanco. Salen
    de `06` y son fallo cerrado. `ai-service` ya debe recortar esos textos (`06` regla 5), así que un texto largo
    aquí indica un bug suyo. La acción es más estricta a propósito: `ai-service` solo rechaza la cadena vacía,
    y aquí `"   "` también falla. | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner): `triage/contract/TriageResponse.java`,
  `Severity.java`, `Classification.java`, `RootCause.java`, `Operation.java`, `TriageJson.java` (nuevos) y
  `docs/CHANGELOG.md`.
- Pendiente:
  - Rama `feat/FR-08-triage-client`: guardar la respuesta cruda truncada a 4 KB en `ai_call_log.error_detail`
    (AC-12.3), porque `TriageJson` ya no da el motivo del fallo. Usar `Valid` para `recordDecision` y
    `max_severity`, nunca lo recibido. Un 200 sin cuerpo termina en `AI_INVALID_RESPONSE`.

## Sprint 3 — Cliente HTTP de triage y `ai_call_log` (2026-11-08)
- Hecho: FR-08 en el lado del pipeline, sin cablear. `HttpAiTriageClient` llama a `POST /v1/triage` y convierte cada
  respuesta en un `Reply` siguiendo `06` A.5. Cubre AC-08.5 (una fila de `ai_call_log` por llamada, también si falla,
  en su propia transacción) y AC-12.3 (cuerpo crudo en `error_detail`). AC-08.6 queda parcial: `outcome` distingue
  `SUCCESS` de `CACHE_HIT`, pero `ai_calls_count` llega con el cableado. El job sigue usando `DisabledAiTriagePort`
  hasta `feat/FR-08-quarantine-classifier`.
- Dependencia de test: `org.wiremock:wiremock-standalone:3.13.2`, la última 3.x en Maven Central; la serie 4 sigue en
  beta. El cliente usa `RestClient` con `JdkClientHttpRequestFactory`, sin dependencias nuevas de producción. Todos
  los tests usan 127.0.0.1: WireMock, un puerto cerrado y un listener con la cola de aceptación llena para el
  timeout de conexión.
- Decisión del owner (2026-11-08): "Apruebo partir el cliente en feat/FR-08-triage-client (HttpAiTriageClient,
  AiClientProperties, AiCallLogRepository con REQUIRES_NEW y TriageOutcome, sin cablear) y
  feat/FR-08-quarantine-classifier; añadir org.wiremock:wiremock-standalone:3.13.2 como dependencia de test en
  pipeline-service/build.gradle.kts; mapear docs/06 A.5 y las respuestas fuera de la tabla a quarantine_reason y
  outcome como propone el plan; guardar en error_detail el cuerpo crudo truncado a 4096 bytes UTF-8 y, en
  transporte, solo el tipo de excepción; que un fallo al escribir ai_call_log no se capture en el cliente y termine
  en PROCESSING_ERROR según NFR-03; aplazar al Sprint 4 marcar el lote agotado ante 429 LLM_BUDGET_EXHAUSTED y
  aceptar hasta entonces el riesgo de 30 s por fila; y añadir la entrada al CHANGELOG."
- Decisión del owner (2026-11-08), sobre la frase anterior: `TriageOutcome` no cambia en esta rama. Es una interfaz
  sellada que `RecordProcessor` recorre con un `switch` exhaustivo, y añadirle variantes obligaba a tocar el
  procesador. El cliente devuelve su propio `HttpAiTriageClient.Reply`.
- Decisiones:
  - D129: `Reply` tiene cuatro variantes. `Analysed` lleva la respuesta, el cuerpo tal cual y el `Valid` del
    validador. `Unavailable`, `InvalidResponse` y `BudgetExhausted` llevan un texto técnico sin el cuerpo.
    | Alternativas: ampliar `TriageOutcome` y tocar `RecordProcessor` aquí | Reversible: sí | Aprobado por: owner.
  - D130: fuera de la tabla de A.5 decide el estado HTTP, y el `code` solo distingue las filas de 429 y 502:
    - cualquier estado distinto de 200 que no sea 5xx ni 429 (otros 2xx, 3xx, 4xx sin fila): `AI_INVALID_RESPONSE`
      y `HTTP_ERROR`;
    - un 5xx con problem+json y `code` desconocido: `AI_UNAVAILABLE` y `HTTP_ERROR`;
    - un 5xx sin problem+json: `AI_UNAVAILABLE` y `UNAVAILABLE`.

    Un problem+json necesita `Content-Type: application/problem+json` y un `code` de texto. Los timeouts de conexión
    y de lectura se registran como `TIMEOUT`. | Reversible: sí | Aprobado por: owner.
  - D131: `error_detail` guarda el cuerpo crudo truncado a 4 096 bytes UTF-8 sin partir un code point (`05` §3.5).
    En un fallo de transporte guarda solo el nombre completo de la clase de la excepción; con el cuerpo vacío, `null`.
    `U+0000` se guarda como `U+FFFD` antes de truncar, porque PostgreSQL lo rechaza en `text`. | Reversible: sí.
  - D132: ni el cliente ni `AiCallLogRepository` capturan un fallo al escribir el log (NFR-03, `03` l.84); en la
    rama B, `RecordProcessor` lo convierte en `PROCESSING_ERROR`. | Reversible: sí | Aprobado por: owner.
  - D133: cada llamada envía `X-Request-Id` igual a `requestId` y también `X-Batch-Id` (`06` A, cabeceras
    obligatorias). | Reversible: sí.
  - D134: una respuesta que no pasa `TriageJson` o el validador no guarda nada de `meta`. Un `llmCalls` mayor que
    32 767 se trata como respuesta inválida, porque no cabe en `ai_call_log.llm_calls` (`smallint`). | Reversible: sí.
  - D135: el cuerpo de una respuesta no va a los logs a ningún nivel. Un rechazo por estado (400, 404, 405, 422 y
    los demás no 5xx) se registra en `ERROR` con el `requestId`, el estado y el `code` solo si es uno de A.5. Una
    respuesta que no pasa el validador va a `WARN` con las incidencias, sin valores. | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner): `docs/CHANGELOG.md`. Nueva dependencia de
  test en `pipeline-service/build.gradle.kts`.
- Pendiente:
  - Rama `feat/FR-08-quarantine-classifier`: cablear el cliente y retirar `DisabledAiTriagePort`; columnas de IA de
    `quarantine_record` y `ai_calls_count`; `AI_REVIEW_REQUIRED` provisional para un `AUTO_FIXABLE`; URL de loopback
    cerrada en los tests de Spring; comprobación manual con `docker compose`. `ai_analysis` es `jsonb`, que también
    rechaza `\u0000`: esa rama debe decidir qué hacer con él.
  - Sprint 4 (`07` l.109): reintentos de transporte (`attempts` > 1), circuit breaker (`CIRCUIT_OPEN`), `AiBudget`
    y marcar el lote agotado ante `429 LLM_BUDGET_EXHAUSTED` (`06` l.334), y la validación al arrancar del invariante
    de timeouts de NFR-07 (`03` l.98), que esta rama no implementa.
  - Riesgo aceptado hasta el Sprint 4: con `ai-service` colgado, cada inválida espera el timeout de lectura completo.
    En `dirty-1k` son 50 × 30 s ≈ 25 min con la transacción del chunk abierta, frente a ≲ 7 min con el circuit
    breaker (`03` l.104).

## Sprint 3 — Cableado del cliente y cuarentena con análisis (2026-11-08)
- Hecho: FR-08 cableado y FR-11 con análisis de IA. `HttpAiTriageClient` es el bean de `AiTriagePort`, y
  `DisabledAiTriagePort` se retira, sin flag ni perfil. `RecordProcessor` asigna `quarantine_reason` según `02` §5.
  `quarantine_record` guarda `ai_analysis`, `max_severity` y `ai_request_id`, y `ingestion_batch.ai_calls_count`
  se guarda al completar (AC-08.6 completo).
- Comprobación manual con `docker compose` (proyecto `pmprobe`, `--env-file` vacío, `LLM_PROVIDER=mock` por
  defecto), contra el `ai-service` real:
  - `dirty-1k`: `COMPLETED`, 950 `DIRECT`, 0 `AUTO_CORRECTED` y 50 en cuarentena, todas `AI_REVIEW_REQUIRED` con
    análisis y `ai_request_id`, y ninguna con `policy_decision`.
  - `ai_call_log`: 44 `SUCCESS` y 6 `CACHE_HIT`, todas con `attempts = 1`, así que `aiCalls = 44`, como define
    AC-08.6. Las 50 filas de cuarentena tienen su llamada.
  - Tiempos: 1,6 s de la subida a `COMPLETED`; 98,9 s de `up --build --wait` con el código nuevo; 1,5 s de `down -v`.
- Decisión del owner (2026-11-08): "Apruebo en feat/FR-08-quarantine-classifier: cablear HttpAiTriageClient como
  bean de AiTriagePort y borrar DisabledAiTriagePort sin flags; mover las variantes de Reply a TriageOutcome; que
  RecordProcessor asigne quarantine_reason según 02 §5, con AUTO_FIXABLE en AI_REVIEW_REQUIRED de forma provisional
  hasta el Sprint 4, y que cualquier excepción, también al escribir ai_call_log, termine en PROCESSING_ERROR;
  escribir ai_analysis con el cuerpo recibido, max_severity de Result.Valid, ai_request_id en toda llamada intentada
  (sustituye a D66) y ai_calls_count desde ai_call_log al completar; para U+0000 en ai_analysis y summary, la opción
  A; apuntar los tests de Spring a http://127.0.0.1:1; añadir IngestionJobTriageTest con un transformador de
  WireMock en loopback; hacer la comprobación manual con docker compose y el mock; cambiar los tests existentes
  listados en el plan, incluida la expectativa de ai_request_id de ProcessedRecordWriterTest; y añadir la entrada al
  CHANGELOG y la nota en docs/06 A.5."
- **Provisional hasta el Sprint 4:** un registro con todas sus violaciones `AUTO_FIXABLE` va a cuarentena con
  `AI_REVIEW_REQUIRED`, aunque `02` l.161 define ese motivo para "al menos una `NEEDS_HUMAN_REVIEW`". Sin
  `CorrectionPolicy` no se corrige nada, y `POLICY_REJECTED` afirmaría que actuó una política que no existe. Las
  métricas por motivo cuentan así esos registros hasta el Sprint 4.
- Decisiones:
  - D136: las variantes de `Reply` pasan a `TriageOutcome` y `HttpAiTriageClient` implementa `AiTriagePort`. El
    bean `aiTriagePort` vive en `IngestionJobConfiguration`. | Alternativas: conservar `Reply` y quitar el puerto |
    Reversible: sí | Aprobado por: owner.
  - D137: `summary` es el de la IA con `Analysed`, ya acotado a 1 000 code points por el validador, y el texto técnico
    del pipeline en los motivos `AI_*` y en `PROCESSING_ERROR` (AC-11.2). | Reversible: sí | Aprobado por: owner.
  - D138: `ai_request_id` va en toda llamada intentada, también en un `PROCESSING_ERROR` ocurrido durante o después
    de la llamada; si la excepción ocurre antes de llamar, queda `null`. Sustituye a D66. | Reversible: sí |
    Aprobado por: owner.
  - D139: `ai_analysis` es el cuerpo tal como llegó, solo con un análisis válido; `max_severity` es la de
    `Result.Valid`; `policy_decision` queda en `null` hasta el Sprint 4. | Reversible: sí | Aprobado por: owner.
  - D140: `ai_calls_count` se calcula en `complete()` con la misma cuenta que la API usa durante el lote: filas de
    `ai_call_log` sin `CACHE_HIT`, fallidas incluidas. | Reversible: sí.
  - D141: U+0000, opción A. El cliente revisa el árbol JSON del cuerpo entero (claves y cadenas, también las claves
    que `TriageJson` ignora) después de `TriageJson` y antes del validador. Si aparece, la respuesta es
    `InvalidResponse`, `ai_call_log` registra `INVALID_RESPONSE` y la fila va a cuarentena con `AI_INVALID_RESPONSE`
    y `ai_analysis` en `null`. U+0001 se acepta. | Reversible: sí | Aprobado por: owner.
    - **Medido:** el cuerpo trae U+0000 como el escape JSON `\u0000`, seis caracteres que `text` admite, así que
      `error_detail` guarda el cuerpo sin cambios. La sustitución por U+FFFD de D131 solo actúa con un byte NUL
      literal.
    - ~~Supuesto, sin test~~ **Medido con test** (2026-11-11, rama `fix/FR-01-FR-08-unstorable-values`): `TriageJson`
      rechaza el byte NUL literal, porque Jackson no admite caracteres de control sin escapar, y `error_detail` lo
      guarda como U+FFFD.
  - D142: los tests de Spring apuntan a `http://127.0.0.1:1` desde `PostgresTestConfiguration`.
    `IngestionJobTriageTest` registra un `HttpAiTriageClient` real con `@Primary` que apunta a WireMock, porque el
    `DynamicPropertyRegistrar` común gana a `@DynamicPropertySource` (medido: con la propiedad, el job seguía
    llamando al puerto cerrado). | Alternativas: un perfil de test, o mover la URL fuera de
    `PostgresTestConfiguration` | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner): `docs/06-contrato-api.md` (nota de U+0000
  en A.5) y `docs/CHANGELOG.md`. `contracts/` no cambia.
- Tests existentes cambiados:
  - `RecordProcessorTest` y `ProcessedRecordWriterTest`: `DisabledAiTriagePort` pasa a una lambda que devuelve
    `Unavailable`.
  - `ProcessedRecordWriterTest`: deja de comprobar `ai_request_id` en `null` y comprueba el id de la petición
    (sustituye a D66).
  - `HttpAiTriageClientTest`: `Reply` pasa a `TriageOutcome`. El test que leía la URL por defecto del contexto ahora
    la lee de `application.yml`, porque el contexto de test apunta a loopback; otro test comprueba esa URL.
  - `IngestionJobTest` (`dirty-1k`): sus aserciones no cambian; se añaden 50 filas `UNAVAILABLE` y
    `ai_calls_count = 50`.
- Pendiente:
  - Rama `fix/` desde `dev`, después de esta y antes de `v0.3`, con su propio plan: el número fuera del rango de
    `numeric` en `jsonb` (medido: `1e1000000` da `value overflows numeric format`) y el byte NUL de un CSV en
    `raw_payload` y `violations` (sin verificar).
  - Sprint 4: `CorrectionPolicy` y el motivo definitivo de un `AUTO_FIXABLE`, reintentos, circuit breaker,
    `AiBudget`, el lote agotado ante `429 LLM_BUDGET_EXHAUSTED`, el invariante de timeouts de NFR-07, AC-12.2 y
    AC-12.5.

## Sprint 3 — Valores que PostgreSQL no puede guardar (2026-11-11)
- Hecho: el byte NUL en un CSV de entrada (FR-01) y un número fuera del rango de `numeric` en la respuesta de
  `ai-service` (FR-08) ya no dejan el lote en `FAILED`. Es una sola rama con dos FR, por decisión del owner.
- Medido antes del arreglo, con tests de job y PostgreSQL real:
  - Un byte NUL en el CSV dejaba el lote en `FAILED` con `INFRASTRUCTURE_ERROR` en los cinco casos: fila válida, fila
    inválida, campo inválido, fila `MALFORMED_ROW` y cabecera. Fallaba `sales_transaction` (`text`: `invalid byte
    sequence for encoding "UTF8": 0x00`), `quarantine_record` (`jsonb`: `unsupported Unicode escape sequence`) o el
    informe de drift. Ninguna fila llegó a `sales_transaction`.
  - Un número como `1e1000000` en `correction.parameters`, en un array o en una clave desconocida pasaba
    `TriageJson` y el validador, y hacía fallar el `INSERT` de `ai_analysis` con `value overflows numeric format`.
    Mismo resultado: `FAILED` con `INFRASTRUCTURE_ERROR`.
- Límites de `numeric` medidos en `postgres:18-alpine`:
  - Se aceptan `1e131071`, `1.0e131071`, `1e-16383` y `1.5e-16382`.
  - Fallan `1e131072`, `-1e131072`, `10e131071`, `1e-16384`, `1.5e-16383` y `1.50e-16382`: PostgreSQL cuenta también
    los ceros finales de la escala.
- Decisión del owner (2026-11-11): "Apruebo la rama fix/FR-01-FR-08-unstorable-values desde dev, que toca FR-01 y
  FR-08 en un solo PR: que RawRecordReader rechace con INVALID_FILE_FORMAT un archivo cuya cabecera o cualquier línea
  contenga U+0000, con la nota en docs/02 l.141 y AC-01.7; que HttpAiTriageClient trate como AI_INVALID_RESPONSE una
  respuesta con un número de más de 131072 dígitos enteros o más de 16383 decimales en cualquier punto del cuerpo, con
  la frase añadida en la nota de docs/06 A.5; añadir el test del byte NUL literal en la respuesta, sin cambio de
  código; escribir primero los tests de los cinco casos de NUL de entrada y mostrarlos en rojo en el PR; y añadir la
  entrada al CHANGELOG."
- Decisiones:
  - D143: `RawRecordReader` lanza `InvalidFileFormatException` si la cabecera o un registro contiene U+0000. Como
    `intakeStep` cuenta con el mismo lector, el lote queda `REJECTED` con `INVALID_FILE_FORMAT` antes de procesar
    ninguna fila, sin escribir en `sales_transaction`, `quarantine_record` ni `ai_call_log` (AC-01.7, `02` l.141).
    | Alternativas: convertir la fila en `MALFORMED_ROW` y sustituir el carácter al guardarla; un `ErrorCode` nuevo |
    Reversible: sí | Aprobado por: owner.
  - D144: `HttpAiTriageClient` comprueba cada número del cuerpo con el mismo recorrido del árbol que ya busca U+0000:
    - el árbol se lee con `USE_BIG_DECIMAL_FOR_FLOATS` y sin `STRIP_TRAILING_BIGDECIMAL_ZEROES`;
    - dígitos enteros = max(precision − scale, 0), calculado en `long` porque `1e2147483647` desborda un `int`;
    - decimales = max(scale, 0);
    - se rechaza si pasa de 131 072 dígitos enteros o de 16 383 decimales.

    El resultado es `AI_INVALID_RESPONSE` con `ai_analysis` en `null`. | Alternativas: exigir que el número quepa en
    un `double`, lo que rechazaría `1e400`, que PostgreSQL admite | Reversible: sí | Aprobado por: owner.
  - D145: el byte NUL literal en la respuesta no necesitó código. El test nuevo confirma el supuesto de D141, que queda
    actualizado arriba.
- Archivos protegidos modificados (con aprobación explícita del owner): `docs/02-requisitos-funcionales.md` (l.141 y
  AC-01.7), `docs/06-contrato-api.md` (nota de A.5) y `docs/CHANGELOG.md`, incluida la actualización de D141.
  `contracts/` no cambia.
- Pendiente: el lector JSON de FR-02 (Sprint 6) debe aplicar la regla de `02` l.141 a U+0000, literal o escapado. No se
  escribe código para eso ahora.

## Sprint 3 — Número que `BigDecimal` no puede leer en la respuesta (2026-11-11)
- Corregido después de fusionar el #44 (hallado por el revisor): `TriageJson` acepta `1e2147483648` dentro de
  `correction.parameters`, porque ese mapa no tiene tipo y el valor queda en `Infinity`. Después, `TREE_READER.readTree`
  lanzaba `NumberFormatException` ("value exceeds limits"), y lo mismo pasaba con `1e-2147483649` y `1e99999999999`. La
  excepción salía de `triage()` sin pasar por `save()`. Consecuencias:
  - la fila acababa en `PROCESSING_ERROR`;
  - no quedaba fila en `ai_call_log`.

  Eso incumplía AC-08.5 y AC-12.3 y contradecía la nota de `06` A.5. Medido con tests en rojo antes del arreglo.
- Arreglo: `answer()` captura `NumberFormatException` y `JacksonException` alrededor de `TREE_READER.readTree` y las
  trata como una respuesta inválida con el texto fijo de un número que no cabe en `numeric`, sin el mensaje de la
  excepción. No se captura `Exception`. Resultado: `AI_INVALID_RESPONSE`, una fila `INVALID_RESPONSE` en
  `ai_call_log` y el cuerpo en `error_detail`.
- Decisión del owner (2026-11-11): "Apruebo la rama fix/FR-08-unparseable-number desde dev: que HttpAiTriageClient
  trate como AI_INVALID_RESPONSE, con su fila INVALID_RESPONSE en ai_call_log y el cuerpo en error_detail, una
  respuesta cuyo árbol JSON no se pueda leer con BigDecimal, y añadir la entrada al CHANGELOG."
- Decisiones:
  - D146: `NumberFormatException` y `JacksonException` llevan al mismo texto fijo que D144, porque ambas significan que
    el cuerpo no cabe en `jsonb`. | Alternativas: un texto propio para cada una | Reversible: sí | Aprobado por: owner.
- Sin cambios en `docs/06`: la nota de A.5 ya cubre "un número que no cabe en `numeric`".
- Archivos protegidos modificados (con aprobación explícita del owner): `docs/CHANGELOG.md`.
- Sin verificar: la rama de `JacksonException` no tiene test. Un cuerpo que ya aceptó `TriageJson` no debería fallar
  como JSON en el lector del árbol, así que la captura es solo una defensa.

## Sprint 4 — Registro de horas y punto de control del Sprint 2 (2026-11-12)
- Registro de horas reales (`07` §4 bis). El owner las estimó a partir de los días con actividad en git y en las
  sesiones, a 2,5 h por día:

  | Sprint | Días | Horas reales | Realista en `07` |
  |---|---|---|---|
  | S1 | 8 | ≈ 20 h | 12–14 h |
  | S2 | 9 | ≈ 22 h | 12–15 h |
  | S3 | 11 | ≈ 28 h | 10–12 h |
  | **Total** | 28 | **≈ 70 h** | 34–41 h (`07` l.16-18) |

  Estimación del Sprint 4: 24–32 h reales, frente a las ~14 h de `07` l.113. No se recorta nada del Sprint 4. Si al
  cerrar su semana `v0.4` no está etiquetada, se avisa con la estimación restante y el owner aplica `07` l.190.
- Decisión del owner (2026-11-12): se aplica con retraso el punto de control del fin del Sprint 2 (`07` l.189: "Horas
  acumuladas S1+S2 > 26 h, o `v0.2` sin etiquetar → Recortar ya FR-22 y el endpoint de FR-21 (se conserva el test de
  idempotencia)").
  - Recortados: FR-22 (resolución de cuarentena) y el endpoint de reinicio de FR-21.
  - Se conserva: el test de idempotencia de FR-21.
  - `docs/07` no cambia y no se recorta nada más. FR-05 se decide en el punto de control del Sprint 6 (`07` l.191);
    las horas acumuladas ya superan las 60 h que ese punto fija.

## Sprint 4 — `CorrectionPolicy` (2026-11-12)
- Hecho: FR-09, sin cablear al job. `07` l.105: "`CorrectionPolicy` + tests parametrizados".
  - AC-09.1: las condiciones por violación, sin leer `recordDecision`.
  - AC-09.3: el motivo de cada rechazo.
  - AC-09.4: umbrales y allowlist configurables, con arranque fallido.
  - AC-09.5: tests parametrizados.
  - AC-09.2: la política devuelve "todo o nada"; el motivo en `quarantine_record` llega con el aplicador.

  Hasta entonces, un `AUTO_FIXABLE` sigue en cuarentena con `AI_REVIEW_REQUIRED` (provisional).
- Valores por defecto implementados tal como están documentados, en `application.yml`:
  - `AI_MIN_CONFIDENCE` 0.85 y `AI_ENUM_MIN_CONFIDENCE` 0.90 (`04` l.292-293);
  - la allowlist de `02` §4 sin `RENAME_COLUMN`, que es solo de FR-05.

  `CorrectionCatalog` guarda ese universo; la allowlist configurada solo puede reducirlo.
- Decisión del owner (2026-11-12): "Apruebo en feat/FR-09-correction-policy: crear CorrectionPolicy,
  PolicyEvaluation, RejectionReason, PolicyProperties y CorrectionCatalog en policy/, sin cablear al job; implementar
  en application.yml los umbrales por defecto AI_MIN_CONFIDENCE 0.85 y AI_ENUM_MIN_CONFIDENCE 0.90 y la allowlist por
  defecto de docs/02 §4 tal como están documentados, con arranque fallido según AC-09.4; el orden de motivos y la
  excepción ante NEEDS_HUMAN_REVIEW como propone el plan; y añadir al CHANGELOG la entrada de la rama, el registro de
  horas S1–S3 y el recorte de FR-22 y del endpoint de FR-21 con la cita de docs/07 l.189."
- Decisiones:
  - D147: cada violación guarda un solo motivo, el de la primera condición que falla, en este orden:
    `ERROR_CODE_NOT_CORRECTABLE`, `OPERATION_NOT_ALLOWED`, `FIELD_NOT_ALLOWED`, `SEVERITY_TOO_HIGH` y
    `CONFIDENCE_BELOW_THRESHOLD`. Primero va lo estructural y después lo que depende de la IA. `05` §5 prevé un único
    `rejectionReason`. | Alternativas: guardar todos los motivos, lo que cambiaría `05` | Reversible: sí | Aprobado
    por: owner.
  - D148: un análisis `NEEDS_HUMAN_REVIEW` que llega a la política lanza `IllegalStateException` con un mensaje fijo y
    sin valores; la política no la captura. Esos registros van antes a `AI_REVIEW_REQUIRED` (`04` l.133), así que
    llegar aquí es un bug, y termina en `PROCESSING_ERROR`. Lo mismo si los análisis no emparejan con las violaciones.
    | Alternativas: un motivo `NOT_AUTO_FIXABLE`, que sería un valor nuevo | Reversible: sí | Aprobado por: owner.
  - D149: la allowlist solo se configura por propiedad (`pipemend.policy.allowlist`), sin variable de entorno nueva.
    `.env.example` y `04` §8 no cambian. Una operación fuera del enum ya falla al enlazar la configuración. | Reversible:
    sí | Aprobado por: owner.
  - D150: `MAP_TO_ENUM` usa solo `AI_ENUM_MIN_CONFIDENCE`, también si se configura por debajo del umbral general; lo
    cubre un test. | Reversible: sí.
  - D151: la parte `verification` de `policy_decision` (`05` §5) no se modela aquí. El JSON completo lo compone el
    aplicador, cuando existan los verificadores, sin dejar un campo vacío ahora. | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner):
  - 🔴 los valores por defecto de umbrales y allowlist en `application.yml`, y `CorrectionCatalog`, implementados sin
    cambiar lo documentado;
  - 🟠 `policy/**`;
  - 🟠 `docs/CHANGELOG.md`.
- Pendiente:
  - Rama `feat/FR-10-text-verifiers`: el verificador de `MAP_TO_ENUM` vuelve a comprobar
    `confidence >= AI_ENUM_MIN_CONFIDENCE` (`02` §4, condición 3). El duplicado con la política es intencionado.
  - Rama `test/FR-20-ac-20-3`: el comando local que construye la imagen de `ai-service` antes de `./gradlew test` va
    en `AGENTS.md` y en el README.

## Sprint 4 — Verificadores de texto (2026-11-13)
- Hecho: la parte de texto de `07` l.106, sin cablear al job. Son los verificadores de `TRIM`, `NORMALIZE_CASE`,
  `NULLIFY_TOKEN` y `MAP_TO_ENUM` (`02` l.117-126), con `country-aliases.v1.yaml` leído y nunca modificado.
  - AC-10.1 queda parcial: faltan los verificadores `PARSE_*` (rama 3).
  - AC-10.5: los siete códigos, que se guardan en `policy_decision` con el aplicador (rama 4).
- `CountryAliases` es un bean de `CoreConfiguration` que se carga al arrancar, como `SchemaDefinition`. La aplicación
  no arranca si el YAML trae un país fuera del catálogo, un alias normalizado que apunta a dos países, o un catálogo
  distinto de `countries.v1.txt`. Lo prueba `ApplicationContextRunner`.
- Medido antes del código, sobre el YAML y el catálogo reales (solo lectura): 41 países en el catálogo, 40 con alias
  y 118 alias. Ningún país del YAML falta en el catálogo, ningún alias es ambiguo y no hay dos países con la misma
  forma normalizada.
- Corregida la tabla de horas del Sprint 4 (pedido del owner): la columna "Realista en `07`" lleva S1 12–14 h, S2
  12–15 h y S3 10–12 h (`07` l.16-18).
- Decisión del owner (2026-11-13): "Apruebo en feat/FR-10-text-verifiers: crear en verifiers/ Verifier,
  VerificationInput, VerificationResult, VerificationCode con los siete códigos de AC-10.5, los verificadores TRIM,
  NORMALIZE_CASE, NULLIFY_TOKEN y MAP_TO_ENUM según docs/02 §4, con la comprobación repetida de
  AI_ENUM_MIN_CONFIDENCE en MAP_TO_ENUM, y CountryAliases como lector de solo lectura de
  schemas/country-aliases.v1.yaml que falla al arrancar ante un país fuera del catálogo o un alias ambiguo, sin
  cablear al job; las decisiones A a E como propone el plan; y añadir la entrada al CHANGELOG con la columna
  "Realista en 07" de la tabla de horas."
- Decisiones:
  - D152: el valor original que se verifica es el `receivedValue` que envió el pipeline, nunca el `originalValue`
    que devuelve la IA. | Reversible: sí | Aprobado por: owner.
  - D153: `detail` es un texto fijo sin valores del registro. El ejemplo de `05` §5 cita el valor, pero la forma
    `{ passed, code, detail }` es la misma. | Reversible: sí | Aprobado por: owner.
  - D154: el cargador de alias falla al arrancar ante un país fuera del catálogo o un alias ambiguo (ADR-0008). Hoy
    no ocurre, según lo medido arriba. | Reversible: sí | Aprobado por: owner.
  - D155: la condición (3) de `MAP_TO_ENUM`, la confianza, se rechaza con `UNVERIFIABLE_MAPPING`, porque AC-10.5 no
    tiene un código propio para la confianza. Esa comprobación se repite a propósito con la de la política (AC-09.1).
    | Reversible: sí | Aprobado por: owner.
  - D156: un verificador ante un campo que no es suyo falla con `VALUE_MISMATCH` en vez de lanzar una excepción, como
    defensa en profundidad tras `FIELD_NOT_ALLOWED`. | Reversible: sí | Aprobado por: owner.
  - D157: `NORMALIZE_CASE` compara `toLowerCase(Locale.ROOT)` en los dos lados. `02` §4 escribe "`equalsIgnoreCase`
    (con `Locale.ROOT`)", pero `String.equalsIgnoreCase` no recibe locale, y pasar los dos lados por
    `toLowerCase(Locale.ROOT)` es la lectura literal de "con `Locale.ROOT`". Cualquiera de las dos sirve, porque
    `proposed` además tiene que estar en el catálogo o cumplir la regex. | Reversible: sí.
  - D158: `TRIM` usa `Whitespace.strip`, que solo quita U+0020 y tab (`02` l.72), y nunca `String.strip()`. Esta
    quitaría U+2003, U+3000 o un salto de línea. Lo cubre un test con esos caracteres. El NBSP no distingue entre las
    dos, porque `String.strip()` tampoco lo quita. | Reversible: sí.
- Archivos protegidos modificados (con aprobación explícita del owner): 🟠 `verifiers/**` (nuevos) y
  `docs/CHANGELOG.md`. 🔴 `schemas/country-aliases.v1.yaml` solo se lee.
- Fuera del plan de archivos, pedido por el owner en la aprobación: `common/CoreConfiguration.java` declara el bean
  `CountryAliases`.
