# Revisión técnica de la documentación — PipeMend

- **Fecha:** 2026-09-27
- **Alcance:** auditoría de `/docs`, `AGENTS.md` y ADR-0001…0007 antes del Sprint 1.
- **Restricciones respetadas:** no se cambió el stack, el alcance del MVP ni el patrón ETL. No se reescribió ningún documento desde cero: se corrigió y amplió sobre la estructura existente (la única sección reescrita en bloque es el DDL de `05` §4, y `AGENTS.md`, que se reescribió para reflejar `09`).

## 1. Veredicto

La documentación original está **por encima del promedio** de un proyecto de portafolio: requisitos con IDs estables, criterios Given/When/Then, ADRs, un principio rector claro ("la IA propone, el pipeline dispone") y una sección explícita sobre el riesgo de Spring Batch 5 vs 6. Los problemas no son de estructura sino de **precisión**: varios puntos admitían dos implementaciones razonables incompatibles, algunos ejemplos contradecían las reglas, y la defensa contra correcciones incorrectas del LLM tenía huecos concretos justo donde un evaluador técnico va a mirar.

### Los 10 hallazgos más importantes

| # | Hallazgo | Gravedad | Cambio |
|---|---|---|---|
| 1 | Varios verificadores solo comprobaban coherencia con los parámetros que eligió la propia IA: `"2,550"` → `2.55` (×1000), `"€2.55"` → `2.55 GBP` y `"Austria"` → `"Australia"` pasaban la verificación | **Crítica** (contamina datos) | R-04, R-06, ADR-0008 |
| 2 | La caché por violación reutilizaba explicaciones que mencionaban la factura y el producto de **otra** fila; la clave estaba definida de dos formas distintas y no incluía idioma ni proveedor | Alta | R-09 |
| 3 | Reintentos en dos capas: hasta ~9 llamadas al LLM por registro, y `ai-service` podía tardar más que el timeout del pipeline; la tabla de reintentos contradecía la de errores | Alta (costo y tiempo) | R-11 |
| 4 | `read_count` no estaba definido de forma independiente: o el invariante era una tautología, o fallaba en falso tras un reinicio | Alta | R-13 |
| 5 | Un lote `REJECTED` por IA caída o un lote "zombi" tras reiniciar el contenedor quedaban bloqueados con `409` para siempre | Alta | R-12, ADR-0009 |
| 6 | En Spring Boot 4, el starter de Batch por defecto guarda la metadata **en memoria**: reinicio e idempotencia fallarían sin ningún error visible | Alta | R-16 |
| 7 | Con PostgreSQL 18, el volumen del `docker-compose.yml` de referencia (`/var/lib/postgresql/data`) hace fallar el arranque | Alta (rompe "un comando") | R-17 |
| 8 | El Sprint 2 dejaba los inválidos "solo en log": el `finalizeStep` marcaría `FAILED` cualquier archivo con una fila inválida | Media | R-31 |
| 9 | El plan suma 72 h con 0 % de buffer real (decía 15 %); la estimación realista es 82–102 h frente a 64–80 h disponibles | Media (expectativas) | R-31 |
| 10 | Las reglas para agentes decían *qué* no cambiar, pero no *qué archivos*, *qué cuenta como aprobación*, ni prohibían manipular tests, labels o reportes de evaluación | Media | R-33, R-37 |

## 2. Cómo están marcados los cambios

- Cada cambio tiene un ID `R-xx` y un comentario HTML invisible `<!-- rev: R-xx -->` junto al texto modificado (se ve en el código fuente y en el diff, no en la vista renderizada).
- Donde un texto anterior era erróneo, se dejó una frase breve "la versión anterior decía…" para que el motivo quede junto a la regla y un agente no "restaure" el texto viejo.
- **ADR-0008 y ADR-0009 están en estado `Proposed`.** Los documentos ya los incorporan; el owner debe aceptarlos o rechazarlos antes del Sprint 1 (ver §5).

## 3. Registro de cambios

### 3.1 Ambigüedades, contradicciones y vacíos en requisitos y criterios de aceptación (punto 1)

