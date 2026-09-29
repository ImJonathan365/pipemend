# ADR-0007 — Idempotencia por checksum de archivo y clave (lote, fila)

- **Estado:** Accepted
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** FR-04, FR-21, NFR-04, NFR-06
- **Enmendado por:** ADR-0009 (`Proposed`) — solo el punto de la restricción única de checksum

## Contexto
Los pipelines se reintentan y los archivos se reenvían. Se necesita que reenviar o reiniciar no duplique datos y que el invariante de conteo se mantenga.

## Decisión
- Identidad del archivo = SHA-256 del contenido, `UNIQUE` en `ingestion_batch`. Reenvío del mismo contenido → `409 DUPLICATE_INGESTION`.
- Identidad del registro = `(batch_id, source_row_number)`, `UNIQUE` en `sales_transaction` y `quarantine_record`; `(batch_id, source_row_number, field_name)` en `correction_log`.
- Escrituras con `INSERT ... ON CONFLICT DO NOTHING`.
- Parámetros identificadores del job: `batchId` y `sourceChecksum` (permite reinicio de Spring Batch desde el último chunk confirmado).
- Sin skip policy: todo registro termina en la tabla limpia o en cuarentena.

## Alternativas consideradas
| Alternativa | Motivo de descarte |
|---|---|
| Idempotencia por clave de negocio (`invoice_no` + `stock_code`) | El dataset real tiene líneas repetidas legítimas; la deduplicación de negocio está fuera de alcance |
| Permitir reprocesar el mismo archivo con `force=true` | Scope; confuso para métricas |

## Consecuencias
- El mismo contenido con otro nombre se detecta como duplicado (deseado).
- Un archivo con un byte distinto es un lote nuevo (aceptado).
