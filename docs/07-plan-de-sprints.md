# 07 — Plan de fases y sprints

## 1. Supuestos

- 8 sprints de **1 semana**, ~9 h/semana (rango 8–10) → **64–80 h** en total.
- Cada sprint termina con algo **ejecutable** y un commit etiquetado (`v0.{n}`).
- ~~Aprox. 15 % de cada semana se reserva implícitamente para imprevistos~~. **Corrección:** las tareas de cada sprint suman 9–10 h, es decir, el 100 % de la capacidad nominal; no había buffer real salvo en el Sprint 8, y en una semana de 8 h el plan ya empieza atrasado. Ver §1 bis. <!-- rev: R-31 -->
- Las horas son estimaciones para una persona con experiencia en Spring y Python básico. Si un sprint se atrasa más de 3 h, se aplica la **línea de recorte** (§4) en lugar de extender el plan, siguiendo los **puntos de control** de §4 bis.

<!-- rev: R-31 -->
## 1 bis. Reestimación realista (revisión técnica)

Estimación de la revisión, por sprint, incluyendo el trabajo que agrega esta misma revisión (≈ 6 h: verificadores anti-alucinación y archivo de alias, suite adversarial, validación de respuesta AC-08.7, reconciliación de arranque, conteo independiente para el invariante, `CHECK` en BD, plazos de NFR-07):

| Sprint | Plan original | Estimación realista | Por qué la diferencia |
|---|---|---|---|
| 1 | 9 h | 12–14 h | Primer contacto con Boot 4 / Batch 6 (poco material, los asistentes generan código de Batch 5); el generador con ~20 tipos de defecto, precondiciones y *labels* reproducibles es más cerca de 4–5 h que de 2 h; `country-aliases.v1.yaml` |
| 2 | 9 h | 12–15 h | Lector tolerante **y** reiniciable (`ItemStream`), lanzamiento asíncrono con `JobOperator`, validador con las reglas de `02` §3.1 y sus tests, reconciliación de arranque |
| 3 | 9 h | 10–12 h | Contrato + mock + caché con las reglas de AC-08.4 |
| 4 | 10 h | 13–16 h | Seis verificadores con reglas de ambigüedad, suite adversarial, Resilience4j con el test de IA colgada, AC-20.3 |
| 5 | 9 h | 9–12 h | El ajuste de prompt no tiene fin natural: se limita con *time-box* |
| 6 | 9 h | 8–10 h | Mayormente CRUD de lectura |
| 7 | 9 h | 10–13 h | Schema drift con verificador de muestra; evaluación con LLM real |
| 8 | 8 h | 8–10 h | README, GIF, prueba desde clon limpio |
| **Total** | **72 h** | **82–102 h** | Capacidad real: **64–80 h** |

**Conclusión:** el camino crítico Must (Sprints 1–4 + métricas, OpenAPI, logs y README) cabe; **el conjunto Must + todos los Should no cabe**. Es esperable ejecutar al menos los puntos 1–4 de la línea de recorte (≈ 8–10 h) y es probable que FR-05 también quede en riesgo. Esto **no** cambia el alcance del MVP (todos los recortes son Should/Could), pero se decide con los puntos de control de §4 bis, no a última hora.

## 2. Vista general

| Sprint | Tema | Hito / tag | FRs principales |
|---|---|---|---|
| 1 | Fundaciones y datos | `v0.1` — Compose con 3 servicios healthy + dataset sucio con *ground truth* | FR-19, NFR-01 (esqueleto) |
| 2 | Camino feliz ETL | `v0.2` — CSV limpio → tabla limpia, estado de lote, inválidos a cuarentena sin IA | FR-01, FR-04, FR-06, FR-13, FR-14, FR-12 (parcial) |
| 3 | Camino inválido + IA mock | `v0.3` — Inválidos → triage mock → cuarentena | FR-07, FR-08 (mock), FR-11, FR-17 (mock) |
| 4 | Auto-corrección segura | `v0.4` — **núcleo demostrable end-to-end con mock** | FR-09, FR-10, FR-12, FR-20 (AC-20.3) |
| 5 | LLM reales | `v0.5` — Mismo flujo con proveedor real configurable | FR-17, NFR-11, NFR-12 |
| 6 | Métricas y consultas | `v0.6` — **MVP completo (todos los Must)** + métricas, cuarentena, correcciones, JSON, muestras | FR-15, FR-16, FR-02, FR-03, FR-18 |
| 7 | Drift, evaluación, robustez | `v0.7` — Schema drift + reporte de evaluación + reinicio | FR-05, FR-20, FR-21, NFR-04, NFR-10 |
| 8 | Pulido y publicación | `v1.0` — Portafolio listo | FR-22 (opcional), NFR-19, DoD |

