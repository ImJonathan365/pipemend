# 05 — Modelo de datos

## 1. Principios

- **Solo `pipeline-service` escribe y lee** esta base de datos (ADR-0005).
- Migraciones con **Flyway** en `pipeline-service/src/main/resources/db/migration`. **Nunca se edita una migración ya aplicada**; todo cambio es una migración nueva `V{n}__descripcion.sql`.
- Enumeraciones como `text` + `CHECK` (más fácil de evolucionar que tipos `ENUM` de PostgreSQL).
- Timestamps en `timestamptz` (UTC). `invoice_date` es `timestamp` sin zona porque la fuente no la trae (se documenta como hora local del origen).
- Datos semiestructurados (violaciones, análisis de IA, crudo) en `jsonb`.
- Las tablas `BATCH_*` de Spring Batch se crean con su script oficial **vía Flyway `V1__spring_batch_schema.sql`** (copiado del jar de la versión exacta de Spring Batch fijada) con `spring.batch.jdbc.initialize-schema=never`. No se modifican. (Antes: "se decide en Sprint 2"; se decide ahora para que un agente no active ambas vías. Ver `04` §3.1.) <!-- rev: R-16 -->
- **"Migración aplicada"** = cualquier archivo `V*__*.sql` que ya está en la rama principal (`main`). Esas son inmutables. Una migración que solo existe en la rama de trabajo del PR actual y nunca se fusionó puede editarse dentro de ese mismo PR. <!-- rev: R-33 -->
- La BD replica como `CHECK` las reglas del schema v1 expresables en SQL (defensa en profundidad, AC-13.4). Si el schema YAML y los `CHECK` divergen, es un bug: un test de integración inserta un registro que viola cada `CHECK` y otro que ejecuta el validador sobre los mismos casos. <!-- rev: R-15 -->

## 2. Diagrama entidad-relación

```
                    ┌───────────────────────────┐
                    │      ingestion_batch      │
                    │───────────────────────────│
                    │ PK id (uuid)              │
                    │ UQ* source_checksum       │  (*parcial: status <> 'REJECTED')
                    │    status, contadores,    │
                    │    schema_mapping ...     │
                    └─────────────┬─────────────┘
          ┌───────────────────────┼────────────────────────┬──────────────────────┐
          │ 1:N                   │ 1:N                    │ 1:N                  │ 1:N
┌─────────▼──────────┐ ┌──────────▼──────────┐ ┌───────────▼─────────┐ ┌──────────▼─────────┐
│ sales_transaction  │ │  quarantine_record  │ │   correction_log    │ │    ai_call_log     │
│────────────────────│ │─────────────────────│ │─────────────────────│ │────────────────────│
│ PK id              │ │ PK id               │ │ PK id               │ │ PK id              │
│ FK batch_id        │ │ FK batch_id         │ │ FK batch_id         │ │ FK batch_id        │
│ source_row_number  │ │ source_row_number   │ │ source_row_number   │ │ source_row_number  │
│ UQ(batch,row)      │ │ UQ(batch,row)       │ │ FK(batch,row)→sales │ │ request_id         │
│ datos canónicos    │ │ raw_payload, ai_... │ │ UQ(batch,row,field) │ │ latencia, tokens   │
│ load_origin        │ │ reason, status      │ │ operación, valores  │ │ outcome, attempts  │
└────────────────────┘ └─────────────────────┘ └─────────────────────┘ └────────────────────┘
        ▲                                               │
        └──── N:1 FK compuesta (batch_id, source_row_number) ───┘

Invariante: cada (batch_id, source_row_number) está en sales_transaction XOR quarantine_record.
```

## 3. Tablas

### 3.1 `ingestion_batch`

Un registro por archivo recibido (lote).

