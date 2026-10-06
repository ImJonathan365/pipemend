# 02 — Requisitos funcionales

## 0. Convenciones

- Cada requisito tiene un ID estable `FR-xx`. **Los IDs no se renumeran nunca**; si un requisito se elimina, se marca `DEPRECATED` y se conserva.
- Prioridad MoSCoW: **Must** (el MVP no existe sin esto), **Should** (esperado, recortable si hay atraso), **Could** (solo si sobra tiempo), **Won't** (explícitamente fuera del MVP).
- Criterios de aceptación en formato **Given / When / Then** (Dado / Cuando / Entonces). Cada criterio debe tener al menos un test automatizado que lo cubra (ver `08-definition-of-done.md`).
- Los códigos de error, operaciones y enums referenciados están definidos en este documento (§2–§4) y en `06-contrato-api.md`. **No se inventan valores nuevos sin actualizar ambos documentos.**
- Un AC que diga "se registra", "se loguea" o "se guarda" sin indicar dónde se interpreta como **columna o tabla de `05-modelo-de-datos.md`**, nunca como log de texto. Si no hay columna, el AC está incompleto: se consulta antes de implementar. <!-- rev: R-33 -->
- La prioridad de un FR aplica a todos sus AC salvo que el AC indique otra prioridad explícitamente (p. ej., AC-20.3). <!-- rev: R-26 -->

## 1. Resumen

| ID | Requisito | Prioridad | Sprint |
|---|---|---|---|
| FR-01 | Ingesta de archivo CSV por carga (upload) | Must | 2 |
| FR-02 | Ingesta de archivo JSON por carga | Should | 6 |
| FR-03 | Ejecución de datasets de muestra empaquetados | Should | 6 |
| FR-04 | Registro de lote e idempotencia por checksum | Must | 2 |
| FR-05 | Detección de *schema drift* y mapeo asistido por IA | Should | 7 |
| FR-06 | Validación contra schema declarativo versionado | Must | 2 |
| FR-07 | Captura del contexto del error | Must | 3 |
| FR-08 | Triage con IA de registros inválidos | Must | 3–4 |
| FR-09 | Política de corrección determinista | Must | 4 |
| FR-10 | Aplicación de corrección, verificación, revalidación y registro | Must | 4 |
| FR-11 | Cuarentena con análisis de IA adjunto | Must | 3–4 |
| FR-12 | Degradación segura ante falla del servicio de IA | Must | 4 |
| FR-13 | Transformación y carga de datos limpios | Must | 2 |
| FR-14 | Consulta de estado de lotes | Must | 2 |
| FR-15 | Endpoint de métricas | Must | 6 |
| FR-16 | Consulta de cuarentena y correcciones | Should (AC-16.2 y AC-16.3 no recortables: los usa la demo) | 6 |
| FR-17 | Proveedor LLM configurable por variable de entorno | Must | 3 (mock), 5 (reales) |
| FR-18 | Documentación OpenAPI en ambos servicios | Must | 2–6 |
| FR-19 | Generador de datos sucios con *ground truth* | Must | 1 |
| FR-20 | Evaluación de calidad del triage | Should (AC-20.3 es **Must**, Sprint 4) | 7 |
| FR-21 | Reinicio de lote fallido | Could | 7 |
| FR-22 | Marcar registro de cuarentena como resuelto/descartado | Could | 8 |
| FR-23 | Reprocesamiento automático de la cuarentena | Won't | — |

## 2. Schema canónico del MVP: `sales_transaction` v1

El MVP procesa **un** tipo de entidad: líneas de transacciones de venta minorista, basadas en el dataset *Online Retail II* (ver `10-datasets.md`). El schema se define de forma declarativa en `pipeline-service/src/main/resources/schemas/sales_transaction.v1.yaml` y **se congela al final del Sprint 1** tras perfilar el dataset. Cambiarlo después requiere un ADR.

| Campo canónico | Cabecera de origen esperada | Tipo | Requerido | Regla |
|---|---|---|---|---|
| `invoice_no` | `Invoice` | string | Sí | Regex `^[CA]?\d{6}$` (`C` = cancelación, `A` = ajuste) |
| `stock_code` | `StockCode` | string | Sí | 1–20 caracteres, regex `^[A-Za-z0-9](?:[A-Za-z0-9_ \-]{0,18}[A-Za-z0-9_])?$` (sin espacios al inicio/fin; ajustable en Sprint 1 tras perfilado). **Sensible a mayúsculas**: `85123a` y `85123A` son códigos distintos en el dataset real |
| `description` | `Description` | string | No | Máx. 255 caracteres |
| `quantity` | `Quantity` | integer | Sí | `-100000 ≤ x ≤ 100000`, `x ≠ 0` |
| `invoice_date` | `InvoiceDate` | datetime | Sí | Formato `yyyy-MM-dd HH:mm:ss`; `2009-01-01 ≤ x ≤ now()` (`now()` proviene de un `java.time.Clock` inyectado, en UTC, para que los tests sean deterministas) |
| `unit_price` | `Price` | decimal(12,2) | Sí | `0 ≤ x ≤ 100000`, punto como separador decimal, sin símbolos, máximo 2 decimales (más decimales → `INVALID_FORMAT`, nunca se redondea) |
| `customer_id` | `Customer ID` | string | No | Regex `^\d{5}$` |
| `country` | `Country` | enum | Sí | Valor exacto (sensible a mayúsculas) del catálogo `countries.v1.txt` |

<!-- rev: R-02 -->
**Origen del catálogo de países (evita una definición circular):** `countries.v1.txt` = valores distintos de `Country` en el dataset **completo** (ambas hojas), menos las exclusiones decididas en el perfilado del Sprint 1 (candidatos: `Unspecified`, `European Community`), registradas con su motivo en `docs/perfilado-dataset.md`. El baseline limpio se construye **después**, filtrando filas que pasan el schema v1 con ese catálogo. (La versión anterior decía "valores distintos del baseline", pero el baseline se define como las filas que pasan el schema, que a su vez depende del catálogo.)

> Las cabeceras de origen se confirman contra el archivo real descargado en el Sprint 1 (ver advertencia en `10-datasets.md` §2).

**Reglas de negocio (cross-field):**

| Código de regla | Regla |
|---|---|
| `BR-01` | Si `invoice_no` empieza con `C`, entonces `quantity < 0`. |
| `BR-02` | Si `invoice_no` no empieza con `C`, entonces `quantity > 0`. |

**Tokens nulos** reconocidos (configurables): `""`, `"N/A"`, `"NA"`, `"null"`, `"NULL"`, `"None"`, `"-"`, `"?"`.

- En un campo **requerido**, un token nulo (tras `trim`) produce `MISSING_REQUIRED_FIELD`.
- En un campo **opcional**, la cadena vacía se interpreta como `null` (válido); cualquier otro token se valida como texto normal. Así, `customer_id = "N/A"` falla `PATTERN_MISMATCH` y es candidato a `NULLIFY_TOKEN`. En `description` (sin regex) un token como `"N/A"` se carga tal cual: el MVP no normaliza campos de texto libre.

- **Espacios en blanco:** el validador **no** recorta valores antes de validar (salvo para detectar tokens nulos). Por eso `" 85123A "` produce `PATTERN_MISMATCH` y es candidato a `TRIM`. "Espacio" significa U+0020 y U+0009 (tab); cualquier otro carácter de espacio Unicode (p. ej., NBSP U+00A0) no es recortable automáticamente y lleva a cuarentena. Java y Python deben usar esta misma definición (Java `String.strip()` **no** elimina NBSP; Python `str.strip()` **sí**: no se usan sin argumento en código que deba coincidir entre ambos lados). <!-- rev: R-30 -->
- **Comparaciones de mayúsculas/minúsculas** siempre con `Locale.ROOT` en Java. <!-- rev: R-30 -->