Camino crítico: **1 → 2 → 3 → 4**. Al terminar el Sprint 4 existe el **núcleo demostrable** (`v0.4`) aunque todo lo demás se recorte. **Precisión:** `v0.4` todavía no cumple todos los Must (faltan FR-15 métricas y FR-18 OpenAPI completo, que están en el Sprint 6); el **MVP completo** (todos los Must) es `v0.6`. La versión anterior llamaba "MVP" a `v0.4`, lo que invitaba a dar por cerrado el alcance Must antes de tiempo. <!-- rev: R-31 -->

## 3. Detalle por sprint

### Sprint 1 — Fundaciones y datos (plan original ~9 h; realista ~13 h)

| Tarea | h |
|---|---|
| Repo, `.gitignore`, `.env.example`, `AGENTS.md`, copiar `/docs`, confirmar versiones puntuales de ADR-0006 (Boot 4.1.x, Batch 6.x, Java 25, Python 3.14.x, Postgres 18.x) | 1 |
| Esqueleto `pipeline-service` (Spring Boot, Actuator, Flyway vacío, Dockerfile multi-stage) | 1.5 |
| Esqueleto `ai-service` (FastAPI, `/health`, `/v1/info`, Dockerfile, ruff/mypy/pytest) | 1.5 |
| `docker-compose.yml` con healthchecks; `docker compose up` verde | 1 |
| `tools/download_dataset.py`: descarga Online Retail II, convierte a CSV, muestrea baseline | 1 |
| Perfilado del dataset (nulos, formatos, países, códigos) → **congelar `sales_transaction.v1.yaml`, `countries.v1.txt` y `country-aliases.v1.yaml`** en el orden de `10` §2 paso 3 bis | 1.5 |
| `tools/dirty_data_generator` v1: inyección con semilla + `labels.csv` con las garantías de AC-19.4 | 4 |
| **(movido desde Sprint 8)** CI mínimo en GitHub Actions: `./gradlew test` + `pytest` + `ruff` + `mypy` en cada push. Detecta temprano código de Batch 5 que no compila y es prerequisito de AC-18.4 | 0.5 |
| **(movido desde Sprint 7)** Logs JSON en ambos servicios (en Boot 4 es configuración: `logging.structured.format.console`) | 0.5 |

**Total realista: ~13 h.** Si el tiempo no alcanza, el generador v1 cubre primero los defectos que usan los Sprints 3–4 y el resto pasa al Sprint 3 (no al revés). <!-- rev: R-31 -->

**Entregables:** 3 contenedores healthy; CI verde; `data/samples/clean-1k.csv`, `dirty-1k.csv` + `dirty-1k.labels.csv`; schema v1, catálogo y alias congelados.
**Demo:** `docker compose up` + `curl localhost:8000/health` + mostrar `labels.csv`.

### Sprint 2 — Camino feliz ETL (plan original ~9 h; realista ~13 h)

| Tarea | h |
|---|---|
| Migraciones Flyway: `V1` metadata de Spring Batch (script del jar), `ingestion_batch`, `sales_transaction` **y `quarantine_record`** (adelantada desde el Sprint 3) | 1.5 |
| `POST /api/v1/ingestions`: validación de archivo, checksum, almacenamiento en inbox, `409` por duplicado | 2 |
| Job: `intakeStep` (con conteo de registros), `processRecordsStep` (reader CSV tolerante y reiniciable, processor, writer JDBC), `finalizeStep` con invariante completo, lanzamiento asíncrono con `JobOperator` y ejecutor de un hilo | 3.5 |
| Carga del schema YAML + `Validator` puro con tests unitarios (FR-06 + reglas de `02` §3.1) | 3 |
| `DisabledAiTriagePort`: implementación del puerto que siempre responde "no disponible" → los inválidos van a cuarentena con `AI_UNAVAILABLE` (ejercita FR-12 desde el principio) | 0.5 |
| Reconciliación de arranque (AC-04.6) + test | 1 |
| Transformador + `GET /api/v1/ingestions/{id}` y listado | 1 |
| Test de integración con Testcontainers: `clean-1k.csv` → 1000 filas `DIRECT` | 0.5 |

