# 06 — Contratos de API

Este documento define dos contratos:

- **Parte A — Contrato interno** `pipeline-service → ai-service` (el más crítico: ambos lados deben coincidir exactamente).
- **Parte B — API pública** de `pipeline-service` (la que usa el evaluador).

## Convenciones comunes

- JSON en `camelCase`. Fechas en ISO-8601. UUIDs en formato canónico.
- Versionado en la ruta (`/v1`, `/api/v1`). **Un cambio incompatible exige nueva versión de ruta + ADR.** Cambios compatibles: agregar campos opcionales en respuestas (el cliente Java **ignora** campos desconocidos); agregar valores de enum **no** es compatible (el cliente Java falla ante valores desconocidos a propósito). <!-- rev: R-21 -->
- **Dos fronteras, dos reglas** (antes se contradecían `06` y `09` §7.5): <!-- rev: R-21 -->
  - *LLM → `ai-service`* (no confiable): validación estricta, `extra="forbid"`; cualquier campo inventado o enum desconocido es salida inválida (reparación o `502 LLM_INVALID_OUTPUT`).
  - *`ai-service` → pipeline* (contrato versionado): el pipeline ignora campos desconocidos, falla ante enums desconocidos o campos obligatorios ausentes, y aplica la validación semántica de AC-08.7. Ambas fallas → `AI_INVALID_RESPONSE`.
- Errores: `application/problem+json` (RFC 9457) con extensiones `code` y `requestId` (NFR-20).
- Enums en MAYÚSCULAS_CON_GUION_BAJO.
- Fuente de verdad del contrato interno: los modelos Pydantic de `ai-service/app/domain/contracts.py`, exportados a `contracts/ai-service.openapi.json`. Este documento y esa spec deben coincidir; si difieren, se corrige la que esté mal mediante PR que actualice ambos.
- Fixtures de ejemplo en `contracts/fixtures/*.json`; los tests de ambos servicios las usan.

---

# Parte A — Contrato interno (`ai-service`)

Base URL interna: `http://ai-service:8000`

### Headers de solicitud

| Header | Obligatorio | Descripción |
|---|---|---|
| `Content-Type: application/json` | Sí | |
| `X-Request-Id` | Sí | UUID generado por el pipeline; se devuelve igual en la respuesta |
| `X-Batch-Id` | Sí | UUID del lote, solo para logs |

## A.1 `POST /v1/triage`

Analiza **un registro** con una o más violaciones.

### Request — `TriageRequest`

```json
{
  "requestId": "0b6c3f5e-7f0f-4a55-9d0e-2a1f0f1b6a10",
  "batchId": "8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f",
  "schema": { "name": "sales_transaction", "version": "1" },
  "source": { "filename": "dirty-1k.csv", "type": "CSV" },
  "record": {
    "sourceRowNumber": 152,
    "raw": {
      "Invoice": "536520",
      "StockCode": "21123",
      "Description": "SET/10 IVORY POLKADOT PARTY CANDLES",
      "Quantity": "12",
      "InvoiceDate": "25/12/2010 08:26",
      "Price": "£1,25",
      "Customer ID": "[REDACTED]",
      "Country": "UK"
    }
  },
  "violations": [
    {
      "violationId": "v1",
      "field": "invoice_date",
      "sourceHeader": "InvoiceDate",
      "errorCode": "INVALID_FORMAT",
      "receivedValue": "25/12/2010 08:26",
      "expected": { "type": "datetime", "format": "yyyy-MM-dd HH:mm:ss", "min": "2009-01-01 00:00:00" },
      "technicalMessage": "Text '25/12/2010 08:26' could not be parsed at index 2",
      "exceptionType": "java.time.format.DateTimeParseException"
    },
    {
      "violationId": "v2",
      "field": "unit_price",
      "sourceHeader": "Price",
      "errorCode": "INVALID_FORMAT",
      "receivedValue": "£1,25",
      "expected": { "type": "decimal", "scale": 2, "min": "0", "max": "100000", "decimalSeparator": "." },
      "technicalMessage": "Character £ is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.",
      "exceptionType": "java.lang.NumberFormatException"
    },
    {
      "violationId": "v3",
      "field": "country",
      "sourceHeader": "Country",
      "errorCode": "INVALID_ENUM_VALUE",
      "receivedValue": "UK",
      "expected": { "type": "enum", "allowedValues": ["Australia", "Austria", "Belgium", "EIRE", "France", "Germany", "United Kingdom", "..."] },
      "technicalMessage": "Value 'UK' is not in catalog countries.v1",
      "exceptionType": null
    }
  ],
  "options": { "explanationLanguage": "es" }
}
```

