# 01 — Visión y objetivos del producto

## 1. Nombre

**PipeMend** — *AI-Assisted Self-Healing ETL Pipeline*.

- *Pipe* (pipeline) + *mend* (reparar, remendar). Corto, pronunciable y describe el valor: un pipeline que se repara a sí mismo cuando el riesgo lo permite.
- Alternativas consideradas: *HealFlow*, *Triage ETL*, *DataMedic*, *SelfHeal ETL*. Se eligió PipeMend por ser específico y fácil de buscar.
- Nombre de repositorio sugerido: `pipemend`. Paquete Java base: `com.pipemend`.

## 2. Problema

Las organizaciones con múltiples sistemas y pipelines de datos sufren fallas recurrentes por **errores de datos comunes y repetitivos**:

- tipos incorrectos (`"2,55"` donde se esperaba un decimal),
- formatos inconsistentes (fechas `dd/MM/yyyy` vs `yyyy-MM-dd`),
- campos requeridos vacíos o con tokens basura (`"N/A"`, `"-"`),
- valores de catálogo con sinónimos (`"UK"` vs `"United Kingdom"`),
- cambios de schema en una fuente externa (columna `UnitPrice` renombrada a `Price`),
- nulos inesperados y filas malformadas.

Hoy, el flujo típico es: el job falla o descarta filas → alguien recibe una alerta → lee logs y stacktraces → entiende qué pasó → corrige a mano o parchea el código → vuelve a ejecutar. Ese ciclo cuesta horas de ingeniería, retrasa datos al negocio y, peor aún, **la mayoría de los errores son triviales y repetitivos**, pero se tratan con el mismo proceso manual que los graves.

## 3. Propuesta de valor

PipeMend es un pipeline ETL que, cuando un registro falla la validación:

1. **Explica** el error en lenguaje humano (no un stacktrace).
2. **Clasifica** su severidad y riesgo.
3. **Corrige automáticamente** los casos de bajo riesgo, pero **solo** si la corrección pasa una política determinista, un verificador sin IA y una revalidación completa.
4. **Pone en cuarentena** los casos que requieren criterio humano, con un reporte completo listo para actuar.

Todo queda trazado: qué se corrigió, por qué, con qué modelo y versión de prompt, y con qué confianza.

### Principio rector

> **La IA propone, el pipeline dispone.**
> El LLM diagnostica y sugiere; la decisión de aplicar una corrección la toma código determinista y auditable en el pipeline. PipeMend nunca inventa datos: un valor faltante o implausible jamás se "rellena" automáticamente.

### ¿Por qué un LLM si hay verificadores deterministas?

Pregunta esperable de un evaluador técnico. Respuesta honesta:

- El LLM **diagnostica**: identifica la causa probable y la transformación adecuada con sus parámetros (patrón de fecha de origen, separador decimal, mapeo de sinónimo a catálogo, mapeo de columnas renombradas). Escribir reglas para cada variante posible es lo que hoy no escala.
- El LLM **explica**: el principal producto para los casos en cuarentena es un reporte comprensible para un humano no experto en el pipeline.
- El verificador **no descubre** la corrección; solo **comprueba** que la corrección propuesta preserva la semántica del valor original (p. ej., que `"25/12/2010"` con patrón `dd/MM/yyyy` realmente es `2010-12-25`). Verificar es mucho más barato y seguro que generar.

## 4. Audiencia

### Audiencia del portafolio (primaria)

Reclutadores técnicos y equipos de ingeniería que evalúan candidatos para roles de **backend / data engineering con foco en automatización e IA aplicada**. Lo que deben poder comprobar en menos de 15 minutos:

| Quieren ver | Cómo lo demuestra PipeMend |
|---|---|
| Dominio de Spring Boot / Spring Batch | Job ETL con pasos, chunks, writers clasificados, reinicio e idempotencia |
| Integración de IA con criterio | IA desacoplada, salida estructurada validada, política determinista, métricas de calidad del triage |
| Diseño de sistemas distribuidos simples | Dos servicios, contrato HTTP versionado, timeouts, circuit breaker, degradación segura |
| Modelado de datos | Tablas limpias, cuarentena y log de correcciones con invariantes verificables |
| Madurez de ingeniería | Docker Compose de un comando, tests con Testcontainers, OpenAPI, ADRs, logs estructurados |
| Honestidad técnica | Evaluación con *ground truth*, limitaciones documentadas |