| Columna | Tipo | Nulo | Descripción |
|---|---|---|---|
| `id` | `uuid` PK | No | `batchId` público |
| `source_filename` | `text` | No | Nombre original saneado |
| `source_type` | `text` CHECK (`CSV`,`JSON`) | No | |
| `source_checksum` | `char(64)` | No | SHA-256 hex del contenido (minúsculas). Único entre lotes no `REJECTED` (índice parcial `uq_batch_checksum_active`, ADR-0009) |
| `source_size_bytes` | `bigint` | No | |
| `schema_name` / `schema_version` | `text` | No | `sales_transaction` / `1` |
| `status` | `text` CHECK (`RECEIVED`,`RUNNING`,`COMPLETED`,`FAILED`,`REJECTED`) | No | |
| `failure_reason` | `text` CHECK | Sí | Valores cerrados de `02` §5: `INVALID_FILE_FORMAT`, `SCHEMA_DRIFT_UNRESOLVED`, `INVARIANT_VIOLATION`, `INFRASTRUCTURE_ERROR`, `INTERRUPTED` (antes terminaba en "..." y quedaba abierto) |
| `schema_mapping` | `jsonb` | Sí | Mapeo cabecera → campo canónico con origen **por cabecera** (`IDENTITY`,`NORMALIZED`,`AI`); ver §5 |
| `schema_drift_report` | `jsonb` | Sí | Reporte si hubo drift (aplicado o rechazado) |
| `read_count` | `integer` | No (default 0) | Registros de datos del archivo (sin cabecera), contados por `intakeStep` **antes** de procesar (NFR-06). No proviene de `StepExecution.readCount` |
| `direct_count` | `integer` | No (default 0) | |
| `auto_corrected_count` | `integer` | No (default 0) | |
| `quarantined_count` | `integer` | No (default 0) | |
| `ai_calls_count` | `integer` | No (default 0) | Llamadas que consumen presupuesto según AC-08.6 (toda llamada sin `cacheHit = true`, incluidas las fallidas). Se persiste al final; durante el job la fuente es `ai_call_log` |
| `job_execution_id` | `bigint` | Sí | Referencia lógica a `BATCH_JOB_EXECUTION` (sin FK) |
| `received_at` | `timestamptz` | No | |
| `started_at` / `finished_at` | `timestamptz` | Sí | |

### 3.2 `sales_transaction` (datos limpios)

| Columna | Tipo | Nulo | Descripción |
|---|---|---|---|
| `id` | `bigint` identity PK | No | |
| `batch_id` | `uuid` FK → `ingestion_batch` | No | |
| `source_row_number` | `integer` | No | 1-based, sin cabecera |
| `invoice_no` | `varchar(7)` | No | |
| `is_cancellation` | `boolean` | No | `invoice_no` empieza con `C` |
| `stock_code` | `varchar(20)` | No | |
| `description` | `varchar(255)` | Sí | |
| `quantity` | `integer` | No | |
| `invoice_date` | `timestamp` | No | |
| `unit_price` | `numeric(12,2)` | No | |
| `line_total` | `numeric(14,2)` | No | `quantity × unit_price` |
| `customer_id` | `varchar(5)` | Sí | |
| `country` | `varchar(64)` | No | |
| `load_origin` | `text` CHECK (`DIRECT`,`AUTO_CORRECTED`) | No | |
| `loaded_at` | `timestamptz` | No | default `now()` |

### 3.3 `quarantine_record`

| Columna | Tipo | Nulo | Descripción |
|---|---|---|---|
| `id` | `bigint` identity PK | No | |
| `batch_id` | `uuid` FK | No | |
| `source_row_number` | `integer` | No | |
| `raw_payload` | `jsonb` | No | Registro crudo `{cabecera: valor}` **sin redactar** (queda en la BD local); para `MALFORMED_ROW`, `{ "_rawLine": "..." }` |
| `violations` | `jsonb` | No | Lista de violaciones (FR-07) |
| `ai_analysis` | `jsonb` | Sí | `TriageResponse` completa; `null` si no hubo IA |
| `summary` | `text` | No | Explicación legible (IA o generada por el pipeline) |
| `max_severity` | `text` CHECK (`LOW`,`MEDIUM`,`HIGH`,`CRITICAL`) | Sí | `null` si no hubo IA |
| `quarantine_reason` | `text` CHECK (motivos de `02` §5) | No | |
| `policy_decision` | `jsonb` | Sí | Motivo de rechazo por violación (AC-09.3) y resultado del verificador (AC-10.5). `null` si la política no llegó a evaluarse (motivos `AI_*` y `PROCESSING_ERROR` previos a la política) |
| `ai_request_id` | `uuid` | Sí | Correlación con `ai_call_log` |
| `status` | `text` CHECK (`PENDING_REVIEW`,`RESOLVED`,`DISCARDED`) | No | default `PENDING_REVIEW` |
| `resolution_note` | `text` | Sí | FR-22 |
| `resolved_at` | `timestamptz` | Sí | FR-22 |
| `created_at` | `timestamptz` | No | default `now()` |

### 3.4 `correction_log`