**Campos derivados** en la transformación (no vienen en la fuente): `line_total = quantity × unit_price` (con `unit_price` de 2 decimales y `quantity` entero el resultado es exacto; se fija escala 2 sin redondeo).

## 3. Catálogo de códigos de violación (`errorCode`)

| Código | Nivel | Significado | Ejemplo |
|---|---|---|---|
| `MISSING_REQUIRED_FIELD` | campo | Campo requerido vacío, ausente o token nulo | `Price = ""` |
| `INVALID_TYPE` | campo | No convertible al tipo declarado | `Quantity = "doce"` |
| `INVALID_FORMAT` | campo | Tipo correcto pero formato no canónico | `InvoiceDate = "25/12/2010 08:26"`, `Price = "2,55"` |
| `PATTERN_MISMATCH` | campo | No cumple la regex | `StockCode = " 85123A "` |
| `OUT_OF_RANGE` | campo | Fuera de rango | `Quantity = 999999` |
| `INVALID_ENUM_VALUE` | campo | No pertenece al catálogo | `Country = "UK"` |
| `MAX_LENGTH_EXCEEDED` | campo | Excede longitud | `Description` > 255 |
| `BUSINESS_RULE_VIOLATION` | registro | Viola BR-xx | `C536379` con `Quantity = 5` |
| `MALFORMED_ROW` | registro | Número de columnas distinto al de la cabecera o fila no parseable | fila con 9 columnas |
| `SCHEMA_DRIFT` | archivo | Cabeceras no coinciden con el schema | `UnitPrice` en vez de `Price` |

<!-- rev: R-01 -->
### 3.1 Reglas deterministas de emisión de violaciones

Sin estas reglas, dos implementaciones razonables del validador producen códigos distintos para la misma entrada, y ni el mock, ni el generador, ni la evaluación coinciden.

1. **A lo sumo una violación por campo.** Los chequeos de un campo se ejecutan en este orden y se detienen en el primero que falla: `MISSING_REQUIRED_FIELD` → `INVALID_TYPE` / `INVALID_FORMAT` → `PATTERN_MISMATCH` → `OUT_OF_RANGE` / `INVALID_ENUM_VALUE` → `MAX_LENGTH_EXCEEDED`.
2. Las violaciones a nivel registro (`BUSINESS_RULE_VIOLATION`, `MALFORMED_ROW`) usan `field = "_record"`; puede haber varias `BUSINESS_RULE_VIOLATION` por registro (una por regla). `MALFORMED_ROW` es exclusiva: si la fila está malformada no se evalúa nada más.
3. **`INVALID_TYPE` vs `INVALID_FORMAT`** (campos numéricos y de fecha):

| Campo | `INVALID_FORMAT` si el valor (tras `strip`)… | Si no → `INVALID_TYPE` |
|---|---|---|
| `quantity` | contiene solo dígitos, a lo sumo un signo inicial, `.`/`,` y espacios internos (p. ej., `"6.0"`, `"1 000"`) | `"seis"`, `"6x"`, `"--6"` |
| `unit_price` | contiene solo dígitos, signo, `.`/`,`, espacios y a lo sumo un símbolo `£ $ €` al inicio o final (p. ej., `"2,55"`, `"£2.55"`, `"2.555"`) | `"dos"`, `"2.5.5"`, `"12abc"` |
| `invoice_date` | contiene al menos 6 dígitos y solo dígitos, separadores `/ - . : T` y espacios | `"ayer"`, `"N/D 2010"` |

   Esta clasificación solo decide el `errorCode`; **no** implica que el valor sea corregible (eso lo decide la política + verificador).
4. El validador también emite el `expected` de cada violación con las claves de `06` A.1 (`type`, `format`, `pattern`, `min`, `max`, ...), derivadas del YAML del schema.

## 4. Catálogo de operaciones de corrección (`operation`)

Estas son las **únicas** operaciones que el pipeline puede aplicar automáticamente (allowlist). Cualquier otra operación sugerida por la IA se trata como "requiere revisión humana".

<!-- rev: R-03, R-04, R-05, R-06, R-07 -->
**Principio de diseño de los verificadores:** el verificador **recalcula** el valor corregido a partir de `original` y de los `parameters` que propuso la IA, con código determinista, y exige que el resultado sea idéntico a `proposed`. Además, **rechaza** cuando la entrada admite más de una interpretación razonable, aunque la IA haya elegido una "con alta confianza". La confianza autoinformada **nunca** sustituye a un verificador (ADR-0003, ADR-0008).

Salvo `TRIM`, todos los verificadores operan sobre `s = strip(original)` (definición de espacio de §2).

| Operación | Descripción | Verificador determinista (FR-10) — se rechaza si cualquier condición falla | Campos permitidos |
|---|---|---|---|
| `TRIM` | Quitar espacios al inicio/fin | `proposed == strip(original)`, `proposed` no vacío y `proposed != original` | `invoice_no`, `stock_code`, `country`, `customer_id` |
| `NORMALIZE_CASE` | Ajustar mayúsculas/minúsculas (incluye `strip`) | `proposed.equalsIgnoreCase(s)` (con `Locale.ROOT`) **y** `proposed` pertenece al conjunto válido del campo (catálogo para `country`; regex para `invoice_no`) | `country`, `invoice_no` (**no** `stock_code`: su caja es significativa) |
| `PARSE_DATE` | Reinterpretar fecha con un patrón de origen | (1) `parameters.sourcePattern` es una sintaxis válida de `DateTimeFormatter`, contiene hora y minuto (`H`/`HH` y `mm`) y no contiene zona ni texto localizado (`MMM`, `EEE`, `a`, `z`, `X`); (2) parsear `s` en modo `ResolverStyle.STRICT` produce exactamente `proposed` (si faltan segundos se asume `:00`; si falta la hora → rechazo: no se inventa precisión); (3) **ambigüedad**: si el patrón contiene día y mes numéricos, se prueba también el patrón con día/mes intercambiados; si también parsea y da otra fecha, se rechaza con `AMBIGUOUS_DATE`. Nota de implementación: con `STRICT`, `yyyy` exige era; el verificador reemplaza `y`→`u` o usa `parseDefaulting(ChronoField.ERA, 1)` antes de parsear | `invoice_date` |
| `PARSE_NUMBER` | Reinterpretar número (separador decimal, miles, símbolo de moneda, sufijo `.0`) | (1) Símbolos que se pueden quitar: **solo `£`** (la moneda del schema) y solo en `unit_price`; `$`, `€` u otros → rechazo con `CURRENCY_NOT_CONVERTIBLE` (quitarlos sería una conversión de moneda inventada); en `quantity` y `customer_id` no se quita ningún símbolo; (2) normalizar `s` con `parameters.decimalSeparator` y `parameters.groupingSeparator` da un `BigDecimal` con `compareTo == 0` respecto a `proposed`; (3) **el signo no cambia** y no hay signo nuevo; (4) **ambigüedad de miles**: si `s` contiene exactamente un separador (`.` o `,`) seguido de exactamente 3 dígitos (p. ej., `"2,550"`, `"1.234"`), se rechaza con `AMBIGUOUS_NUMBER`; (5) para `quantity`/`customer_id` la parte fraccionaria debe ser 0 y para `customer_id` el resultado se formatea sin decimales (`"17850"`) | `unit_price`, `quantity`, `customer_id` |
| `MAP_TO_ENUM` | Mapear sinónimo/variante a valor de catálogo | (1) `proposed` pertenece al catálogo; (2) el par `(normalize(s) → proposed)` existe en el archivo versionado `country-aliases.v1.yaml` (códigos ISO 3166 alfa-2/alfa-3, nombres nativos y variantes comunes, curado en el Sprint 1) **o** `normalize(s) == normalize(proposed)` donde `normalize` = minúsculas `Locale.ROOT` + quitar acentos + quitar `[^a-z0-9]`; (3) `confidence ≥ AI_ENUM_MIN_CONFIDENCE`. Un par que no cumple (2) se rechaza con `UNVERIFIABLE_MAPPING` y la sugerencia de la IA queda en cuarentena para revisión humana | `country` |
| `NULLIFY_TOKEN` | Convertir token nulo a `null` | Campo **opcional** y `s` ∈ lista de tokens nulos y `proposed == null` | `customer_id` (en `description` no aplica: la cadena vacía ya es `null` y otros tokens se cargan tal cual, §2) |
| `RENAME_COLUMN` | Mapear cabecera de origen a campo canónico (solo FR-05) | AC-05.4 + AC-05.7 (validación de muestra) | cabeceras |