| Campo | Tipo | Oblig. | Notas |
|---|---|---|---|
| `requestId` | uuid | Sí | Igual a `X-Request-Id` |
| `batchId` | uuid | Sí | |
| `schema.name` / `schema.version` | string | Sí | |
| `source.filename` / `source.type` | string / `CSV\|JSON` | Sí | |
| `record.sourceRowNumber` | int ≥ 1 | Sí | |
| `record.raw` | object<string, string\|null> | Sí | Cabecera original → valor crudo (con redacción NFR-15). Para `MALFORMED_ROW`: `{ "_rawLine": "..." }` truncado a 2 000 caracteres, o `{}` si `AI_SEND_FULL_RECORD=false` (AC-07.4) |
| `violations` | array (1..20) | Sí | |
| `violations[].violationId` | string | Sí | Único dentro de la solicitud |
| `violations[].field` | string | Sí | Campo canónico, o `_record` para violaciones a nivel registro |
| `violations[].sourceHeader` | string\|null | No | |
| `violations[].errorCode` | enum | Sí | Catálogo de `02` §3 (excepto `SCHEMA_DRIFT`) |
| `violations[].receivedValue` | string\|null | Sí | |
| `violations[].expected` | object | Sí | Claves posibles: `type`, `format`, `pattern`, `min`, `max`, `maxLength`, `scale`, `decimalSeparator`, `allowedValues`, `rule` |
| `violations[].technicalMessage` | string (≤ 1000) | Sí | |
| `violations[].exceptionType` | string\|null | No | |
| `options.explanationLanguage` | `es\|en` | No | Default: `AI_EXPLANATION_LANGUAGE` |

### Response 200 — `TriageResponse`

```json
{
  "requestId": "0b6c3f5e-7f0f-4a55-9d0e-2a1f0f1b6a10",
  "recordDecision": "AUTO_FIXABLE",
  "maxSeverity": "LOW",
  "summary": "La fila tiene tres problemas de formato sin pérdida de información: fecha en formato día/mes/año, precio con símbolo de libra y coma decimal, y país abreviado.",
  "analyses": [
    {
      "violationId": "v1",
      "field": "invoice_date",
      "rootCause": "FORMAT_MISMATCH",
      "explanation": "La fecha viene en formato día/mes/año (25/12/2010). Como el día es 25, no hay ambigüedad con el mes.",
      "severity": "LOW",
      "classification": "AUTO_FIXABLE",
      "confidence": 0.97,
      "correction": {
        "operation": "PARSE_DATE",
        "originalValue": "25/12/2010 08:26",
        "proposedValue": "2010-12-25 08:26:00",
        "parameters": { "sourcePattern": "dd/MM/yyyy HH:mm" }
      },
      "suggestedHumanAction": null
    },
    {
      "violationId": "v2",
      "field": "unit_price",
      "rootCause": "LOCALE_FORMAT",
      "explanation": "El precio incluye el símbolo £ y usa coma como separador decimal (formato europeo).",
      "severity": "LOW",
      "classification": "AUTO_FIXABLE",
      "confidence": 0.95,
      "correction": {
        "operation": "PARSE_NUMBER",
        "originalValue": "£1,25",
        "proposedValue": "1.25",
        "parameters": { "decimalSeparator": ",", "groupingSeparator": null, "strippedSymbols": ["£"] }
      },
      "suggestedHumanAction": null
    },
    {
      "violationId": "v3",
      "field": "country",
      "rootCause": "SYNONYM",
      "explanation": "'UK' es la abreviatura habitual de Reino Unido; el catálogo usa 'United Kingdom'.",
      "severity": "LOW",
      "classification": "AUTO_FIXABLE",
      "confidence": 0.96,
      "correction": {
        "operation": "MAP_TO_ENUM",
        "originalValue": "UK",
        "proposedValue": "United Kingdom",
        "parameters": {}
      },
      "suggestedHumanAction": null
    }
  ],
  "meta": {
    "provider": "mock",
    "model": "mock-rules-v1",
    "promptVersion": "triage_v1",
    "latencyMs": 3,
    "cacheHit": false,
    "inputTokens": null,
    "outputTokens": null,
    "llmCalls": 0
  }
}
```

