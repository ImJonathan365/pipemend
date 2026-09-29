# ADR-0009 — Reenvío de lotes `REJECTED` y reconciliación de ejecuciones interrumpidas

- **Estado:** Accepted
- **Fecha:** 2026-09-27
- **Autor:** revisión técnica (agente: Claude)
- **Aprobado por:** owner (2026-09-28)
- **Requisitos relacionados:** FR-04, FR-05, FR-21, NFR-04, NFR-06
- **Enmienda a:** ADR-0007 (solo el punto "`UNIQUE` en `ingestion_batch`"; el resto de ADR-0007 sigue vigente)

## Contexto

ADR-0007 define `UNIQUE (source_checksum)` sin condiciones. Eso produce dos callejones sin salida:

1. **Rechazo transitorio permanente.** Si un archivo con *schema drift* llega mientras `ai-service` está caído, el lote termina `REJECTED` (AC-05.5). Al reenviar el mismo archivo con la IA ya disponible, la restricción única devuelve `409` para siempre. La única salida es `docker compose down -v`, que borra todo.
2. **Lote zombi.** Si el contenedor de `pipeline-service` se reinicia a mitad de un job, `ingestion_batch` queda en `RUNNING` y la `JobExecution` en `STARTED` indefinidamente. Nadie la marca `FAILED`, FR-21 (que exige `FAILED`) no puede reiniciarla, y el archivo recibe `409` para siempre.

Además, AC-04.2 solo mencionaba `COMPLETED` y `RUNNING`, dejando sin definir `RECEIVED` y `REJECTED`.

## Decisión

1. Sustituir la restricción por un **índice único parcial**: `UNIQUE (source_checksum) WHERE status <> 'REJECTED'`. Un lote `REJECTED` no procesó filas (por definición), así que reenviar su archivo como lote nuevo es seguro.
2. `RECEIVED`, `RUNNING`, `COMPLETED` y `FAILED` siguen bloqueando el reenvío con `409` (un `FAILED` puede tener chunks confirmados; su camino es FR-21).
3. La detección de duplicados es por violación de la restricción (`SQLState 23505`), no "consultar y luego insertar".
4. **Reconciliación de arranque** (AC-04.6, Must): al iniciar, `pipeline-service` marca `FAILED` con `failure_reason = INTERRUPTED` los lotes `RUNNING` cuya ejecución quedó `STARTED`, usando `JobOperator.recover(...)` de Spring Batch 6, y reencola los `RECEIVED` sin ejecución.

## Alternativas consideradas

| Alternativa | Motivo de descarte |
|---|---|
| Borrar el lote `REJECTED` al reenviar | Se pierde el historial del rechazo (útil para la demo y la auditoría) |
| Parámetro `force=true` | Ya descartado en ADR-0007: confuso para métricas |
| Permitir reenviar también `FAILED` como lote nuevo | Duplicaría las filas de los chunks ya confirmados del lote fallido |
| Actualizar a mano las tablas `BATCH_*` en la reconciliación | Frágil y depende del esquema interno de Spring Batch; `recover` existe para esto |

## Consecuencias

- Positivas: ningún estado queda sin salida salvo `FAILED` sin FR-21 (documentado como limitación). La reconciliación es condición para que FR-21 funcione.
- Negativas: un mismo checksum puede aparecer en varias filas de `ingestion_batch` (todas `REJECTED` salvo, como mucho, una). Las consultas por checksum deben filtrar por estado.
- Esfuerzo estimado: ~1 h (índice + test de reenvío) + ~1 h (reconciliación + test).
- Documentos actualizados: `02` §5, FR-04, FR-05; `03` NFR-04; `04` §3; `05` §3.1, §4; `06` B.1; `08` §3.2.

## Criterio de revisión

Si se implementa reprocesamiento de lotes `FAILED` como lote nuevo (fuera de alcance hoy), revisar junto con una estrategia de limpieza de filas parciales.