Una fila por **campo** corregido automáticamente.

| Columna | Tipo | Nulo | Descripción |
|---|---|---|---|
| `id` | `bigint` identity PK | No | |
| `batch_id` | `uuid` FK | No | |
| `source_row_number` | `integer` | No | |
<!-- rev: R-14 -->
| *(eliminada)* `sales_transaction_id` | — | — | **Reemplazada por FK compuesta** `(batch_id, source_row_number) → sales_transaction(batch_id, source_row_number)`. La versión anterior decía "se completa tras insertar la fila limpia (o se resuelve por `(batch_id, source_row_number)`)": dos estrategias incompatibles, y la primera no funciona con `JdbcBatchItemWriter` (no devuelve IDs generados por lote). La API sigue exponiendo `salesTransactionId` resolviéndolo con un `JOIN` |
| `field_name` | `text` | No | Campo canónico |
| `error_code` | `text` | No | Código de violación original |
| `operation` | `text` CHECK (allowlist) | No | |
| `operation_params` | `jsonb` | Sí | p. ej. `{ "sourcePattern": "dd/MM/yyyy HH:mm" }` |
| `original_value` | `text` | Sí | |
| `corrected_value` | `text` | Sí | `null` solo para `NULLIFY_TOKEN` |
| `confidence` | `numeric(4,3)` | No | |
| `severity` | `text` CHECK (`= 'LOW'`) | No | Siempre `LOW` en el MVP (la política no aprueba otra; el `CHECK` lo hace explícito) |
| `ai_explanation` | `text` | No | |
| `ai_provider` / `ai_model` / `prompt_version` | `text` | No | NFR-12 |
| `ai_request_id` | `uuid` | No | |
| `applied_at` | `timestamptz` | No | default `now()` |

### 3.5 `ai_call_log`

Una fila por **llamada lógica** del pipeline al servicio de IA (incluidas fallas; los reintentos de transporte de la misma llamada se agrupan en `attempts`). Se escribe fuera de la transacción del chunk (NFR-05). <!-- rev: R-38 -->

| Columna | Tipo | Nulo | Descripción |
|---|---|---|---|
| `id` | `bigint` identity PK | No | |
| `request_id` | `uuid` | No | `X-Request-Id` |
| `batch_id` | `uuid` FK | No | |
| `source_row_number` | `integer` | Sí | `null` para schema drift |
| `endpoint` | `text` CHECK (`TRIAGE`,`SCHEMA_DRIFT`) | No | |
| `outcome` | `text` CHECK (`SUCCESS`,`CACHE_HIT`,`TIMEOUT`,`UNAVAILABLE`,`CIRCUIT_OPEN`,`INVALID_RESPONSE`,`HTTP_ERROR`) | No | |
| `http_status` | `integer` | Sí | |
| `latency_ms` | `integer` | Sí | Medida en Spring |
| `provider` / `model` / `prompt_version` | `text` | Sí | De `meta` |
| `input_tokens` / `output_tokens` | `integer` | Sí | |
| `attempts` | `smallint` | No (default 1) | Intentos HTTP del pipeline para esta llamada lógica (NFR-07) |
| `llm_calls` | `smallint` | Sí | `meta.llmCalls`: llamadas reales al LLM dentro de `ai-service` (0 si caché) |
| `error_code` | `text` | Sí | `code` del `problem+json` de `ai-service` si lo hubo (`LLM_TIMEOUT`, `LLM_BUDGET_EXHAUSTED`, ...) |
| `error_detail` | `text` | Sí | Truncado a 4 KB |
| `created_at` | `timestamptz` | No | default `now()` |

## 4. DDL de referencia (Flyway `V2__core_tables.sql`)

<!-- rev: R-12, R-14, R-15, R-38 -->
`V1__spring_batch_schema.sql` contiene el script oficial de Spring Batch (§1). Cambios respecto de la versión anterior de este DDL: índice único parcial de checksum, `CHECK` de `failure_reason`, `CHECK` de defensa en profundidad en `sales_transaction`, FK compuesta en `correction_log`, `CHECK` de `severity`, columnas `attempts`, `llm_calls` y `error_code` en `ai_call_log`.