**Una sola corrección por campo.** El contrato admite exactamente una `correction` por violación y el validador emite a lo sumo una violación por campo (§3.1). Si un campo necesita dos operaciones (p. ej., `" uk "` requiere `TRIM` + `MAP_TO_ENUM`), la operación elegida debe cubrir ambas (`NORMALIZE_CASE`, `PARSE_*` y `MAP_TO_ENUM` ya operan sobre `strip(original)`); si no es posible, el registro va a cuarentena. Esto también garantiza que `UNIQUE (batch_id, source_row_number, field_name)` en `correction_log` nunca descarte silenciosamente una corrección.

**Nunca permitido automáticamente:** rellenar valores faltantes, cambiar el signo o la magnitud de un número, convertir monedas, "adivinar" un valor a partir de otras filas, eliminar filas, truncar texto, modificar `BUSINESS_RULE_VIOLATION`, `MALFORMED_ROW`, `MISSING_REQUIRED_FIELD`, `OUT_OF_RANGE` o `MAX_LENGTH_EXCEEDED`.

## 5. Estados y resultados

- **Resultado por registro (`outcome`)**: `DIRECT`, `AUTO_CORRECTED`, `QUARANTINED`.
- **Estado del lote (`batch_status`)**: `RECEIVED` (registrado, en cola), `RUNNING`, `COMPLETED`, `FAILED`, `REJECTED` (rechazado por schema drift no resoluble o formato inválido; **ninguna fila procesada**).
- **Motivo de falla o rechazo del lote (`failure_reason`)** — valores cerrados: <!-- rev: R-12, R-15 -->

| Valor | Estado | Cuándo |
|---|---|---|
| `INVALID_FILE_FORMAT` | `REJECTED` | Archivo no parseable detectado después de crear el lote (JSON no-array, cabecera CSV duplicada o vacía) |
| `SCHEMA_DRIFT_UNRESOLVED` | `REJECTED` | FR-05 no pudo resolver el mapeo (incluye IA no disponible durante el análisis de drift) |
| `INVARIANT_VIOLATION` | `FAILED` | El chequeo de NFR-06 falló (indica un bug) |
| `INFRASTRUCTURE_ERROR` | `FAILED` | BD inaccesible, archivo ilegible, disco lleno, excepción no controlada fuera del procesador |
| `INTERRUPTED` | `FAILED` | El proceso se detuvo a mitad del job (reinicio del contenedor); detectado por la reconciliación de arranque (AC-04.6) |

- **Correspondencia entre Spring Batch e `ingestion_batch.status`** (la fuente de verdad para la API es `ingestion_batch`): <!-- rev: R-16 -->

| Situación | `BatchStatus` de Spring Batch | `ExitStatus` | `ingestion_batch.status` |
|---|---|---|---|
| Registrado, el job aún no arranca (cola) | — (sin `JobExecution`) | — | `RECEIVED` |
| Job en curso | `STARTED` | `EXECUTING` | `RUNNING` |
| Drift no resuelto / formato inválido | `COMPLETED` (el flujo termina a propósito) | `REJECTED` (custom) | `REJECTED` |
| Terminó y el invariante se cumple | `COMPLETED` | `COMPLETED` | `COMPLETED` |
| Invariante violado | `FAILED` | `FAILED` | `FAILED` (`INVARIANT_VIOLATION`) |
| Excepción de infraestructura | `FAILED` | `FAILED` | `FAILED` (`INFRASTRUCTURE_ERROR`) |
- **Motivo de cuarentena (`quarantine_reason`)**:

| Motivo | Cuándo |
|---|---|
| `AI_REVIEW_REQUIRED` | La IA clasificó al menos una violación como `NEEDS_HUMAN_REVIEW` |
| `POLICY_REJECTED` | La IA propuso auto-corrección pero la política la rechazó (severidad, confianza, operación o campo no permitido) |
| `VERIFICATION_FAILED` | La corrección no pasó el verificador determinista |
| `REVALIDATION_FAILED` | El registro corregido no pasó la revalidación completa |
| `AI_UNAVAILABLE` | Servicio de IA inalcanzable, timeout o circuit breaker abierto |
| `AI_INVALID_RESPONSE` | La respuesta de la IA no cumple el contrato |
| `AI_BUDGET_EXCEEDED` | Se alcanzó el tope de llamadas a IA del lote |
| `PROCESSING_ERROR` | Excepción inesperada procesando el registro (bug); el registro no se pierde |

<!-- rev: R-29 -->
**Regla de asignación del motivo:** el flujo de decisión (`04` §4) es secuencial y **el primer paso que falla determina el motivo**; no se combinan motivos. El orden es: presupuesto → llamada a la IA (`AI_UNAVAILABLE` / `AI_INVALID_RESPONSE`) → decisión de la IA (`AI_REVIEW_REQUIRED`) → política (`POLICY_REJECTED`) → verificadores (`VERIFICATION_FAILED`) → revalidación (`REVALIDATION_FAILED`). `PROCESSING_ERROR` tiene prioridad sobre todos si ocurre una excepción no prevista en cualquier paso.

---

## 6. Requisitos detallados

### FR-01 — Ingesta de archivo CSV por carga (Must)

**Descripción:** El sistema expone `POST /api/v1/ingestions` (multipart) que acepta un archivo CSV (UTF-8, separador `,`, con cabecera, comillas RFC 4180), lo registra como lote y lanza el job ETL de forma asíncrona.

**Criterios de aceptación:**

