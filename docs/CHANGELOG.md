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