```sql
CREATE TABLE ingestion_batch (
    id                    uuid PRIMARY KEY,
    source_filename       text        NOT NULL,
    source_type           text        NOT NULL CHECK (source_type IN ('CSV','JSON')),
    source_checksum       char(64)    NOT NULL,
    source_size_bytes     bigint      NOT NULL,
    schema_name           text        NOT NULL,
    schema_version        text        NOT NULL,
    status                text        NOT NULL CHECK (status IN ('RECEIVED','RUNNING','COMPLETED','FAILED','REJECTED')),
    failure_reason        text        CHECK (failure_reason IN (
                             'INVALID_FILE_FORMAT','SCHEMA_DRIFT_UNRESOLVED','INVARIANT_VIOLATION',
                             'INFRASTRUCTURE_ERROR','INTERRUPTED')),
    schema_mapping        jsonb,
    schema_drift_report   jsonb,
    read_count            integer     NOT NULL DEFAULT 0,
    direct_count          integer     NOT NULL DEFAULT 0,
    auto_corrected_count  integer     NOT NULL DEFAULT 0,
    quarantined_count     integer     NOT NULL DEFAULT 0,
    ai_calls_count        integer     NOT NULL DEFAULT 0,
    job_execution_id      bigint,
    received_at           timestamptz NOT NULL DEFAULT now(),
    started_at            timestamptz,
    finished_at           timestamptz
);
-- Un mismo contenido no puede tener dos lotes activos; los REJECTED no bloquean el reenvío (ADR-0009).
CREATE UNIQUE INDEX uq_batch_checksum_active ON ingestion_batch (source_checksum) WHERE status <> 'REJECTED';
CREATE INDEX ix_batch_status_received ON ingestion_batch (status, received_at DESC);
CREATE INDEX ix_batch_received ON ingestion_batch (received_at);

CREATE TABLE sales_transaction (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id           uuid          NOT NULL REFERENCES ingestion_batch(id),
    source_row_number  integer       NOT NULL CHECK (source_row_number >= 1),
    invoice_no         varchar(7)    NOT NULL CHECK (invoice_no ~ '^[CA]?[0-9]{6}$'),
    is_cancellation    boolean       NOT NULL,
    stock_code         varchar(20)   NOT NULL,
    description        varchar(255),
    quantity           integer       NOT NULL CHECK (quantity BETWEEN -100000 AND 100000 AND quantity <> 0),
    invoice_date       timestamp     NOT NULL CHECK (invoice_date >= TIMESTAMP '2009-01-01 00:00:00'),
    unit_price         numeric(12,2) NOT NULL CHECK (unit_price BETWEEN 0 AND 100000),
    line_total         numeric(14,2) NOT NULL,
    customer_id        varchar(5)    CHECK (customer_id ~ '^[0-9]{5}$'),
    country            varchar(64)   NOT NULL,
    load_origin        text          NOT NULL CHECK (load_origin IN ('DIRECT','AUTO_CORRECTED')),
    loaded_at          timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_sales_batch_row UNIQUE (batch_id, source_row_number),
    -- Defensa en profundidad (AC-13.4): BR-01/BR-02 y campos derivados
    CONSTRAINT ck_sales_cancellation CHECK (is_cancellation = (invoice_no LIKE 'C%')),
    CONSTRAINT ck_sales_br_sign CHECK ((invoice_no LIKE 'C%') = (quantity < 0)),
    CONSTRAINT ck_sales_line_total CHECK (line_total = quantity * unit_price)
);
CREATE INDEX ix_sales_origin ON sales_transaction (batch_id, load_origin);

CREATE TABLE quarantine_record (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id           uuid        NOT NULL REFERENCES ingestion_batch(id),
    source_row_number  integer     NOT NULL CHECK (source_row_number >= 1),
    raw_payload        jsonb       NOT NULL,
    violations         jsonb       NOT NULL,
    ai_analysis        jsonb,
    summary            text        NOT NULL,
    max_severity       text        CHECK (max_severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    quarantine_reason  text        NOT NULL CHECK (quarantine_reason IN (
                          'AI_REVIEW_REQUIRED','POLICY_REJECTED','VERIFICATION_FAILED','REVALIDATION_FAILED',
                          'AI_UNAVAILABLE','AI_INVALID_RESPONSE','AI_BUDGET_EXCEEDED','PROCESSING_ERROR')),
    policy_decision    jsonb,
    ai_request_id      uuid,
    status             text        NOT NULL DEFAULT 'PENDING_REVIEW'
                          CHECK (status IN ('PENDING_REVIEW','RESOLVED','DISCARDED')),
    resolution_note    text,
    resolved_at        timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_quarantine_batch_row UNIQUE (batch_id, source_row_number)
);
CREATE INDEX ix_quarantine_filters ON quarantine_record (batch_id, quarantine_reason, max_severity, status);

CREATE TABLE correction_log (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id              uuid          NOT NULL,
    source_row_number     integer       NOT NULL,
    field_name            text          NOT NULL,
    error_code            text          NOT NULL,
    operation             text          NOT NULL CHECK (operation IN
                             ('TRIM','NORMALIZE_CASE','PARSE_DATE','PARSE_NUMBER','MAP_TO_ENUM','NULLIFY_TOKEN')),
    operation_params      jsonb,
    original_value        text,
    corrected_value       text,
    confidence            numeric(4,3)  NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    severity              text          NOT NULL CHECK (severity = 'LOW'),
    ai_explanation        text          NOT NULL,
    ai_provider           text          NOT NULL,
    ai_model              text          NOT NULL,
    prompt_version        text          NOT NULL,
    ai_request_id         uuid          NOT NULL,
    applied_at            timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_correction UNIQUE (batch_id, source_row_number, field_name),
    -- Una corrección solo existe si la fila limpia existe (misma transacción de chunk)
    CONSTRAINT fk_correction_sales FOREIGN KEY (batch_id, source_row_number)
        REFERENCES sales_transaction (batch_id, source_row_number),
    CONSTRAINT ck_correction_nullify CHECK ((corrected_value IS NULL) = (operation = 'NULLIFY_TOKEN'))
);
CREATE INDEX ix_correction_batch_op ON correction_log (batch_id, operation, field_name);

CREATE TABLE ai_call_log (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id         uuid        NOT NULL,
    batch_id           uuid        NOT NULL REFERENCES ingestion_batch(id),
    source_row_number  integer,
    endpoint           text        NOT NULL CHECK (endpoint IN ('TRIAGE','SCHEMA_DRIFT')),
    outcome            text        NOT NULL CHECK (outcome IN
                          ('SUCCESS','CACHE_HIT','TIMEOUT','UNAVAILABLE','CIRCUIT_OPEN','INVALID_RESPONSE','HTTP_ERROR')),
    http_status        integer,
    error_code         text,
    attempts           smallint    NOT NULL DEFAULT 1 CHECK (attempts >= 1),
    llm_calls          smallint,
    latency_ms         integer,
    provider           text,
    model              text,
    prompt_version     text,
    input_tokens       integer,
    output_tokens      integer,
    error_detail       text,
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_call_batch ON ai_call_log (batch_id, outcome);
```