### Usuario ficticio del producto (para dar coherencia a las decisiones)

- **Data engineer de guardia**: quiere que los errores triviales no le despierten y que los no triviales lleguen con diagnóstico.
- **Analista / dueño de los datos**: quiere saber cuántos datos llegaron limpios, cuántos se corrigieron y cuáles están bloqueados.

## 5. Objetivos

### Objetivos del producto (MVP)

| ID | Objetivo | Métrica de éxito |
|---|---|---|
| OBJ-1 | Ningún registro se pierde silenciosamente | `leídos = directos + auto-corregidos + cuarentena` en el 100 % de los lotes |
| OBJ-2 | Las correcciones automáticas son seguras | (a) 0 correcciones automáticas incorrectas con el proveedor `mock` (gate de CI); (b) 0 correcciones incorrectas aceptadas en la suite adversarial (respuestas plausibles pero erróneas inyectadas a propósito, NFR-16); (c) tasa medida y publicada con un LLM real |
| OBJ-3 | Los errores triviales no requieren humano | Con `mock`: 100 % de las filas terminan con el `expected_outcome` de `labels.csv` (gate de CI; el mock está diseñado para cubrir el catálogo, así que < 100 % es un bug). Con LLM real: *recall* de auto-corrección ≥ 80 % como **meta publicada**, no como gate (si no se alcanza, se publica el número real) |
| OBJ-4 | Los casos en cuarentena son accionables | 100 % de registros en cuarentena tienen explicación, severidad y acción sugerida (o motivo técnico si la IA no estuvo disponible) |
| OBJ-5 | El pipeline es resiliente a la IA | Con el servicio de IA caído, el job termina correctamente y envía los inválidos a cuarentena |
| OBJ-6 | Visibilidad | Un endpoint de métricas responde cuántos registros pasaron directo, se auto-corrigieron o quedaron en cuarentena |

### Objetivos del portafolio

- Levantar todo con **un solo comando** y sin API key (proveedor `mock` por defecto).
- Un **script de demo** que en < 5 minutos muestre el flujo completo.
- README con diagrama, GIF/video corto y resultados de evaluación reales.

## 6. Alcance

### Dentro del MVP

- Ingesta de archivos CSV (obligatorio) y JSON (deseable) mediante carga por API.
- Validación contra un schema declarativo versionado.
- Detección de *schema drift* en cabeceras con mapeo asistido por IA (deseable).
- Triage con IA de registros inválidos vía microservicio FastAPI.
- Política de corrección determinista, verificadores, revalidación.
- Persistencia en PostgreSQL: datos limpios, cuarentena, log de correcciones, log de llamadas a IA.
- Endpoints de estado, métricas, cuarentena y correcciones.
- OpenAPI/Swagger en ambos servicios.
- Generador de datos sucios con *ground truth* y evaluación de calidad del triage.
- Docker Compose con un comando.

### Fuera del alcance (explícito)

| Excluido | Motivo / destino |
|---|---|
| Frontend o dashboard visual | Swagger + endpoints bastan. Mejora futura. |
| Autenticación / autorización | Mejora futura (p. ej., API key o OAuth2 resource server). |
| Multi-tenant | No aporta a la demostración. |
| Integración real con sistemas empresariales | Se simula con datasets públicos/generados. |
| ELT, capa analítica, dbt | Posible fase 2. |
| Reprocesamiento automático de la cuarentena | Mejora futura; en el MVP solo se consulta y (opcionalmente) se marca como resuelta. |
| Deduplicación entre filas | Mejora futura. |
| Streaming (Kafka, CDC) | Fuera del modelo batch del MVP. |
| Orquestadores externos (Airflow, Dagster) | Spring Batch es el orquestador del MVP. |
| Múltiples schemas de destino simultáneos | El MVP maneja un schema canónico (`sales_transaction v1`). |

<!-- rev: R-32 -->
#### Lista explícita de exclusiones (para colaboradores humanos y agentes de IA)

La tabla anterior agrupa por tema; esta lista enumera **lo que un agente tiende a agregar "por completitud"** y que **NO** se implementa en el MVP, ni completo, ni "a medias", ni "preparado para" (interfaces vacías, flags, TODOs, endpoints stub, columnas reservadas). Si una tarea parece requerir algo de esta lista, el agente se detiene y consulta (ver `09` §4).