- **AC-01.1** — *Dado* un CSV válido de ≤ 50 MB, *cuando* se envía a `POST /api/v1/ingestions`, *entonces* la respuesta es `202 Accepted` con `batchId`, `status = RECEIVED|RUNNING` y un header `Location: /api/v1/ingestions/{batchId}`, en menos de 2 segundos (el procesamiento continúa en segundo plano).
- **AC-01.2** — *Dado* un archivo cuya **extensión** no es `.csv` ni `.json` (sin distinguir mayúsculas), *cuando* se envía, *entonces* la respuesta es `415 Unsupported Media Type` en formato `application/problem+json` y no se crea lote. El formato se decide por la extensión; el `Content-Type` de la parte multipart **no** se usa para rechazar (curl y muchos clientes envían `application/octet-stream` para `.csv`). <!-- rev: R-18 -->
- **AC-01.3** — *Dado* un archivo vacío, solo con cabecera, o un JSON `[]`, *cuando* se envía, *entonces* la respuesta es `400` con código `EMPTY_FILE` y no se crea lote.
- **AC-01.4** — *Dado* un archivo > 50 MB (configurable `PIPELINE_MAX_UPLOAD_MB`), *cuando* se envía, *entonces* la respuesta es `413` con código `FILE_TOO_LARGE` en `problem+json`. `spring.servlet.multipart.max-file-size` y `max-request-size` se derivan de `PIPELINE_MAX_UPLOAD_MB` y la `MaxUploadSizeExceededException` se mapea explícitamente (si no, Spring responde con un error genérico).
- **AC-01.5** — *Dado* un CSV con filas malformadas (columnas de más o de menos), *cuando* se procesa, *entonces* el lector **no** aborta el job; cada fila malformada se convierte en una violación `MALFORMED_ROW` y termina en cuarentena. El lector **NO DEBE** rellenar ni truncar columnas para "hacer encajar" la fila (p. ej., `DelimitedLineTokenizer.setStrict(false)` de Spring Batch lo hace en silencio y está prohibido). <!-- rev: R-18 -->
- **AC-01.6** — El archivo recibido se guarda en un volumen como `/data/inbox/{batchId}/source.{csv|json}` para permitir reinicios; nunca se modifica. El nombre original solo se guarda (saneado) en `ingestion_batch.source_filename`; **nunca** se usa para construir rutas (evita *path traversal*). El checksum se calcula en streaming mientras se escribe el archivo (no se carga completo en memoria).
- **AC-01.7** — Normalización de lectura CSV: se elimina un BOM UTF-8 inicial (Excel lo agrega y, si no se elimina, la primera cabecera sería `﻿Invoice` y dispararía un falso *schema drift*); se aceptan finales de línea `\n` y `\r\n`; los campos entre comillas pueden contener comas y saltos de línea. `source_row_number` es el número de **registro lógico** (1-based, sin cabecera), no el número de línea física. Un archivo que no es UTF-8 válido → lote `REJECTED` con `INVALID_FILE_FORMAT`. <!-- rev: R-18 -->
- **AC-01.8** — *Dado* un CSV con cabeceras duplicadas o vacías, *entonces* el lote termina `REJECTED` con `INVALID_FILE_FORMAT` y ninguna fila se procesa. <!-- rev: R-18 -->

### FR-02 — Ingesta de archivo JSON por carga (Should)

**Descripción:** El mismo endpoint acepta un archivo JSON cuyo contenido es un **array de objetos** con las mismas claves que las cabeceras del CSV.

- **AC-02.1** — *Dado* un JSON array de objetos, *cuando* se envía, *entonces* se procesa con el mismo pipeline (mismas validaciones, triage y carga) y `source_type = JSON`.
- **AC-02.2** — *Dado* un JSON que no es un array de objetos (p. ej., objeto raíz o JSON inválido), *cuando* se envía, *entonces* el lote termina `REJECTED` con motivo `INVALID_FILE_FORMAT` (o `400` si se detecta antes de crear el lote).
- **AC-02.3** — *Dado* un objeto con claves faltantes, *cuando* se valida, *entonces* las claves faltantes se tratan como `MISSING_REQUIRED_FIELD` (si son requeridas) y las claves extra se ignoran y se registran en el log a nivel `DEBUG`.
- **AC-02.4** — `source_row_number` = índice del objeto en el array + 1.
- **AC-02.5** — Conversión de valores JSON a texto crudo (el validador siempre recibe strings, igual que con CSV): <!-- rev: R-18 -->
  - `string` → tal cual; `null` → `null`;
  - `number` → su **representación textual exacta en el archivo** (Jackson con `USE_BIG_DECIMAL_FOR_FLOATS` y `BigDecimal.toPlainString()`; nunca `double`, que convertiría `2.55` en `2.5499999…`);
  - `boolean` → `"true"`/`"false"`;
  - objeto o array anidado → el campo recibe `INVALID_TYPE`.
- **AC-02.6** — La lectura es en streaming (`JsonItemReader` de Spring Batch 6 o parser de streaming de Jackson), nunca deserializando el array completo en memoria.

### FR-03 — Ejecución de datasets de muestra empaquetados (Should)

**Descripción:** `POST /api/v1/ingestions/samples/{sampleName}` ejecuta un archivo incluido en la imagen (`/app/samples`), para que un evaluador pruebe el sistema sin preparar archivos.

- **AC-03.1** — *Dado* un `sampleName` existente (listados en `GET /api/v1/ingestions/samples`), *cuando* se invoca, *entonces* se comporta exactamente como FR-01/FR-02 con ese archivo.
- **AC-03.2** — *Dado* un `sampleName` inexistente, *entonces* `404`.
- **AC-03.3** — Muestras mínimas: `clean-1k.csv`, `dirty-1k.csv` (con *ground truth*), `dirty-10k.csv`, `drift-legacy-headers.csv` (cabeceras de *Online Retail* original), `dirty-1k.json` (con su propio `labels`, ver `10` §3), `raw-natural-2k.csv` (suciedad natural, sin *ground truth*).
- **AC-03.4** — Como toda ingesta, una muestra ya procesada devuelve `409 DUPLICATE_INGESTION` (FR-04) con `existingBatchId`. Para repetir la demo se usa `docker compose down -v` o se consulta el lote existente; `scripts/demo.sh` DEBE tratar el `409` reutilizando `existingBatchId` en lugar de fallar. <!-- rev: R-35 -->

### FR-04 — Registro de lote e idempotencia por checksum (Must)

**Descripción:** Cada archivo recibido se identifica por su SHA-256. Un mismo contenido no se procesa dos veces.

- **AC-04.1** — *Dado* un archivo nuevo, *cuando* se recibe, *entonces* se crea una fila en `ingestion_batch` con `source_checksum`, `source_filename`, `source_type`, `status`, timestamps y el `job_execution_id` de Spring Batch.
<!-- rev: R-12 -->
- **AC-04.2** — *Dado* un archivo cuyo checksum ya existe en un lote con estado `RECEIVED`, `RUNNING`, `COMPLETED` o `FAILED`, *cuando* se envía de nuevo, *entonces* la respuesta es `409 Conflict` con código `DUPLICATE_INGESTION`, `existingBatchId` y `existingStatus`; no se crea lote nuevo ni se lanza job.
- **AC-04.3** — *Dado* un checksum de un lote `FAILED`, *cuando* se envía de nuevo, *entonces* `409` con `detail` que sugiere `POST /api/v1/ingestions/{batchId}/restart` (FR-21). Si FR-21 se recortó, esto es una **limitación documentada** en el README (el archivo solo puede reprocesarse con `docker compose down -v`), porque un lote `FAILED` puede tener chunks ya confirmados y reenviarlo como lote nuevo duplicaría datos.
- **AC-04.3b** — *Dado* un checksum cuyo único lote previo está `REJECTED`, *cuando* se envía de nuevo, *entonces* se crea un **lote nuevo** (`202`). Es seguro porque un lote `REJECTED` no procesó filas, y es necesario porque un rechazo puede deberse a una causa transitoria (IA caída durante el análisis de drift). Se implementa con un índice único **parcial** (`WHERE status <> 'REJECTED'`, ver `05`), ADR-0009.
- **AC-04.4** — *Dado* un job que se reinicia tras una falla, *cuando* reprocesa el chunk no confirmado, *entonces* no se generan duplicados (restricciones `UNIQUE (batch_id, source_row_number)` + `ON CONFLICT DO NOTHING`). `ON CONFLICT DO NOTHING` es una **red de seguridad**, no un mecanismo de lógica: la corrección del reinicio depende de que el lector sea un `ItemStream` que guarda y restaura su posición en el `ExecutionContext` (ver `04` §3). Un conflicto real se detecta porque el invariante de NFR-06 falla.
- **AC-04.5** — La detección de duplicados **no** es "consultar y luego insertar" (dos cargas simultáneas del mismo archivo pasarían ambas): se inserta y se traduce la violación de la restricción única (`SQLState 23505`) a `409`.
- **AC-04.6** — *Dado* que `pipeline-service` arranca y existen lotes en `RUNNING` cuya `JobExecution` quedó en `STARTED` (el proceso murió a mitad del job), *entonces* una reconciliación de arranque los marca `FAILED` con `failure_reason = INTERRUPTED` (en Spring Batch 6: `JobOperator.recover(...)`), y los lotes en `RECEIVED` sin `JobExecution` se vuelven a encolar. Sin esto, un lote "zombi" queda en `RUNNING` para siempre y su archivo recibe `409` indefinidamente. (Must: es barato y es condición para que FR-21 funcione.)