Notas de implementación:
- El orden de escritura dentro del chunk es: `sales_transaction` → `correction_log` → `quarantine_record` (la FK compuesta exige que la fila limpia exista antes de su corrección). Con `ClassifierCompositeItemWriter`, el writer de filas limpias escribe ambas tablas en ese orden.
- `ck_sales_line_total` se cumple exactamente porque `quantity` es entero y `unit_price` tiene escala 2 (§3.2).
- Un `CHECK` que falla dentro del chunk provoca rollback y `FAILED` del job: es el comportamiento deseado (un bug de validación no debe pasar en silencio). **No** se agrega una *skip policy* para "tolerarlo".

> Nota: `RENAME_COLUMN` no aparece en `correction_log.operation` porque el mapeo de cabeceras se registra a nivel lote en `ingestion_batch.schema_mapping`, no por fila.

## 5. Ejemplos de contenido JSONB

`quarantine_record.violations`:

```json
[
  {
    "violationId": "v1",
    "field": "invoice_date",
    "errorCode": "INVALID_FORMAT",
    "receivedValue": "03/04/2010 09:15",
    "expected": { "type": "datetime", "format": "yyyy-MM-dd HH:mm:ss" },
    "technicalMessage": "Text '03/04/2010 09:15' could not be parsed at index 2",
    "exceptionType": "java.time.format.DateTimeParseException"
  }
]
```

`quarantine_record.policy_decision`:

```json
{
  "approved": false,
  "stage": "VERIFICATION",
  "violations": [
    {
      "violationId": "v1",
      "approved": true,
      "rejectionReason": null,
      "verification": { "passed": false, "code": "AMBIGUOUS_DATE", "detail": "dd/MM/yyyy and MM/dd/yyyy both parse '03/04/2010 09:15' to different dates" }
    }
  ]
}
```

