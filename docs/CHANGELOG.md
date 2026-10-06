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