**Total realista: ~13 h.**

**Entregables:** CSV limpio cargado; duplicados rechazados; **inválidos en cuarentena con `AI_UNAVAILABLE`** e invariante de conteo verificado desde el primer lote. <!-- rev: R-31 -->

> Corrección: la versión anterior decía que en el Sprint 2 los inválidos "solo se cuentan y se registran en log (sin persistir)". Con eso, el `finalizeStep` (que verifica el invariante) marcaría `FAILED` cualquier archivo con una sola fila inválida, y habría que desactivar el invariante —justo lo que no se quiere enseñar a un agente—. Con `DisabledAiTriagePort` el invariante vale desde el día 1 y el Sprint 3 solo reemplaza el adaptador.
**Demo:** subir `clean-1k.csv` por Swagger y consultar el lote.

### Sprint 3 — Camino inválido + IA mock (plan original ~9 h; realista ~11 h)

| Tarea | h |
|---|---|
| Modelos Pydantic del contrato (`TriageRequest/Response`) + export de OpenAPI a `contracts/` + fixtures | 1.5 |
| `LLMProvider` protocol, factory, `MockProvider` cubriendo todo el catálogo de defectos | 2 |
| `triage_service` con reglas duras (`rules.py`) y caché | 1.5 |
| Migración `ai_call_log` (`quarantine_record` ya existe desde el Sprint 2) | 0.25 |
| Pipeline: construcción de contexto (FR-07), `HttpAiTriageClient` (reemplaza a `DisabledAiTriagePort`; sin resiliencia aún) + `TriageResponseValidator` (AC-08.7) | 2.5 |
| Writer clasificador: limpio vs cuarentena (todos los inválidos → cuarentena con `AI_REVIEW_REQUIRED` o el motivo que corresponda) | 1 |
| Tests: contrato con fixtures en ambos lados; integración con WireMock | 1 |

**Total realista: ~11 h.**

**Entregables:** `dirty-1k.csv` → válidos en tabla limpia, inválidos en cuarentena con análisis del mock. Invariante de conteo verificado.

### Sprint 4 — Auto-corrección segura (plan original ~10 h; realista ~14 h) ⭐ núcleo

| Tarea | h |
|---|---|
| `CorrectionPolicy` + tests parametrizados (todos los motivos de rechazo) | 2 |
| Verificadores por operación (`TRIM`, `NORMALIZE_CASE`, `PARSE_DATE` con detección de ambigüedad, `PARSE_NUMBER` con ambigüedad de miles y moneda, `MAP_TO_ENUM` contra `country-aliases.v1.yaml`, `NULLIFY_TOKEN`) + tests | 3.5 |
| Suite adversarial (AC-10.6) | 1.5 |
| `CorrectionApplier` + revalidación + `correction_log` (migración + writer) en la misma transacción | 2 |
| Resilience4j (timeouts, retry solo de transporte, circuit breaker con llamadas lentas) + mapeo de fallas a motivos de cuarentena + validación del invariante de timeouts (NFR-07) | 2 |
| `AiBudget` por lote (AC-08.6) | 0.5 |
| Tests de integración: IA caída, **IA colgada** (AC-12.5), respuesta inválida, presupuesto agotado; invariante; **AC-20.3** (resultado exacto vs *ground truth*) | 2 |

**Total realista: ~14 h.** Es el sprint más cargado y el más importante: si se atrasa, se usa el buffer de la semana 5 antes que recortar cualquier cosa de este sprint.

**Entregables:** núcleo demostrable con `mock` (todos los Must de corrección y resiliencia). Tag `v0.4`.
**Demo:** subir `dirty-1k.csv`; mostrar un registro auto-corregido con su `correction_log` y uno en cuarentena con explicación; apagar `ai-service` y repetir con otro archivo.

### Sprint 5 — Proveedores LLM reales (plan original ~9 h; realista 9–12 h)