### FR-05 — Detección de *schema drift* y mapeo asistido por IA (Should)

**Descripción:** Antes de procesar filas, el job compara las cabeceras recibidas con las esperadas. Si difieren, intenta resolver el mapeo primero de forma determinista y luego con IA.

- **AC-05.1** — *Dado* cabeceras idénticas a las esperadas, *entonces* no se llama a la IA y `schema_mapping` del lote queda como identidad.
- **AC-05.2** — *Dado* cabeceras que difieren solo por mayúsculas, espacios, guiones o guiones bajos (normalización `lowercase(Locale.ROOT) + quitar [^a-z0-9]`), *entonces* se mapean deterministamente **sin** IA. Ejemplo: `CustomerID` y `Customer ID` normalizan ambos a `customerid` → mapeo determinista; `InvoiceNo` (`invoiceno`) y `UnitPrice` (`unitprice`) no coinciden con `invoice`/`price` → requieren IA. <!-- rev: R-19 -->
- **AC-05.3** — *Dado* cabeceras no resueltas tras AC-05.2, *cuando* se invoca `POST /v1/schema-drift/analyze` con las cabeceras recibidas, el schema esperado y hasta 5 filas de muestra, *entonces* la IA devuelve un mapeo propuesto con confianza por columna.
- **AC-05.4** — *Dado* un mapeo propuesto, *entonces* se aplica solo si: (a) todos los campos requeridos quedan mapeados, (b) el mapeo es 1:1, (c) cada mapeo tiene `confidence ≥ AI_SCHEMA_MIN_CONFIDENCE` (default 0.90), (d) ningún campo canónico recibe dos cabeceras. Las cabeceras extra se ignoran y se registran.
- **AC-05.5** — *Dado* un mapeo que no cumple AC-05.4 o AC-05.7 (o IA no disponible), *entonces* el lote termina `REJECTED` con `failure_reason = SCHEMA_DRIFT_UNRESOLVED` y `schema_drift_report` (JSON con cabeceras, mapeo propuesto, motivo y explicación de la IA) y **ninguna** fila se procesa. Reenviar el mismo archivo crea un lote nuevo (AC-04.3b). Nota: esto **no** contradice NFR-03 — un lote `REJECTED` no es un job `FAILED` y no se carga ningún dato dudoso.
- **AC-05.6** — *Dado* el archivo `drift-legacy-headers.csv` (`InvoiceNo`, `UnitPrice`, `CustomerID`), *entonces* `CustomerID` se resuelve deterministamente (AC-05.2), solo `InvoiceNo` y `UnitPrice` se envían a la IA, el lote se procesa con mapeo aplicado y el mapeo queda en `ingestion_batch.schema_mapping` con el origen **por cabecera** (`IDENTITY`, `NORMALIZED` o `AI`). <!-- rev: R-19 -->
- **AC-05.7** — **Verificador del mapeo (anti-alucinación):** antes de aceptar un mapeo que incluye al menos una cabecera resuelta por IA, se validan con el schema v1 las primeras `AI_SCHEMA_SAMPLE_ROWS` filas (default 100) usando el mapeo propuesto. Si más del `AI_SCHEMA_MAX_INVALID_RATE` (default 0.20) de esas filas tienen violaciones **en campos mapeados por IA**, el mapeo se rechaza (`REJECTED`). Esto detecta mapeos plausibles pero erróneos (p. ej., `UnitPrice → quantity`) que las condiciones de AC-05.4 no ven. <!-- rev: R-19 -->
- **AC-05.8** — Una cabecera **opcional** del schema que no aparece en el archivo no bloquea el mapeo: el campo se trata como `null` en todas las filas. Una cabecera **requerida** ausente (y sin mapeo) → `REJECTED`. <!-- rev: R-19 -->

### FR-06 — Validación contra schema declarativo versionado (Must)

**Descripción:** Cada registro se valida contra `sales_transaction.v1.yaml` (tipos, requeridos, regex, rangos, enum, longitud) y contra las reglas BR-xx.

- **AC-06.1** — *Dado* un registro que cumple todas las reglas, *entonces* se marca `DIRECT` y pasa a transformación sin llamar a la IA.
- **AC-06.2** — *Dado* un registro con varias violaciones, *entonces* se reportan **todas** (no solo la primera), cada una con su `errorCode` del catálogo §3.
- **AC-06.3** — Las reglas de negocio (BR-xx) se evalúan solo si todos los campos involucrados son válidos a nivel campo.
- **AC-06.4** — El validador es puro (sin I/O) y reutilizable: el mismo componente se usa para la validación inicial y para la revalidación (FR-10).
- **AC-06.5** — El schema se carga al iniciar la aplicación; si el YAML es inválido, la aplicación no arranca (fail-fast).
- **AC-06.6** — El validador cumple las reglas de emisión de §3.1 (una violación por campo, orden de chequeos, `INVALID_TYPE` vs `INVALID_FORMAT`). Hay un test parametrizado por cada fila de la tabla de §3.1 y por cada `defect_type` de `10` §3 que verifica el `errorCode` esperado. <!-- rev: R-01 -->
- **AC-06.7** — El validador recibe el `Clock` por constructor; ningún componente de validación llama a `LocalDateTime.now()` directamente. <!-- rev: R-30 -->

### FR-07 — Captura del contexto del error (Must)

**Descripción:** Por cada registro inválido se construye un objeto de contexto que viaja a la IA y se persiste.

- **AC-07.1** — *Dado* un registro inválido, *entonces* se captura: `batchId`, `sourceRowNumber`, `sourceFilename`, registro crudo completo (mapa cabecera→valor, redactando campos configurados), y por cada violación: `violationId`, `field`, `errorCode`, `receivedValue`, `expected` (tipo, formato, regex, rango o valores permitidos), `technicalMessage` y `exceptionType` (si hubo excepción de parseo).
- **AC-07.2** — El `technicalMessage` contiene el mensaje original de la excepción (p. ej., `DateTimeParseException`) truncado a 1000 caracteres; no se envían stacktraces completos a la IA.
- **AC-07.3** — Para `INVALID_ENUM_VALUE`, `expected.allowedValues` incluye el catálogo completo (≤ 100 valores).
- **AC-07.4** — Para `MALFORMED_ROW`, `record.raw` es `{ "_rawLine": "..." }` truncado a 2 000 caracteres. La redacción de NFR-15 **no puede aplicarse** a una línea que no se pudo separar en columnas; por eso, si `AI_SEND_FULL_RECORD=false`, `_rawLine` no se envía y solo se envía `expected` (columnas esperadas vs recibidas). Esta limitación se documenta en el README. <!-- rev: R-23 -->

### FR-08 — Triage con IA de registros inválidos (Must)

**Descripción:** El pipeline envía el contexto de cada registro inválido a `POST /v1/triage` del servicio de IA y recibe un análisis estructurado.