(`stage` = `POLICY` o `VERIFICATION`: indica en qué paso se detuvo el registro; coherente con `quarantine_reason`. Si la política rechaza, `verification` es `null` porque los verificadores no llegan a ejecutarse.) <!-- rev: R-29 -->

`ingestion_batch.schema_mapping`:

```json
{
  "strategy": "AI",
  "mapping": {
    "InvoiceNo":   { "field": "invoice_no",   "origin": "AI",         "confidence": 0.98 },
    "StockCode":   { "field": "stock_code",   "origin": "IDENTITY" },
    "Description": { "field": "description",  "origin": "IDENTITY" },
    "Quantity":    { "field": "quantity",     "origin": "IDENTITY" },
    "InvoiceDate": { "field": "invoice_date", "origin": "IDENTITY" },
    "UnitPrice":   { "field": "unit_price",   "origin": "AI",         "confidence": 0.97 },
    "CustomerID":  { "field": "customer_id",  "origin": "NORMALIZED" },
    "Country":     { "field": "country",      "origin": "IDENTITY" }
  },
  "ignoredHeaders": [],
  "missingOptionalFields": [],
  "sampleValidation": { "rows": 100, "invalidRateInAiMappedFields": 0.0 },
  "aiRequestId": "5f1c..."
}
```

`strategy` = el origen "más fuerte" usado (`IDENTITY` < `NORMALIZED` < `AI`). `CustomerID` es `NORMALIZED`, no `AI`: normaliza a `customerid` igual que `Customer ID` (AC-05.2). La versión anterior del ejemplo lo trataba como resuelto por IA. <!-- rev: R-19 -->

## 6. Consultas de referencia

Invariante por lote (NFR-06):

```sql
SELECT b.read_count,
       (SELECT count(*) FROM sales_transaction s WHERE s.batch_id = b.id)  AS loaded,
       (SELECT count(*) FROM quarantine_record q WHERE q.batch_id = b.id)  AS quarantined
FROM ingestion_batch b WHERE b.id = :batchId;

-- ninguna fila en ambas tablas
SELECT s.source_row_number
FROM sales_transaction s
JOIN quarantine_record q ON q.batch_id = s.batch_id AND q.source_row_number = s.source_row_number
WHERE s.batch_id = :batchId;   -- debe devolver 0 filas

-- sin huecos ni números fuera de rango (NFR-06 c)   [rev: R-13]
SELECT count(DISTINCT n) = b.read_count AND coalesce(max(n), 0) = b.read_count AS ok
FROM ingestion_batch b
LEFT JOIN (
    SELECT batch_id, source_row_number AS n FROM sales_transaction WHERE batch_id = :batchId
    UNION ALL
    SELECT batch_id, source_row_number     FROM quarantine_record WHERE batch_id = :batchId
) u ON u.batch_id = b.id
WHERE b.id = :batchId
GROUP BY b.read_count;
```

Resumen de métricas (base de FR-15):

> ⚠️ **Trampa de JDBC + PostgreSQL** (rev: R-22): el patrón `(:batchId IS NULL OR batch_id = :batchId)` falla con `could not determine data type of parameter $1` cuando el parámetro llega como `null` sin tipo. Usar `CAST(:batchId AS uuid) IS NULL`, o construir el `WHERE` dinámicamente con parámetros. Los filtros `from`/`to` se aplican sobre `ingestion_batch.received_at` y las demás tablas se unen por `batch_id` (AC-15.2); las consultas de abajo muestran solo el filtro por lote por brevedad.

```sql
SELECT load_origin, count(*) FROM sales_transaction WHERE (CAST(:batchId AS uuid) IS NULL OR batch_id = :batchId) GROUP BY load_origin;
SELECT quarantine_reason, coalesce(max_severity, 'UNCLASSIFIED'), count(*) FROM quarantine_record WHERE (CAST(:batchId AS uuid) IS NULL OR batch_id = :batchId) GROUP BY 1, 2;
SELECT operation, field_name, count(*) FROM correction_log WHERE (CAST(:batchId AS uuid) IS NULL OR batch_id = :batchId) GROUP BY 1, 2;
SELECT outcome, count(*), avg(latency_ms),
       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95
FROM ai_call_log WHERE (CAST(:batchId AS uuid) IS NULL OR batch_id = :batchId) GROUP BY outcome;
```

## 7. Retención

Fuera del MVP. Todo se conserva indefinidamente; `docker compose down -v` borra el volumen.