| # | Excluido (no implementar) | Ejemplos concretos de desvío que se rechazan |
|---|---|---|
| X-01 | Cualquier interfaz gráfica | Thymeleaf, React/Vue/Svelte, Streamlit/Gradio, páginas HTML estáticas, personalizar Swagger UI más allá de descripciones y ejemplos |
| X-02 | Autenticación, autorización, CORS | Spring Security, API keys propias, JWT, OAuth2, Keycloak, filtros de CORS |
| X-03 | Reprocesar, editar o "reintentar" registros en cuarentena | Endpoints `replay`/`reprocess`/`fix`, editar `raw_payload`, mover filas de cuarentena a la tabla limpia, `PATCH` con campos distintos de `status` y `note` (FR-22) |
| X-04 | Alertas y notificaciones | Email, Slack, webhooks, PagerDuty, "notificar al owner cuando..." |
| X-05 | Ingesta programada o automática | `@Scheduled`, cron, *watchers* de carpeta, *polling* del inbox, subida por URL/S3/FTP/SFTP |
| X-06 | Formatos de entrada adicionales | `.xlsx` (Apache POI), Parquet, XML, TSV, separadores configurables, encodings distintos de UTF-8, archivos comprimidos |
| X-07 | Más de un schema o schemas dinámicos | Endpoint para subir schemas, *schema registry*, `sales_transaction` v2, inferencia automática de schema |
| X-08 | Deduplicación de negocio y *data quality* genérica | Deduplicar por `invoice_no`+`stock_code`, Great Expectations, Pandera, Deequ, *profiling* en runtime |
| X-09 | Transformaciones semánticas | Conversión de moneda, zonas horarias, geocodificación, enriquecimiento con APIs externas, normalizar `description` |
| X-10 | Paralelismo y escalado | *Multi-threaded step*, *partitioning*, `AsyncItemProcessor`, *remote chunking*, varias réplicas de un servicio, varios *workers* de Uvicorn |
| X-11 | Capacidades de LLM más allá de una llamada estructurada | Agentes, *tool/function calling* para ejecutar acciones, RAG, embeddings, BD vectorial, *fine-tuning*, LLM-como-juez, votación entre varios modelos, *streaming* de respuestas, Batch APIs asíncronas de proveedores |
| X-12 | Estado persistente en `ai-service` | Redis, SQLite, archivos de caché, cualquier BD. Solo estado efímero en memoria (caché LRU y contador diario de llamadas, NFR-11) |
| X-13 | Configuración en caliente | Endpoints para cambiar umbrales, prompts, proveedor o allowlist en runtime; todo es configuración de arranque |
| X-14 | Despliegue en la nube e infraestructura como código | Kubernetes, Helm, Terraform, despliegue en AWS/GCP/Azure, *registry* de imágenes |
| X-15 | Observabilidad avanzada | Prometheus/Grafana/Loki/Tempo/OpenTelemetry *collector* (Could solo **después** de `v1.0`, como perfil opcional de Compose) |
| X-16 | Retención, purga, archivado, particionado de tablas | Jobs de limpieza, TTL en tablas, particiones por fecha |
| X-17 | Internacionalización | Idiomas distintos de `es`/`en` en explicaciones; mensajes de error de API traducidos |

## 7. Por qué importa

- El costo de los datos malos no es el error en sí, sino el **tiempo humano** para diagnosticar errores repetitivos y el **retraso** de datos al negocio.
- La IA generativa es buena explicando y proponiendo, pero peligrosa si escribe directamente en datos productivos. PipeMend muestra un patrón **responsable**: IA en el ciclo de diagnóstico, controles deterministas en el ciclo de escritura, y todo medido.

## 8. Supuestos y restricciones

- Desarrollo individual, ~8–10 h/semana durante 8 semanas (64–80 h totales). La reestimación de `07` §1 bis muestra que el alcance Must + Should completo está **por encima** de esa capacidad; la línea de recorte de `07` §4 no es un plan de contingencia sino el escenario esperado para parte de los Should. <!-- rev: R-31 -->
- Ejecución local (laptop). No hay despliegue en la nube dentro del MVP.
- Volúmenes de portafolio (decenas de miles de filas), no big data.
- Costo de LLM mínimo: por defecto `mock`; con proveedor real se aplican caché, tope de llamadas por lote, tope diario en `ai-service` y un **límite de gasto configurado en la consola del proveedor** (la única garantía dura; ver NFR-11). <!-- rev: R-10 -->