Ejemplo de violación que requiere humano:

```json
{
  "violationId": "v1",
  "field": "unit_price",
  "rootCause": "MISSING_VALUE",
  "explanation": "El precio unitario está vacío. No es posible deducirlo de forma segura a partir de la fila.",
  "severity": "HIGH",
  "classification": "NEEDS_HUMAN_REVIEW",
  "confidence": 0.99,
  "correction": null,
  "suggestedHumanAction": "Consultar el precio unitario del producto en el sistema de origen y reenviar la fila con el valor."
}
```

(La versión anterior del ejemplo mencionaba el producto `21123` y la factura `536520`. Como los análisis a nivel campo se cachean y se reutilizan en otras filas con la misma firma, ese texto habría aparecido en filas de otros productos y facturas. La fila concreta ya se identifica por `batchId` + `sourceRowNumber` en la cuarentena.) <!-- rev: R-09 -->

| Campo | Tipo | Notas |
|---|---|---|
| `requestId` | uuid | Eco |
| `recordDecision` | `AUTO_FIXABLE\|NEEDS_HUMAN_REVIEW` | `AUTO_FIXABLE` solo si **todas** las `analyses` lo son (el servicio lo garantiza) |
| `maxSeverity` | `LOW\|MEDIUM\|HIGH\|CRITICAL` | Máximo de `analyses[].severity` |
| `summary` | string (≤ 1000) | Resumen legible del registro. `ai-service` **recorta** textos que excedan su máximo (no es error de contrato) |
| `analyses` | array | **Exactamente una** por `violationId` recibido, mismo orden. El pipeline lo verifica (AC-08.7) |
| `analyses[].rootCause` | enum | `FORMAT_MISMATCH`, `LOCALE_FORMAT`, `WHITESPACE`, `CASING`, `SYNONYM`, `NULL_TOKEN`, `MISSING_VALUE`, `IMPLAUSIBLE_VALUE`, `TYPE_MISMATCH`, `AMBIGUOUS_VALUE`, `BUSINESS_RULE`, `STRUCTURAL`, `UNKNOWN` |
| `analyses[].explanation` | string (≤ 800) | |
| `analyses[].severity` | enum | Ver tabla de severidad abajo |
| `analyses[].classification` | `AUTO_FIXABLE\|NEEDS_HUMAN_REVIEW` | |
| `analyses[].confidence` | number [0,1] | |
| `analyses[].correction` | object\|null | Obligatorio (no nulo) si `AUTO_FIXABLE`; nulo si `NEEDS_HUMAN_REVIEW` |
| `correction.operation` | enum | `TRIM`, `NORMALIZE_CASE`, `PARSE_DATE`, `PARSE_NUMBER`, `MAP_TO_ENUM`, `NULLIFY_TOKEN` |
| `correction.originalValue` | string\|null | Debe ser igual a `receivedValue` |
| `correction.proposedValue` | string\|null | Valor en formato canónico como string; `null` solo con `NULLIFY_TOKEN` |
| `correction.parameters` | object | `PARSE_DATE`: `sourcePattern` (obligatorio, sintaxis `java.time.DateTimeFormatter`, con hora y minuto; restricciones en `02` §4). `PARSE_NUMBER`: `decimalSeparator` (obligatorio), `groupingSeparator`, `strippedSymbols` (solo `£`, solo en `unit_price`). Otras operaciones: `{}` |
| `analyses[].suggestedHumanAction` | string\|null | Obligatorio si `NEEDS_HUMAN_REVIEW`. En violaciones a nivel campo **no** menciona valores de otras columnas (se cachea y reutiliza, AC-08.4 c) |
| `meta` | object | Trazabilidad (NFR-12): `provider`, `model`, `promptVersion`, `latencyMs`, `cacheHit`, `inputTokens`, `outputTokens`, `llmCalls` (llamadas reales al LLM en esta solicitud, incluidos reintento y reparación; 0 si caché) |