| ID | Dónde | Qué cambió | Por qué |
|---|---|---|---|
| R-01 | `02` §3.1 (nuevo), AC-06.6 | Una violación por campo, orden fijo de chequeos, regla determinista `INVALID_TYPE` vs `INVALID_FORMAT` | `"6.0"` en `quantity` podía ser cualquiera de los dos códigos; el mock, el generador y la evaluación dependen de que el código sea único. Además, con dos violaciones en el mismo campo, `ON CONFLICT DO NOTHING` descartaba en silencio la segunda corrección |
| R-02 | `02` §2, `10` §2 | El catálogo de países sale del dataset completo menos exclusiones; el baseline se construye después | Definición circular: el catálogo eran "valores del baseline" y el baseline, "filas que pasan el schema" (que usa el catálogo) |
| R-12 | FR-04 (AC-04.2…04.6), `02` §5 | 409 para todos los estados salvo `REJECTED`; inserción + captura de `23505` en vez de consultar-e-insertar; reconciliación de lotes zombi (Must) | AC-04.2 solo cubría `COMPLETED`/`RUNNING`; condición de carrera en cargas simultáneas; estados sin salida |
| R-18 | FR-01 (AC-01.2, 01.4–01.8), FR-02 (AC-02.5, 02.6) | Formato por extensión (no por `Content-Type`), BOM, CRLF, registro lógico, cabeceras duplicadas, ruta del inbox sin nombre original, números JSON como `BigDecimal` textual | `curl` envía `application/octet-stream` para `.csv` (AC-01.2 original rechazaría la demo); el BOM de Excel dispara un falso *schema drift*; `double` convierte `2.55` en `2.5499…`; *path traversal* |
| R-19 | FR-05 (AC-05.2, 05.5–05.8), `05` §5, `06` A.2 | `CustomerID` se resuelve sin IA; origen por cabecera; verificador por muestra de filas; columnas opcionales ausentes | El ejemplo y AC-05.6 contradecían AC-05.2 (`customerid` normaliza igual). Un mapeo plausible pero erróneo (`UnitPrice → quantity`) cumplía todas las condiciones de AC-05.4 |
| R-26 | `01` OBJ-2/OBJ-3, FR-17 (AC-17.2), FR-20 (AC-20.3 → Must), `08` §3.2 | Con mock, el gate es 100 % de coincidencia con `labels.csv`; el 80 % es meta publicada del LLM real; el mock no puede leer `labels` ni saltarse la validación | Un gate de 80 % con un mock diseñado para cubrir el catálogo esconde bugs; `08` exigía en CI una evaluación que `02` marcaba como Should; un mock que conoce el `defect_type` haría circular la evaluación |
| R-29 | `02` §5, AC-10.5, `05` §5 | Regla de precedencia de `quarantine_reason` (primer paso que falla); `policy_decision` incluye el resultado del verificador y la etapa | No estaba definido qué motivo usar cuando varias condiciones aplican ni dónde se guardaba el motivo de `VERIFICATION_FAILED` |
| R-30 | `02` §2, AC-06.7 | Definición de "espacio" común Java/Python, `Locale.ROOT`, `Clock` inyectado | `String.strip()` de Java y `str.strip()` de Python difieren en NBSP; `now()` directo hace no deterministas los tests |
| R-35 | AC-03.4, `08` §3.5 | La demo es idempotente por checksum: repetirla exige `down -v`; `demo.sh` reutiliza `existingBatchId` | Un evaluador que ejecuta la demo dos veces recibía `409` sin explicación |

### 3.2 Consistencia modelo de datos ↔ contrato ↔ requisitos (punto 2)

| ID | Dónde | Qué cambió | Por qué |
|---|---|---|---|
| R-08 | AC-08.7 (nuevo), `04` §4, §6.1 | El pipeline valida la respuesta: `requestId`, conjunto exacto de `violationId`, `field`, `originalValue == receivedValue`, y **recalcula** `recordDecision` | El contrato prometía estas propiedades pero solo `ai-service` las garantizaba; la autoridad (ADR-0003) es el pipeline |
| R-14 | `05` §3.4 y §4, `06` B.5 | `correction_log.sales_transaction_id` → FK compuesta `(batch_id, source_row_number)` | La columna tenía dos estrategias de llenado incompatibles y la primera no funciona con `JdbcBatchItemWriter` |
| R-15 | `05` §1, §3, §4; AC-13.4 | `CHECK` de defensa en profundidad en `sales_transaction` (rangos, regex, BR-01/02, `line_total`), `CHECK` en `failure_reason` y `severity` | `failure_reason` terminaba en "..."; la tabla limpia no tenía ninguna red de seguridad a nivel de BD |
| R-20 | `06` A.1 (escala y piso de severidad), AC-08.3, AC-15.6 | `MISSING_REQUIRED_FIELD` siempre `HIGH`; piso de severidad por `errorCode`; `MAX_LENGTH_EXCEEDED` no corregible; bucket `UNCLASSIFIED` | "Token nulo en requerido" era `MEDIUM` y "precio vacío" `HIGH` (mismo código); la suma de severidades no cuadraba con los registros sin IA |
| R-21 | `06` convenciones, `04` §6.1–6.2, `09` §7.5 | Dos fronteras: LLM → `ai-service` estricta (`extra="forbid"`); `ai-service` → pipeline ignora campos desconocidos | `06` decía que agregar campos opcionales es compatible y `09` §7.5 que cualquier campo inesperado es inválido |
| R-22 | AC-15.2, `05` §6, `06` B.3 | Filtros `from/to` por `received_at` del lote; trampa del `null` sin tipo en JDBC; ejemplo de métricas corregido; `corrections` vs `autoCorrected` | Filtrar por `loaded_at` parte lotes y rompe AC-15.3; el ejemplo tenía 60 correcciones en `description` (imposible) y 0 en `invoice_no` |
| R-38 | AC-08.5, `05` §3.5 | `ai_call_log`: una fila por llamada lógica, con `attempts`, `llm_calls`, `error_code` | No estaba definido si un reintento generaba otra fila; sin `llm_calls` no se puede medir el costo real |