| Tarea | h |
|---|---|
| Prompt `triage_v1.md` (rol, reglas, escala de severidad, allowlist, JSON Schema de salida, ejemplos few-shot) | 2 |
| `OpenAICompatibleProvider` (OpenAI / Ollama / otros vía `LLM_BASE_URL`) con modo JSON | 2 |
| `AnthropicProvider` | 1.5 |
| Reintento de reparación ante salida inválida; mapeo de errores del SDK | 1 |
| Tokens y latencia en `meta` y `ai_call_log`; redacción (NFR-15) | 1 |
| Prueba manual con un proveedor real sobre `dirty-1k`; ajuste del prompt; anotar hallazgos, tokens por llamada y costo estimado (NFR-11). **Time-box: 3 h en total** para iterar el prompt; lo que no se logre se documenta como hallazgo | 1.5–3 |
| Tope diario `LLM_MAX_CALLS_PER_DAY` (AC-08.8) + plazo total `AI_REQUEST_DEADLINE_SECONDS` | 1 |

**Entregables:** cambio de proveedor solo por `.env`; sección §9 de `04-arquitectura.md` verificada.

### Sprint 6 — Métricas, consultas y fuentes (~9 h)

| Tarea | h |
|---|---|
| `GET /api/v1/metrics/summary` con filtros + test de coherencia (AC-15.3) | 2.5 |
| `GET /api/v1/quarantine`, `/{id}`, `GET /api/v1/corrections` con paginación y filtros | 2 |
| Lector JSON (FR-02) | 1.5 |
| Muestras empaquetadas + endpoints de samples (FR-03) | 1 |
| Anotaciones OpenAPI completas con ejemplos en ambos servicios (FR-18); Micrometer (NFR-09) | 2 |

**Entregables:** todos los endpoints de la Parte B de `06-contrato-api.md` (salvo Could).

### Sprint 7 — Schema drift, evaluación y robustez (plan original ~9 h; realista 10–13 h)

| Tarea | h |
|---|---|
| `schemaCheckStep`: normalización determinista + `/v1/schema-drift/analyze` (mock + prompt) + `SchemaMappingPolicy` + verificador de muestra (AC-05.7). **Time-box: 4 h**; si se excede, FR-05 se recorta con el diseño documentado | 3.5–4 |
| `scripts/evaluate.py` + reporte Markdown; correr con `mock` y con un LLM real | 2 |
| Test de reinicio (falla inyectada a mitad de job) + FR-21 si el tiempo alcanza | 1.5 |
| Pruebas de performance (10k, 100k) y ajustes (chunk size, índices). **Time-box: 1.5 h**; se documentan los números reales aunque no se cumplan | 1.5 |
| Propagación de `batchId`/`requestId` en MDC y headers entre ambos servicios (NFR-08; el formato JSON ya existe desde el Sprint 1) | 0.5 |

**Entregables:** `drift-legacy-headers.csv` procesado; `reports/eval-*.md` publicados.

### Sprint 8 — Pulido y publicación (~8 h)

| Tarea | h |
|---|---|
| README: pitch, diagrama, quickstart, demo de 5 minutos, resultados de evaluación, limitaciones, roadmap | 2.5 |
| `scripts/demo.sh` (sube muestras, espera, imprime métricas y ejemplos) | 1 |
| CI completo: agregar verificación de contrato (AC-18.4), build de imágenes y `gitleaks` (el CI mínimo existe desde el Sprint 1) | 1 |
| GIF o video corto (≤ 2 min). **Time-box: 1 h**; un GIF de terminal basta | 1 |
| Recorrido completo del DoD (`08-definition-of-done.md`) desde un clon limpio | 1 |
| FR-22 si sobra tiempo; limpieza de TODOs | 1 |

**Entregables:** `v1.0`.

## 4. Línea de recorte (si hay atraso)

Se recorta en este orden, **sin** tocar los Must:

1. FR-22 (resolución de cuarentena)
2. FR-21 (endpoint de reinicio) — se mantiene el test de idempotencia
3. `AnthropicProvider` (queda solo `openai_compatible`, que cubre varios proveedores)
4. FR-02 (JSON)
5. FR-05 (schema drift) → se documenta como "siguiente paso" con el diseño ya escrito
6. FR-20 se reduce a un script mínimo con dos métricas: correcciones incorrectas y *recall* (AC-20.3 **no** se recorta: es Must y existe desde el Sprint 4)

**Nunca se recorta:** política + verificadores (incluida la suite adversarial), degradación segura, invariante de conteo, reconciliación de arranque, métricas, FR-16 AC-16.2/16.3 y FR-03 (los usa la demo), Docker Compose de un comando, proveedor `mock`, CI, README.

<!-- rev: R-31 -->
## 4 bis. Puntos de control (decisiones objetivas, no a última hora)

Se lleva un registro simple de horas reales por sprint en `docs/CHANGELOG.md`.