### Escala de severidad (común al prompt, al mock y a la política)

| Severidad | Criterio | Ejemplos |
|---|---|---|
| `LOW` | Problema de formato/representación; la corrección preserva la semántica sin interpretación | espacios, mayúsculas, `dd/MM/yyyy` no ambiguo, coma decimal, `"17850.0"`, `"UK"` |
| `MEDIUM` | Corrección plausible pero requiere interpretación | fecha ambigua `03/04/2010`, sinónimo poco común, número con separador de miles ambiguo (`2,550`) |
| `HIGH` | Falta información o el valor es implausible | precio vacío **o con token nulo** (`N/A`), precio negativo, cantidad 999999, texto en campo numérico, descripción demasiado larga |
| `CRITICAL` | Registro estructuralmente roto o inconsistencia de negocio | fila con columnas desplazadas, cancelación con cantidad positiva |

(La versión anterior ponía "token nulo en campo requerido" en `MEDIUM` y "precio vacío" en `HIGH`; ambos son `MISSING_REQUIRED_FIELD` y deben tener la misma severidad.) <!-- rev: R-20 -->

<!-- rev: R-20 -->
**Piso de severidad por `errorCode`** (aplicado por `rules.py` y por el mock; el LLM puede subir la severidad, nunca bajarla del piso):

| `errorCode` | Severidad mínima | Clasificación forzada |
|---|---|---|
| `MALFORMED_ROW`, `BUSINESS_RULE_VIOLATION` | `CRITICAL` | `NEEDS_HUMAN_REVIEW` |
| `MISSING_REQUIRED_FIELD`, `OUT_OF_RANGE`, `MAX_LENGTH_EXCEEDED` | `HIGH` | `NEEDS_HUMAN_REVIEW` |
| `INVALID_TYPE` | `MEDIUM` | — |
| `INVALID_FORMAT`, `PATTERN_MISMATCH`, `INVALID_ENUM_VALUE` | `LOW` | — |

### Reglas duras del servicio de IA (se aplican después del LLM)

1. `errorCode ∈ {MISSING_REQUIRED_FIELD, OUT_OF_RANGE, MAX_LENGTH_EXCEEDED, BUSINESS_RULE_VIOLATION, MALFORMED_ROW}` → `classification = NEEDS_HUMAN_REVIEW`, `correction = null`.
2. Severidad elevada al piso de la tabla anterior.
3. `AUTO_FIXABLE` sin `correction` válida → `NEEDS_HUMAN_REVIEW`.
4. `correction.operation` fuera del enum → respuesta inválida (reparación o `502`).
5. Textos recortados a su longitud máxima.
6. `recordDecision` y `maxSeverity` se recalculan a partir de `analyses`.

(El pipeline vuelve a aplicar su política completa: estas reglas son defensa en profundidad, no la autoridad.)

## A.2 `POST /v1/schema-drift/analyze`

### Request — `SchemaDriftRequest`

```json
{
  "requestId": "c1d2e3f4-0000-4000-8000-000000000001",
  "batchId": "8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f",
  "schema": {
    "name": "sales_transaction",
    "version": "1",
    "fields": [
      { "name": "invoice_no", "expectedHeader": "Invoice", "type": "string", "required": true, "description": "Número de factura de 6 dígitos, prefijo C para cancelaciones" },
      { "name": "unit_price", "expectedHeader": "Price", "type": "decimal", "required": true, "description": "Precio unitario en libras esterlinas" }
    ]
  },
  "receivedHeaders": ["InvoiceNo", "StockCode", "Description", "Quantity", "InvoiceDate", "UnitPrice", "CustomerID", "Country"],
  "unresolvedHeaders": ["InvoiceNo", "UnitPrice"],
  "sampleRows": [
    { "InvoiceNo": "536365", "StockCode": "85123A", "Description": "WHITE HANGING HEART T-LIGHT HOLDER", "Quantity": "6", "InvoiceDate": "2010-12-01 08:26:00", "UnitPrice": "2.55", "CustomerID": "[REDACTED]", "Country": "United Kingdom" }
  ],
  "options": { "explanationLanguage": "es" }
}
```