- **AC-08.1** — *Dado* un registro inválido, *cuando* se llama a `/v1/triage`, *entonces* la respuesta incluye por cada violación: `explanation` (español o inglés según `AI_EXPLANATION_LANGUAGE`, default `es`), `severity` (`LOW|MEDIUM|HIGH|CRITICAL`), `classification` (`AUTO_FIXABLE|NEEDS_HUMAN_REVIEW`), `confidence` (0–1), `correction` (si `AUTO_FIXABLE`) y `suggestedHumanAction` (si `NEEDS_HUMAN_REVIEW`); y a nivel registro: `recordDecision`, `maxSeverity` y `summary`.
- **AC-08.2** — La respuesta incluye `meta` con `provider`, `model`, `promptVersion`, `latencyMs`, `cacheHit`, `llmCalls` y tokens (si el proveedor los reporta).
- **AC-08.3** — *Dado* un error `MISSING_REQUIRED_FIELD`, `OUT_OF_RANGE`, `MAX_LENGTH_EXCEEDED`, `BUSINESS_RULE_VIOLATION` o `MALFORMED_ROW`, *entonces* el servicio de IA **debe** clasificarlo `NEEDS_HUMAN_REVIEW` y aplicar el piso de severidad de `06` A.1 (regla reforzada en el prompt **y** en un post-procesador del servicio de IA).
- **AC-08.4** — Caché (ver NFR-11 para la clave exacta). <!-- rev: R-09 -->
  - (a) *Dado* dos solicitudes cuyas violaciones **a nivel campo** tienen la misma firma (`field`, `errorCode`, `receivedValue`, hash canónico de `expected`, idioma, proveedor, modelo, versión de prompt), *entonces* la segunda se responde desde caché sin llamar al LLM.
  - (b) Las violaciones con `field = "_record"` (`BUSINESS_RULE_VIOLATION`, `MALFORMED_ROW`) **nunca** se cachean: su explicación depende de toda la fila.
  - (c) Un análisis cacheado se reutiliza en filas distintas, por eso el prompt exige que `explanation` y `suggestedHumanAction` de violaciones a nivel campo **no** mencionen valores de otras columnas (factura, producto, cliente). Hay un test que lo verifica sobre las respuestas del mock y una regla en `rules.py` que descarta del caché cualquier análisis cuyo texto contenga valores de otras columnas del `record.raw`.
  - (d) Si **todas** las violaciones de la solicitud están en caché, `summary` se construye con una plantilla determinista a partir de los análisis y `meta.cacheHit = true`. Si solo algunas lo están, se llama al LLM por la solicitud completa y `meta.cacheHit = false`.
- **AC-08.5** — Cada **llamada lógica** del pipeline a la IA (éxito o falla; los reintentos de la misma llamada se agrupan con el mismo `requestId` y se cuentan en `attempts`) se registra como **una fila** en `ai_call_log`. Las llamadas que no se hacen porque el presupuesto se agotó no generan fila. <!-- rev: R-38 -->
- **AC-08.6** — Presupuesto por lote. *Dado* `AI_MAX_CALLS_PER_BATCH` alcanzado (default 500), *entonces* los registros inválidos restantes van a cuarentena con `AI_BUDGET_EXCEEDED` sin llamar a la IA. Definición exacta: **cuenta toda llamada lógica cuya respuesta no sea `meta.cacheHit = true`**, incluidas las fallidas (un timeout pudo consumir tokens). El contador se inicializa desde `ai_call_log` al (re)iniciar el job, de modo que un reinicio no regala un presupuesto nuevo. Como el pipeline no sabe de antemano si habrá acierto de caché, una vez agotado el presupuesto no se llama ni siquiera para filas que habrían sido acierto (comportamiento aceptado y documentado). <!-- rev: R-10 -->
- **AC-08.7** — **Validación de la respuesta en el pipeline (fallo cerrado).** Además de deserializar, el pipeline verifica: `requestId` igual al enviado; el conjunto de `analyses[].violationId` es exactamente el enviado, sin repetidos; `analyses[].field` coincide con el `field` de su violación; `correction.originalValue == receivedValue`; `correction` no nula ⇔ `AUTO_FIXABLE`; `recordDecision` y `maxSeverity` coherentes con `analyses` (el pipeline los **recalcula** y usa su propio cálculo, nunca el recibido). Cualquier discrepancia → `AI_INVALID_RESPONSE`. <!-- rev: R-08 -->
- **AC-08.8** — Tope global de costo en `ai-service`: *dado* `LLM_MAX_CALLS_PER_DAY` alcanzado (default 2 000; contador en memoria que se reinicia a medianoche UTC o al reiniciar el proceso), *entonces* `/v1/triage` y `/v1/schema-drift/analyze` responden `429 LLM_BUDGET_EXHAUSTED` sin llamar al LLM (los aciertos de caché siguen respondiendo) y el pipeline envía el registro a cuarentena con `AI_BUDGET_EXCEEDED`. Con `LLM_PROVIDER=mock` el tope no aplica. <!-- rev: R-10 -->

### FR-09 — Política de corrección determinista (Must)

**Descripción:** Un componente de Spring Boot (`CorrectionPolicy`) decide si las correcciones propuestas se aplican. Es la única autoridad para escribir datos corregidos.

- **AC-09.1** — Un registro se auto-corrige **solo si todas** sus violaciones cumplen: `classification = AUTO_FIXABLE`, `severity = LOW`, `confidence ≥ AI_MIN_CONFIDENCE` (default 0.85; `AI_ENUM_MIN_CONFIDENCE` para `MAP_TO_ENUM`), `operation` en la allowlist §4, el campo está permitido para esa operación y el `errorCode` no es `MISSING_REQUIRED_FIELD`, `OUT_OF_RANGE`, `MAX_LENGTH_EXCEEDED`, `BUSINESS_RULE_VIOLATION` ni `MALFORMED_ROW`. La política evalúa `analyses` directamente; **no** confía en `recordDecision` (AC-08.7).
- **AC-09.2** — *Dado* que una sola violación no cumple, *entonces* **ninguna** corrección del registro se aplica (todo o nada) y el registro va a cuarentena con `POLICY_REJECTED` (o `AI_REVIEW_REQUIRED` si la IA ya lo pidió).
- **AC-09.3** — La política registra el motivo concreto del rechazo por violación (p. ej., `CONFIDENCE_BELOW_THRESHOLD`, `OPERATION_NOT_ALLOWED`, `FIELD_NOT_ALLOWED`, `SEVERITY_TOO_HIGH`, `ERROR_CODE_NOT_CORRECTABLE`) en `quarantine_record.policy_decision`.
- **AC-09.4** — Los umbrales y la allowlist son configurables por propiedades (para experimentar), pero: la política **no** puede ampliarse con operaciones o campos fuera del catálogo §4 sin ADR; los **valores por defecto** solo cambian con ADR; y la aplicación **no arranca** si un umbral configurado es `< 0.5` o `> 1`, o si la allowlist configurada contiene una operación o un campo no definidos en §4 (fail-fast). <!-- rev: R-33 -->
- **AC-09.5** — La política tiene tests unitarios parametrizados que cubren cada operación, cada código de error y cada motivo de rechazo.

### FR-10 — Aplicación de corrección, verificación, revalidación y registro (Must)