| Cuándo | Condición | Acción |
|---|---|---|
| Fin del Sprint 2 | Horas acumuladas S1+S2 > 26 h, o `v0.2` sin etiquetar | Recortar ya FR-22 y el endpoint de FR-21 (se conserva el test de idempotencia). Anotar en CHANGELOG |
| Fin del Sprint 4 (**crítico**) | `v0.4` sin etiquetar | La semana 5 se dedica a terminar el Sprint 4. Se recortan `AnthropicProvider` y FR-02; el Sprint 5 queda solo con `openai_compatible` |
| Fin del Sprint 6 | `v0.6` sin etiquetar, o horas acumuladas > 60 h | FR-05 se recorta (queda el diseño documentado en `02` y `06` como "siguiente paso"); el Sprint 7 se dedica a evaluación y robustez |
| Inicio del Sprint 8 | Cualquier Must pendiente | El Sprint 8 se dedica solo a Must + README; GIF y FR-22 fuera |

**Regla para agentes de IA:** un agente **no** aplica recortes por su cuenta; si detecta que una tarea no cabe, lo informa con la estimación y el punto de control que aplicaría (`09` §4).

## 5. Riesgos

| Riesgo | Prob. | Impacto | Mitigación |
|---|---|---|---|
| Los asistentes de IA generan código de Spring Batch 5 (la mayoría del material público) en un proyecto con Batch 6 | **Muy alta** | Medio | Versiones fijadas en ADR-0006; guía de señales en `09` §7 bis; falla de compilación temprana lo evidencia |
| Menos ejemplos y respuestas en línea para Spring Boot 4 / Batch 6 | Alta | Bajo | Usar la documentación oficial y la guía de migración 5→6 como referencia primaria |
| LLM devuelve JSON inválido o inconsistente | Media | Medio | Modo JSON, validación Pydantic, reparación, reglas duras, política en Spring |
| Costo o límites de tasa del proveedor | Media | Bajo | `mock` por defecto, caché, tope por lote, Ollama como alternativa gratuita |
| Llamadas a IA dentro de la transacción ralentizan lotes grandes | Media | Bajo | Aceptado para el MVP; documentado en NFR-10 |
| Scope creep (dashboard, auth, Kafka...) | Alta | Alto | Fuera de alcance explícito, reglas para agentes, ADR obligatorio |
| El dataset real tiene más anomalías de las esperadas | Media | Bajo | Perfilado en Sprint 1 y baseline filtrado; anomalías reales mostradas como "hallazgos" |
| Semanas con menos horas | Alta | Medio | Línea de recorte + puntos de control (§4 bis). El "buffer del Sprint 8" es en realidad ~0–2 h (§1 bis) |
| Subestimación general del plan (≈ 82–102 h realistas vs 64–80 h disponibles) | **Alta** | Alto | §1 bis; recortes decididos en los puntos de control; ningún Must depende de un Should | 
| *Rabbit holes*: ajuste infinito del prompt, tuning de performance, pulido del README/GIF | Alta | Medio | *Time-boxes* explícitos en los Sprints 5, 7 y 8 |
| El LLM propone correcciones plausibles pero incorrectas con alta confianza | Alta (con LLM real) | Alto (daño a datos) | Verificadores que recalculan y rechazan ambigüedad (ADR-0008) + suite adversarial (AC-10.6) |
| Metadata de Spring Batch en memoria sin que nadie lo note (starter equivocado en Boot 4) | Media | Alto | `spring-boot-starter-batch-jdbc` + test que verifica filas en `BATCH_JOB_EXECUTION` (`04` §3.1) |
| Costo del LLM fuera de control (muchos lotes, reintentos en cascada) | Media | Medio | Tope por lote + tope diario + plazo total por solicitud + límite de gasto en la consola del proveedor (NFR-11) |
| Inyección de prompt desde `description` | Media | Bajo (solo afecta texto) | Datos delimitados + la política/verificadores deciden (NFR-14) |

## 6. Rituales mínimos (trabajo individual)

- **Inicio de semana (15 min):** elegir tareas del sprint, abrir issues con IDs de FR.
- **Fin de semana (15 min):** demo para uno mismo según la sección "Demo", tag, nota corta en `docs/CHANGELOG.md` (qué se hizo, **horas reales**, qué se recortó, decisiones nuevas → ADR) y revisión del punto de control de §4 bis que corresponda.