(`schema.fields` contiene **todos** los campos; el ejemplo está recortado. `sampleRows`: máximo 5, con redacción NFR-15. `unresolvedHeaders` contiene solo lo que AC-05.2 no resolvió: `CustomerID` ya no aparece porque normaliza igual que `Customer ID`; la versión anterior del ejemplo lo incluía.) <!-- rev: R-19 -->

### Response 200 — `SchemaDriftResponse`

```json
{
  "requestId": "c1d2e3f4-0000-4000-8000-000000000001",
  "decision": "AUTO_MAPPABLE",
  "summary": "La fuente usa los nombres de columnas de la versión anterior del dataset. Las dos columnas no reconocidas tienen equivalentes claros.",
  "mappings": [
    { "receivedHeader": "InvoiceNo",  "canonicalField": "invoice_no",  "confidence": 0.98, "rationale": "Mismo concepto; los valores de muestra tienen 6 dígitos." },
    { "receivedHeader": "UnitPrice",  "canonicalField": "unit_price",  "confidence": 0.97, "rationale": "Valores decimales pequeños coherentes con precio unitario." }
  ],
  "unmappedRequiredFields": [],
  "extraHeaders": [],
  "meta": { "provider": "mock", "model": "mock-rules-v1", "promptVersion": "schema_drift_v1", "latencyMs": 2, "cacheHit": false, "inputTokens": null, "outputTokens": null, "llmCalls": 0 }
}
```

- `mappings` contiene solo las cabeceras de `unresolvedHeaders`. `canonicalField` puede ser `null` (cabecera sin equivalente).
- `decision`: `AUTO_MAPPABLE | NEEDS_HUMAN_REVIEW`. El pipeline aplica AC-05.4 y el verificador de muestra AC-05.7 independientemente de este valor.
- Las respuestas de schema drift **no** se cachean (se llama una vez por lote).

## A.3 `GET /health`

```json
{ "status": "ok", "provider": "openai_compatible", "providerConfigured": true }
```

`status`: `ok` | `degraded` (proveedor real sin API key). Siempre HTTP 200 mientras el proceso esté vivo (el healthcheck de Compose solo valida que el servicio responde).

## A.4 `GET /v1/info`

```json
{ "service": "pipemend-ai-service", "version": "0.1.0", "provider": "mock", "model": "mock-rules-v1",
  "promptVersions": { "triage": "triage_v1", "schemaDrift": "schema_drift_v1" }, "cache": { "entries": 128, "maxEntries": 5000 },
  "limits": { "requestDeadlineSeconds": 25, "maxLlmCallsPerDay": 2000, "llmCallsToday": 37 } }
```

## A.5 Errores del servicio de IA

<!-- rev: R-10, R-11 -->
Regla general (NFR-07): el pipeline **no** reintenta errores que traen `code` de `ai-service`, porque `ai-service` ya reintentó internamente dentro de su plazo. Solo reintenta fallas de transporte (conexión rechazada, timeout de conexión, 502/503/504 **sin** cuerpo `problem+json`). La versión anterior de esta tabla marcaba reintentos del pipeline en 429, 502 y 504, lo que multiplicaba las llamadas al LLM y contradecía la regla de "no reintentar `503 PROVIDER_NOT_CONFIGURED`".