- **AC-10.1** — *Dado* un registro aprobado por la política, *cuando* se aplica cada corrección, *entonces* se ejecuta el verificador determinista de la operación (§4); si alguno falla, el registro va a cuarentena con `VERIFICATION_FAILED` y ninguna corrección se aplica.
- **AC-10.2** — *Dado* un registro con todas las correcciones verificadas, *entonces* se revalida **completo** con el mismo validador de FR-06; si falla, va a cuarentena con `REVALIDATION_FAILED`.
- **AC-10.3** — *Dado* un registro revalidado, *entonces* se carga con `load_origin = AUTO_CORRECTED` y se inserta una fila en `correction_log` por cada campo corregido (valor original, valor corregido, operación, parámetros, confianza, severidad, explicación, proveedor, modelo, versión de prompt).
- **AC-10.4** — Las inserciones en tabla limpia y `correction_log` ocurren en la misma transacción del chunk.
- **AC-10.5** — El resultado de cada verificador (aprobado / código de rechazo: `AMBIGUOUS_DATE`, `AMBIGUOUS_NUMBER`, `CURRENCY_NOT_CONVERTIBLE`, `UNVERIFIABLE_MAPPING`, `VALUE_MISMATCH`, `SIGN_CHANGED`, `INVALID_PARAMETERS`) se guarda por violación en `quarantine_record.policy_decision.violations[].verification`. <!-- rev: R-29 -->
- **AC-10.6** — **Suite adversarial:** existe una batería de tests (solo en tests; no es un proveedor de runtime) que alimenta a política + verificadores con respuestas de IA **plausibles pero incorrectas**, todas con `severity = LOW` y `confidence = 0.99`: día/mes invertidos en fecha ambigua, `"2,550"` → `2.55`, `"€2.55"` → `2.55`, `"-2.55"` → `2.55`, `"Austria"` → `Australia`, `"17850.5"` → `17850`, `TRIM` que además cambia un carácter, `NORMALIZE_CASE` sobre `stock_code`, `proposedValue` con formato no canónico, `originalValue` distinto de `receivedValue`. Criterio: **0** de estos casos termina en `sales_transaction`. Este test es la evidencia principal de OBJ-2 y la respuesta a "¿qué pasa si el LLM se equivoca con confianza?". <!-- rev: R-25 -->

### FR-11 — Cuarentena con análisis de IA adjunto (Must)

- **AC-11.1** — *Dado* un registro que no se carga, *entonces* se inserta en `quarantine_record` con: registro crudo (`raw_payload`), violaciones, análisis completo de la IA (`ai_analysis`, o `null` si no hubo), `summary` legible, `max_severity`, `quarantine_reason`, `policy_decision`, `status = PENDING_REVIEW`.
- **AC-11.2** — *Dado* un registro en cuarentena por `AI_UNAVAILABLE`, `AI_INVALID_RESPONSE`, `AI_BUDGET_EXCEEDED` o `PROCESSING_ERROR`, *entonces* `summary` contiene un texto técnico generado por el pipeline (sin IA) que describe las violaciones.
- **AC-11.3** — Ningún registro se descarta sin quedar en cuarentena o en la tabla limpia (invariante de conteo, ver NFR-06).

### FR-12 — Degradación segura ante falla del servicio de IA (Must)

- **AC-12.1** — *Dado* el servicio de IA detenido, *cuando* se procesa un lote con registros inválidos, *entonces* el job termina `COMPLETED`, los válidos se cargan y los inválidos van a cuarentena con `AI_UNAVAILABLE`.
- **AC-12.2** — *Dado* timeouts o errores 5xx repetidos, *entonces* el circuit breaker se abre y las llamadas siguientes fallan rápido (< 50 ms) sin esperar el timeout.
- **AC-12.3** — *Dado* una respuesta que no cumple el contrato (JSON inválido, enum desconocido, campos faltantes), *entonces* el registro va a cuarentena con `AI_INVALID_RESPONSE` y la respuesta cruda (truncada a 4 KB) se guarda en `ai_call_log.error_detail`.
- **AC-12.4** — En ningún caso una falla de la IA provoca que un registro inválido se cargue en la tabla limpia.
- **AC-12.5** — *Dado* un `ai-service` que acepta conexiones pero **no responde** (escenario más dañino que "caído": cada llamada espera el timeout completo), *cuando* se procesa `dirty-1k`, *entonces* el job termina `COMPLETED` dentro de la cota de tiempo calculada en NFR-07 y todos los inválidos quedan con `AI_UNAVAILABLE`. Se prueba con WireMock y un retardo mayor que `AI_CLIENT_READ_TIMEOUT_MS` (con timeouts reducidos en el perfil de test). <!-- rev: R-11 -->

### FR-13 — Transformación y carga de datos limpios (Must)

- **AC-13.1** — *Dado* un registro válido (directo o corregido), *entonces* se transforma al modelo canónico (tipos nativos, `invoice_date` como `timestamp`, `unit_price` como `numeric(12,2)`, `customer_id` `null` si vacío, `is_cancellation = invoice_no.startsWith("C")`, `line_total` calculado) y se inserta en `sales_transaction`.
- **AC-13.2** — La carga se hace por chunks (default 100) con escritura por lotes JDBC.
- **AC-13.3** — Solo datos que pasaron la validación (inicial o revalidación) llegan a `sales_transaction` (principio ETL, ADR-0001).
- **AC-13.4** — Defensa en profundidad: `sales_transaction` tiene `CHECK` en la BD que replican las reglas del schema v1 que son expresables en SQL (rangos, `quantity <> 0`, regex de `invoice_no`, BR-01/BR-02, coherencia de `is_cancellation` y `line_total`; ver `05`). Si un bug deja pasar un registro inválido, el chunk falla ruidosamente en lugar de contaminar la tabla. <!-- rev: R-15 -->

### FR-14 — Consulta de estado de lotes (Must)

- **AC-14.1** — `GET /api/v1/ingestions/{batchId}` devuelve estado, timestamps, archivo, checksum, tipo, contadores (`read`, `direct`, `autoCorrected`, `quarantined`), `schemaMapping` y, si aplica, `failureReason` o `schemaDriftReport`.
- **AC-14.2** — Mientras el lote está `RUNNING`, `direct`, `autoCorrected` y `quarantined` se calculan con `COUNT(*)` sobre las tablas (reflejan exactamente los chunks confirmados) y `read` es el total de registros del archivo contado en `intakeStep` (NFR-06), de modo que el cliente ve progreso como `(direct + autoCorrected + quarantined) / read`. Al terminar, los valores se persisten en `ingestion_batch`. <!-- rev: R-13 -->
- **AC-14.3** — `GET /api/v1/ingestions?status=&page=&size=` lista lotes paginados, ordenados por fecha descendente.
- **AC-14.4** — `batchId` inexistente → `404` `application/problem+json`.

### FR-15 — Endpoint de métricas (Must)

- **AC-15.1** — `GET /api/v1/metrics/summary` devuelve totales globales: lotes por estado; registros leídos, directos, auto-corregidos, en cuarentena y sus tasas; métricas de IA (llamadas, aciertos de caché, fallas, latencia promedio y p95); cuarentena por severidad y por motivo; correcciones por operación y por campo.
- **AC-15.2** — Acepta filtros opcionales `batchId`, `from`, `to` (ISO-8601). `from`/`to` filtran **lotes** por `ingestion_batch.received_at` (intervalo `[from, to)`); todas las métricas de registros, IA y correcciones se agregan sobre los lotes seleccionados. Nunca se filtra por `loaded_at`/`created_at` de cada tabla (partiría un lote en dos y rompería AC-15.3). <!-- rev: R-22 -->
- **AC-15.3** — *Dado* cualquier alcance, *entonces* `read = loadedDirect + autoCorrected + quarantined` (para lotes `COMPLETED`).
- **AC-15.6** — `quarantineBySeverity` incluye la clave `UNCLASSIFIED` para registros con `max_severity = null` (cuarentena sin análisis de IA), de modo que la suma por severidad = `quarantined`. Lo mismo aplica a `quarantineByReason`. <!-- rev: R-20 -->
- **AC-15.4** — Los números se calculan desde las tablas (fuente de verdad), no desde contadores en memoria.
- **AC-15.5** — Respuesta en < 1 s con 100 000 registros en la base.