### 3.3 Plan de fases, realismo y scope creep (punto 3)

| ID | Dónde | Qué cambió | Por qué |
|---|---|---|---|
| R-31 | `07` §1, §1 bis (nuevo), §2, §3, §4, §4 bis (nuevo), §5; `01` §8; FR-16 | Reestimación por sprint (82–102 h vs 64–80 h); corrección del "15 % de buffer"; CI y logs JSON al Sprint 1; `quarantine_record` + `DisabledAiTriagePort` al Sprint 2; suite adversarial y AC-20.3 en el Sprint 4; *time-boxes*; puntos de control con condiciones objetivas; `v0.4` = núcleo, `v0.6` = todos los Must; FR-16/FR-03 no recortables porque los usa la demo | Ver hallazgos 8 y 9. La CI estaba en el Sprint 8 aunque AC-18.4 es Must y la CI es lo que detecta temprano el código de Batch 5 |

Riesgos de *scope creep* señalados en `07` §5: ajuste infinito del prompt, tuning de performance, pulido del README/GIF (con *time-box*), y el propio trabajo de esta revisión (~6 h, ya incluido en la reestimación).

### 3.4 Sección "fuera de alcance" (punto 4)

| ID | Dónde | Qué cambió | Por qué |
|---|---|---|---|
| R-32 | `01` §6 (lista X-01…X-17) | Lista explícita de lo que un agente tiende a agregar "por completitud", con ejemplos concretos de desvío: alertas, ingesta programada, `.xlsx`, conversión de moneda, `@Scheduled`, *multi-threaded step*, RAG/*tool calling*, Redis en `ai-service`, endpoints de configuración en caliente, etc.; prohíbe también "preparado para" (stubs, flags, interfaces vacías) | La tabla original agrupaba por tema ("Frontend", "Streaming") y dejaba fuera los desvíos más probables de un agente (notificaciones, *watchers* de carpeta, Apache POI porque el dataset viene en `.xlsx`) |

### 3.5 Reglas para agentes de IA (punto 5)

| ID | Dónde | Qué cambió | Por qué |
|---|---|---|---|
| R-33 | `09` §0, §1.4, §2 (+ dependencias), §2.1, §2.2, §2.3, §3, §4, §4 bis, §5.1, §6, §7, §7 bis, §8, §9; `02` §0; AC-09.4; `05` §1; `08` §1; NFR-16 | Definiciones cerradas ("aprobación del owner", "dato"); **matriz de archivos protegidos** 🔴/🟠/🟢; señales de "detente aunque parezca pequeño"; reglas de **integridad de tests, datos y evaluación**; **protocolo de propuesta de cambio de arquitectura** (PR solo con el ADR, plantilla de mensaje); **qué decisión va en ADR, en el PR o en el CHANGELOG**; reglas de git; formato de informe final; definición de "migración aplicada"; se eliminó la copia duplicada de `AGENTS.md` dentro de `09` | Las reglas originales eran correctas pero interpretables: "aprobación" sin definir, sin lista de archivos, sin prohibición de ajustar labels/fixtures/umbrales para que los tests pasen, y dos copias del mismo texto que iban a divergir |
| R-37 | `AGENTS.md` | Reescrito en inglés como espejo compacto de `09` (definiciones, archivos protegidos, integridad, señales de parada, protocolo de ADR) | Es lo primero que lee cualquier agente; antes no mencionaba ninguna de las reglas nuevas |
| R-34 | `README.md` | Índice con este documento; aclaración de la jerarquía cuando el owner contradice los docs; estado transitorio de ADR-0008/0009; glosario ampliado | Evitar que un agente trate una instrucción del chat como si anulara un ADR, o implemente algo que depende de un ADR `Proposed` |

### 3.6 Riesgos técnicos y omisiones (punto 6)

| ID | Tema | Dónde | Qué cambió |
|---|---|---|---|
| R-03 | Caja de `stock_code` | `02` §2, §4 | `NORMALIZE_CASE` ya no se permite en `stock_code` (en el dataset `85123a` ≠ `85123A`) |
| R-04 | LLM que "inventa" números | `02` §4, `10` §3 | `PARSE_NUMBER`: solo `£` removible; rechazo de separador de miles ambiguo; sin símbolos en `quantity`/`customer_id` |
| R-05 | LLM que "inventa" fechas | `02` §4 | `PARSE_DATE` exige hora; trampa `yyyy` + `STRICT` documentada; ambigüedad día/mes con día ≠ mes |
| R-06 | LLM que "inventa" sinónimos | `02` §4, `04` §11, `10` §2, ADR-0008 | `MAP_TO_ENUM` verificado contra `country-aliases.v1.yaml` o normalización; si no, cuarentena con la sugerencia |
| R-07 | Correcciones compuestas | `02` §4 | Una corrección por campo; qué hacer con `" uk "` |
| R-09 | Caché incorrecta | AC-08.4, NFR-11, `04` §6.2, `06` A.1 | Clave única y completa; `_record` no se cachea; explicaciones sin datos de otras columnas; aciertos parciales |
| R-10 | Costos de API | AC-08.6, AC-08.8, NFR-11, `01` §8, `04` §8–9, `06` A.5 | Definición exacta del presupuesto por lote (incluye fallas, sobrevive a reinicios); tope diario `LLM_MAX_CALLS_PER_DAY`; `429 LLM_BUDGET_EXHAUSTED`; fórmula de costo; **límite de gasto en la consola del proveedor como única garantía dura** |
| R-11 | Timeouts y fallas del LLM | NFR-07, AC-12.5, `06` A.5 | Plazo total en `ai-service`; reintentos solo de transporte en el pipeline; circuit breaker con llamadas lentas; cota de tiempo en el peor caso; test de IA **colgada** (más dañino que caída) |
| R-13 | Idempotencia / reinicio | NFR-04, NFR-06, AC-14.2, `04` §3, `05` §3.1, §6 | `read_count` independiente (pasada de conteo en `intakeStep`); invariante con "sin huecos"; lector `ItemStream`; `ON CONFLICT` como red de seguridad, no como lógica |
| R-16 | Trampas de Boot 4 / Batch 6 | `04` §3.1 (nuevo), §12, `05` §1, `09` §7 bis, NFR-05 | `spring-boot-starter-batch-jdbc`; schema de Batch por Flyway `V1` con `initialize-schema=never`; `flyway-database-postgresql`; `JobOperator.recover`; `setStrict(false)` prohibido; `JAVA_TOOL_OPTIONS`; `REQUIRES_NEW` en otro bean y tamaño de pool; tabla de estados Batch ↔ `ingestion_batch` |
| R-17 | PostgreSQL 18 en Docker | `04` §10 | Volumen en `/var/lib/postgresql` |
| R-23 | Privacidad en `MALFORMED_ROW` | AC-07.4, NFR-15, `04` §4, `06` A.1 | La redacción no puede aplicarse a una línea sin columnas: truncado y opción de no enviarla; limitación documentada; el diagrama pasaba estos registros por fuera del presupuesto |
| R-24 | Inyección de prompt | NFR-14 | Datos delimitados; la defensa estructural es la política; fixture con instrucción inyectada |
| R-25 | Prueba de que el LLM no puede colar errores | AC-10.6, NFR-16, `08` | Suite adversarial: respuestas plausibles e incorrectas con confianza 0.99 nunca llegan a la tabla limpia |
| R-27 | Generador de datos | AC-19.2, AC-19.4, `10` §2–§3, §5 | `expected_error_code` en labels; un defecto por campo; precondiciones por defecto; `Customer ID` de Excel como float; labels propios para JSON; test que valida cada label con el validador real |
| R-28 | Concurrencia | NFR-10, `04` §3, `06` B.1 | Un job a la vez (cola); un worker de Uvicorn; clientes asíncronos en endpoints `async` |
| R-36 | Secretos en Compose | NFR-14, `04` §10 | Sin `env_file` global: la API key solo llega a `ai-service` |

## 4. Decisiones que tomé y que el owner puede preferir distinto

Todas son reversibles y están marcadas con su `R-xx`:

1. **`MAP_TO_ENUM` con archivo de alias (R-06).** Alternativa: aceptar solo igualdad normalizada y dejar todo sinónimo real a cuarentena (más seguro, menos demo). Descarté similitud de texto porque premia justo `Austria`/`Australia`.
2. **Seguir enviando `MALFORMED_ROW` al LLM (R-23).** Da la explicación más útil de la demo ("coma sin comillas en la descripción"), a cambio de la limitación de privacidad documentada. Alternativa: explicación determinista del pipeline sin LLM.
3. **`LLM_MAX_CALLS_PER_DAY = 2000` (R-10).** Valor arbitrario pero conservador; ajustarlo tras medir tokens por llamada en el Sprint 5.
4. **Un job a la vez (R-28).** Simplifica presupuesto, circuit breaker y cota de tiempo. El paralelismo ya estaba fuera de alcance.
5. **AC-20.3 pasa a Must (R-26).** Resuelve la contradicción entre `02` (Should) y `08` (gate de CI); el costo real es bajo porque es un test de integración que el Sprint 4 ya necesitaba.
6. **Umbrales de los puntos de control (R-31)**: 26 h al final del Sprint 2 y 60 h al final del Sprint 6. Son una propuesta; lo importante es que existan antes de empezar.
7. **Ambigüedad de miles en `PARSE_NUMBER` (R-04)**: la regla "un separador + exactamente 3 dígitos → ambiguo" también manda a cuarentena precios como `"2.555"`. Es deliberadamente conservadora.

## 5. Dependencias con los ADR `Proposed`

| ADR | Cambios que dependen de él | Si el owner lo rechaza |
|---|---|---|
| ADR-0008 | R-03, R-04, R-05, R-06, R-19 (AC-05.7), R-25 | Revertir esos cambios en `02` §4, FR-05, FR-10 y `10`; la suite adversarial se mantiene, pero documentará qué errores sí pasan |
| ADR-0009 | R-12 (índice parcial y AC-04.3b; la reconciliación AC-04.6 no depende del ADR) | Volver a `UNIQUE (source_checksum)` y documentar como limitación que un `REJECTED` por IA caída solo se reprocesa con `down -v` |

## 6. Lo que deliberadamente **no** cambié

- Stack, versiones, patrón ETL, número de servicios, comunicación síncrona, alcance del MVP y prioridades MoSCoW (salvo AC-20.3 y la nota de no-recorte de FR-16, ambas para resolver contradicciones internas).
- ADRs `Accepted` en su sustancia (solo se agregó a ADR-0003 y ADR-0007 una línea de metadatos que apunta a ADR-0008/0009).
- **Observación sin cambio:** ADR-0006 describe Java 26 como "no LTS, si estuviera disponible"; Java 26 ya se publicó en marzo de 2026. No altera la decisión (Java 25 LTS sigue siendo correcta) y el ADR está `Accepted`, así que no se editó.
- Las afirmaciones de versión de ADR-0006 sobre Spring Batch 6 se contrastaron con fuentes públicas en lo que afecta a esta revisión (starter JDBC separado en Boot 4 y `JobOperator.recover`); el resto no se reverificó.

## 7. Fuentes consultadas para afirmaciones dependientes de versión

- Spring Boot 4 separa el repositorio JDBC de Batch en `spring-boot-starter-batch-jdbc`: <https://medium.com/@shin991116/uing-spring-batch-jdbc-in-spring-boot-4-x-and-spring-batch-6-x-93647e012111>, <https://spring.io/blog/2026/06/21/spring-boot-41-and-spring-batch/>
- `JobOperator.recover()` para ejecuciones interrumpidas en Spring Batch 6: <https://blog.zakaria.lu/spring-batch-6-hands-on-a-new-engine-a-real-operator-and-stops-that-cross-jvms>
- Documentación oficial de configuración de infraestructura de Batch 6: <https://docs.spring.io/spring-batch/reference/6.0/job/configuring-infrastructure.html>
- Cambio de ruta de datos en la imagen oficial de PostgreSQL 18: <https://github.com/docker-library/postgres/issues/1370>, <https://aronschueler.de/blog/2025/10/30/fixing-postgres-18-docker-compose-startup/>