| HTTP | `code` | Cuándo | ¿Pipeline reintenta? | ¿Cuenta para el circuit breaker? | Resultado en el pipeline |
|---|---|---|---|---|---|
| 400 | `INVALID_REQUEST` | Request que no cumple el contrato (semántica) | No | No | `AI_INVALID_RESPONSE` + log `ERROR` (indica bug de contrato) |
| 422 | `VALIDATION_ERROR` | Validación de FastAPI/Pydantic | No | No | igual que 400 |
| 429 | `RATE_LIMITED` | El proveedor limitó y el reintento interno no alcanzó | No | Sí | `AI_UNAVAILABLE` |
| 429 | `LLM_BUDGET_EXHAUSTED` | Se alcanzó `LLM_MAX_CALLS_PER_DAY` (AC-08.8) | No | No | `AI_BUDGET_EXCEEDED`; el presupuesto del lote se marca agotado |
| 502 | `LLM_INVALID_OUTPUT` | Salida del LLM inválida tras reparación | No (con `temperature = 0` repetir da la misma salida) | No | `AI_INVALID_RESPONSE` |
| 502 | `LLM_PROVIDER_ERROR` | Error del proveedor (5xx, auth) | No | Sí | `AI_UNAVAILABLE` |
| 503 | `PROVIDER_NOT_CONFIGURED` | Falta API key u otra config | No | Sí (abre rápido el circuito) | `AI_UNAVAILABLE` |
| 504 | `LLM_TIMEOUT` | Se agotó el plazo `AI_REQUEST_DEADLINE_SECONDS` | No | Sí | `AI_UNAVAILABLE` |
| — | (transporte) | Conexión rechazada / timeout de conexión / 5xx sin `problem+json` | Sí (2, backoff) | Sí | `AI_UNAVAILABLE` |
| — | (transporte) | *Read timeout* del pipeline | No | Sí | `AI_UNAVAILABLE` |

Ejemplo:

```json
{
  "type": "https://pipemend.dev/problems/llm-timeout",
  "title": "LLM timeout",
  "status": 504,
  "detail": "Provider did not respond within 20s",
  "instance": "/v1/triage",
  "code": "LLM_TIMEOUT",
  "requestId": "0b6c3f5e-7f0f-4a55-9d0e-2a1f0f1b6a10"
}
```

(El dominio en `type` es ilustrativo; no necesita resolver.)

---

# Parte B — API pública de `pipeline-service`

Base URL: `http://localhost:8080`. Swagger UI: `/swagger-ui.html`.

| Método | Ruta | FR | Descripción |
|---|---|---|---|
| `POST` | `/api/v1/ingestions` | FR-01, FR-02, FR-04 | Carga de archivo (multipart `file`) |
| `GET` | `/api/v1/ingestions` | FR-14 | Lista paginada de lotes (`status`, `page`, `size`) |
| `GET` | `/api/v1/ingestions/{batchId}` | FR-14 | Detalle y contadores de un lote |
| `GET` | `/api/v1/ingestions/samples` | FR-03 | Lista de muestras disponibles |
| `POST` | `/api/v1/ingestions/samples/{sampleName}` | FR-03 | Ejecuta una muestra |
| `POST` | `/api/v1/ingestions/{batchId}/restart` | FR-21 | Reinicia lote `FAILED` (Could) |
| `GET` | `/api/v1/metrics/summary` | FR-15 | Métricas (`batchId`, `from`, `to`) |
| `GET` | `/api/v1/quarantine` | FR-16 | Lista (`batchId`, `severity`, `reason`, `status`, `page`, `size`) |
| `GET` | `/api/v1/quarantine/{id}` | FR-16 | Detalle con análisis de IA |
| `PATCH` | `/api/v1/quarantine/{id}` | FR-22 | Resolver/descartar (Could) |
| `GET` | `/api/v1/corrections` | FR-16 | Lista (`batchId`, `field`, `operation`, `page`, `size`) |
| `GET` | `/actuator/health` | NFR-09 | Health |

Paginación: `page` (0-based, default 0), `size` (default 20, máx 200). Respuesta paginada:

```json
{ "content": [ ... ], "page": 0, "size": 20, "totalElements": 134, "totalPages": 7 }
```

## B.1 `POST /api/v1/ingestions`

```bash
curl -F "file=@data/samples/dirty-1k.csv" http://localhost:8080/api/v1/ingestions
```