### FR-16 — Consulta de cuarentena y correcciones (Should)

- **AC-16.1** — `GET /api/v1/quarantine?batchId=&severity=&reason=&status=&page=&size=` lista registros en cuarentena (resumen).
- **AC-16.2** — `GET /api/v1/quarantine/{id}` devuelve el detalle completo, incluido el análisis de IA.
- **AC-16.3** — `GET /api/v1/corrections?batchId=&field=&operation=&page=&size=` lista correcciones automáticas.
- **Nota de prioridad:** AC-16.2 y AC-16.3 los usa la demo (`08` §3.5 paso 6) y el README; no están en la línea de recorte de `07` §4. <!-- rev: R-31 -->

### FR-17 — Proveedor LLM configurable (Must)

- **AC-17.1** — `LLM_PROVIDER` acepta `mock` (default), `openai_compatible` y `anthropic`. Cambiar de proveedor no requiere cambios de código ni en Spring Boot.
- **AC-17.2** — *Dado* `LLM_PROVIDER=mock`, *entonces* el sistema completo funciona sin API key ni red externa, con respuestas deterministas que cubren todo el catálogo de defectos de `10-datasets.md`. <!-- rev: R-26 -->
  - El mock implementa `LLMProvider` y devuelve un `dict` que pasa por **el mismo camino** que un proveedor real (validación Pydantic, `rules.py`, caché, `meta`); no hay atajos que salten esos pasos.
  - El mock decide **solo** a partir del contenido de la solicitud (valores, `errorCode`, `expected`). **NO DEBE** leer `labels.csv`, archivos de `data/` ni conocer el `defect_type`: si lo hiciera, la evaluación con mock sería circular.
  - El mock produce `confidence` y `severity` según una tabla fija documentada en `ai-service/app/providers/mock.py`.
- **AC-17.3** — *Dado* un proveedor real sin `LLM_API_KEY` (cuando la requiere), *entonces* el servicio de IA arranca pero `/health` reporta `degraded` y `/v1/triage` responde `503 PROVIDER_NOT_CONFIGURED`.
- **AC-17.4** — `openai_compatible` acepta `LLM_BASE_URL`, lo que permite usar OpenAI, Ollama, Groq, OpenRouter, LM Studio u otros compatibles.
- **AC-17.5** — `GET /v1/info` devuelve proveedor, modelo y versión de prompt activos (nunca la API key).

### FR-18 — Documentación OpenAPI (Must)

- **AC-18.1** — Pipeline: Swagger UI en `http://localhost:8080/swagger-ui.html`, spec en `/v3/api-docs` (springdoc-openapi).
- **AC-18.2** — IA: Swagger UI en `http://localhost:8000/docs`, spec en `/openapi.json`.
- **AC-18.3** — Todos los endpoints tienen descripción, ejemplos de request/response y respuestas de error documentadas.
- **AC-18.4** — La spec del servicio de IA se exporta a `contracts/ai-service.openapi.json` y un test de CI falla si difiere de la generada.

### FR-19 — Generador de datos sucios con *ground truth* (Must)

- **AC-19.1** — Un script (`tools/dirty_data_generator`) toma un baseline limpio y, con semilla fija, inyecta defectos del catálogo de `10-datasets.md` en una proporción configurable (default 5 % de filas).
- **AC-19.2** — Produce `*.dirty.csv` (y opcionalmente `.json`) y `*.labels.csv` con: `source_row_number`, `field`, `defect_type`, `clean_value`, `dirty_value`, `expected_error_code`, `expected_outcome` (`AUTO_CORRECTED|QUARANTINED`), `expected_operation` (formato exacto en `10` §3). <!-- rev: R-27 -->
- **AC-19.3** — Con la misma semilla y parámetros, la salida es idéntica byte a byte (fijar también versiones de librerías, orden de iteración y finales de línea `\n`).
- **AC-19.4** — Garantías del generador: <!-- rev: R-27 -->
  - a lo sumo **un defecto por campo** en cada fila (las combinaciones usan campos distintos);
  - cada defecto se aplica solo a filas donde tiene sentido (p. ej., `DATE_DMY_UNAMBIGUOUS` solo si el día > 12; `DATE_AMBIGUOUS` solo si día ≤ 12 **y** día ≠ mes; `DATE_ISO_T` solo si día > 12 **o** día = mes (si no, el patrón con día/mes intercambiados también parsea y el verificador de `PARSE_DATE` lo rechaza con `AMBIGUOUS_DATE`); `FLOAT_ID` solo si `customer_id` no es nulo; `NULL_TOKEN_OPTIONAL` solo si `customer_id` es nulo (así `clean_value` es la cadena vacía y coincide con la corrección `null`, AC-20.3); `COUNTRY_SYNONYM` solo con pares presentes en `country-aliases.v1.yaml`);
  - los espacios inyectados son solo U+0020 y tab (§2);
  - un test de integración en `pipeline-service` ejecuta el validador sobre cada fila etiquetada y verifica que produce el `expected_error_code` (así se detecta si un defecto inyectado accidentalmente produce un valor válido o un código distinto).

### FR-20 — Evaluación de calidad del triage (Should)

- **AC-20.1** — Un script (`scripts/evaluate.py` o endpoint de solo lectura) cruza `labels.csv` con `sales_transaction`, `quarantine_record` y `correction_log` de un lote y calcula: exactitud de auto-corrección (valor corregido == `clean_value`), tasa de auto-corrección incorrecta, *recall* de auto-corrección (auto-corregibles efectivamente corregidos), tasa de cuarentena de defectos no corregibles, falsos positivos (filas limpias que no terminaron `DIRECT`).
- **AC-20.2** — Genera un reporte Markdown en `reports/eval-{fecha}-{proveedor}-{modelo}.md` que se enlaza desde el README.
- **AC-20.3** — **(Must, Sprint 4)** Con proveedor `mock` sobre `dirty-1k`: cada fila termina exactamente con su `expected_outcome` (filas no etiquetadas → `DIRECT`), cada corrección coincide con `clean_value`, 0 auto-correcciones incorrectas y 0 defectos no corregibles en la tabla limpia (test de CI). Puede implementarse como test de integración en Java antes de que exista `scripts/evaluate.py`. <!-- rev: R-26 -->
- **AC-20.4** — El reporte con LLM real incluye: proveedor, modelo, versión de prompt, fecha, número de llamadas y tokens, costo estimado (fórmula de NFR-11), y la matriz *defect_type × outcome real*. Los números no se editan a mano (ver `09` §2.3).

### FR-21 — Reinicio de lote fallido (Could)

- **AC-21.1** — `POST /api/v1/ingestions/{batchId}/restart` reinicia la ejecución `FAILED` usando el mecanismo de reinicio de Spring Batch desde el último chunk confirmado.
- **AC-21.2** — Tras el reinicio, se cumplen el invariante de conteo y la ausencia de duplicados (AC-04.4).
- **AC-21.3** — Reiniciar un lote no `FAILED` → `409`.

### FR-22 — Marcar resolución de cuarentena (Could)

- **AC-22.1** — `PATCH /api/v1/quarantine/{id}` con `{"status": "RESOLVED" | "DISCARDED", "note": "..."}` actualiza estado, nota y `resolved_at`.
- **AC-22.2** — No reprocesa ni mueve el registro a la tabla limpia (eso es FR-23, fuera de alcance).

### FR-23 — Reprocesamiento automático de la cuarentena (Won't)

Explícitamente fuera del MVP. Documentado para que ningún colaborador lo implemente sin ADR.