**202 Accepted** (header `Location: /api/v1/ingestions/8a2e...`):

```json
{
  "batchId": "8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f",
  "status": "RUNNING",
  "sourceFilename": "dirty-1k.csv",
  "sourceType": "CSV",
  "sourceChecksum": "3f9a...c01",
  "receivedAt": "2026-10-05T14:03:11Z"
}
```

**409 Conflict**:

```json
{
  "type": "https://pipemend.dev/problems/duplicate-ingestion",
  "title": "Duplicate ingestion",
  "status": 409,
  "detail": "A file with the same content was already ingested.",
  "code": "DUPLICATE_INGESTION",
  "existingBatchId": "8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f",
  "existingStatus": "COMPLETED"
}
```

Otros errores: `400 EMPTY_FILE`, `400 MISSING_FILE`, `413 FILE_TOO_LARGE`, `415 UNSUPPORTED_MEDIA_TYPE`.

- `status` en la respuesta `202` es `RECEIVED` si hay otro lote en curso (cola de un solo hilo, NFR-10) o `RUNNING` si el job ya arrancó. <!-- rev: R-28 -->
- `409` se devuelve para cualquier lote previo con el mismo checksum en estado distinto de `REJECTED` (AC-04.2); `existingStatus` indica cuál. Si el lote previo está `REJECTED`, se crea uno nuevo (AC-04.3b). <!-- rev: R-12 -->
- El formato se decide por la extensión del archivo, no por el `Content-Type` de la parte (AC-01.2). <!-- rev: R-18 -->

## B.2 `GET /api/v1/ingestions/{batchId}`

```json
{
  "batchId": "8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f",
  "status": "COMPLETED",
  "sourceFilename": "dirty-1k.csv",
  "sourceType": "CSV",
  "sourceChecksum": "3f9a...c01",
  "schema": { "name": "sales_transaction", "version": "1" },
  "schemaMapping": { "strategy": "IDENTITY" },
  "counts": { "read": 1000, "direct": 950, "autoCorrected": 34, "quarantined": 16 },
  "aiCalls": 21,
  "failureReason": null,
  "schemaDriftReport": null,
  "receivedAt": "2026-10-05T14:03:11Z",
  "startedAt": "2026-10-05T14:03:11Z",
  "finishedAt": "2026-10-05T14:03:19Z",
  "durationMs": 8123
}
```

## B.3 `GET /api/v1/metrics/summary`

```json
{
  "scope": { "batchId": null, "from": null, "to": null },
  "batches": { "total": 6, "completed": 5, "failed": 0, "rejected": 1, "running": 0 },
  "records": {
    "read": 12000,
    "loadedDirect": 11412,
    "autoCorrected": 380,
    "quarantined": 208,
    "directRate": 0.9510,
    "autoCorrectionRate": 0.0317,
    "quarantineRate": 0.0173
  },
  "ai": {
    "calls": 187,
    "cacheHits": 423,
    "failures": 2,
    "avgLatencyMs": 912,
    "p95LatencyMs": 2140,
    "inputTokens": 145230,
    "outputTokens": 38012,
    "llmCalls": 195
  },
  "quarantineBySeverity": { "LOW": 3, "MEDIUM": 41, "HIGH": 117, "CRITICAL": 44, "UNCLASSIFIED": 3 },
  "quarantineByReason": {
    "AI_REVIEW_REQUIRED": 180, "POLICY_REJECTED": 22, "VERIFICATION_FAILED": 3,
    "REVALIDATION_FAILED": 0, "AI_UNAVAILABLE": 3, "AI_INVALID_RESPONSE": 0,
    "AI_BUDGET_EXCEEDED": 0, "PROCESSING_ERROR": 0
  },
  "corrections": 402,
  "correctionsByOperation": { "TRIM": 120, "NORMALIZE_CASE": 40, "PARSE_DATE": 88, "PARSE_NUMBER": 101, "MAP_TO_ENUM": 39, "NULLIFY_TOKEN": 14 },
  "correctionsByField": { "invoice_date": 88, "unit_price": 70, "country": 109, "customer_id": 45, "stock_code": 60, "quantity": 0, "invoice_no": 30, "description": 0 }
}
```

<!-- rev: R-22 -->
- `failures` = llamadas con `outcome` distinto de `SUCCESS` y `CACHE_HIT`.
- `corrections` cuenta **campos** corregidos (filas de `correction_log`); `autoCorrected` cuenta **registros**. Un registro con defectos combinados aporta 1 a `autoCorrected` y varias a `corrections`, así que `corrections ≥ autoCorrected`. `sum(correctionsByOperation) = sum(correctionsByField) = corrections`.
- `sum(quarantineBySeverity) = sum(quarantineByReason) = quarantined`; `UNCLASSIFIED` = cuarentena sin análisis de IA (AC-15.6).
- (Ejemplo corregido: la versión anterior tenía 60 correcciones en `description`, que no tiene ninguna violación corregible, 0 en `invoice_no` pese a que el catálogo inyecta espacios en ese campo, y `autoCorrected` igual a la suma de correcciones.)
- Tasas redondeadas a 4 decimales; `read = loadedDirect + autoCorrected + quarantined` para lotes `COMPLETED` (AC-15.3). Los contadores de lotes `RUNNING` se incluyen solo si `batchId` se especifica.

## B.4 `GET /api/v1/quarantine/{id}`

```json
{
  "id": 912,
  "batchId": "8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f",
  "sourceRowNumber": 377,
  "status": "PENDING_REVIEW",
  "quarantineReason": "AI_REVIEW_REQUIRED",
  "maxSeverity": "CRITICAL",
  "summary": "La factura C536391 es una cancelación, pero la cantidad es positiva (24). Esto contradice la regla de negocio.",
  "rawPayload": { "Invoice": "C536391", "StockCode": "22556", "Quantity": "24", "...": "..." },
  "violations": [ { "violationId": "v1", "field": "_record", "errorCode": "BUSINESS_RULE_VIOLATION", "...": "..." } ],
  "aiAnalysis": { "recordDecision": "NEEDS_HUMAN_REVIEW", "analyses": [ "..." ], "meta": { "...": "..." } },
  "policyDecision": null,
  "createdAt": "2026-10-05T14:03:15Z"
}
```

## B.5 `GET /api/v1/corrections` (elemento)

```json
{
  "id": 55,
  "batchId": "8a2e6d1c-43b2-4f55-8f6b-1f2b3c4d5e6f",
  "sourceRowNumber": 152,
  "salesTransactionId": 10452,
  "field": "invoice_date",
  "errorCode": "INVALID_FORMAT",
  "operation": "PARSE_DATE",
  "operationParams": { "sourcePattern": "dd/MM/yyyy HH:mm" },
  "originalValue": "25/12/2010 08:26",
  "correctedValue": "2010-12-25 08:26:00",
  "confidence": 0.97,
  "severity": "LOW",
  "aiExplanation": "La fecha viene en formato día/mes/año...",
  "ai": { "provider": "mock", "model": "mock-rules-v1", "promptVersion": "triage_v1", "requestId": "0b6c..." },
  "appliedAt": "2026-10-05T14:03:14Z"
}
```

`salesTransactionId` se resuelve por `JOIN` sobre `(batch_id, source_row_number)` (ver `05` §3.4). <!-- rev: R-14 -->

## Mapeo DTO ↔ tabla (para evitar desalineaciones)

| JSON (`camelCase`) | Columna (`snake_case`) |
|---|---|
| `batchId` | `batch_id` |
| `sourceRowNumber` | `source_row_number` |
| `quarantineReason` | `quarantine_reason` |
| `maxSeverity` | `max_severity` |
| `aiAnalysis` | `ai_analysis` |
| `operationParams` | `operation_params` |
| `correctedValue` | `corrected_value` |
| `loadedDirect` / `autoCorrected` | `load_origin = 'DIRECT'` / `'AUTO_CORRECTED'` |
| `salesTransactionId` | `sales_transaction.id` vía `JOIN` por `(batch_id, source_row_number)` |
| `llmCalls` (en `meta`) | `ai_call_log.llm_calls` |
