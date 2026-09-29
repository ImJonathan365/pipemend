# ADR-0005 — PostgreSQL único, acceso solo desde el pipeline, Spring JDBC + Flyway

- **Estado:** Accepted
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** FR-04, FR-11, FR-13, NFR-04, NFR-05, NFR-17

## Contexto
Se necesitan escrituras por lotes eficientes, transacciones por chunk que abarquen varias tablas y un esquema auditable.

## Decisión
- Una base PostgreSQL 18; **solo `pipeline-service`** la usa. `ai-service` no tiene base de datos.
- Acceso con **Spring JDBC** (`JdbcClient`, `JdbcTemplate`, `JdbcBatchItemWriter`), **sin JPA/Hibernate**.
- Migraciones con **Flyway**; migraciones aplicadas son inmutables.
- Enums como `text` + `CHECK`; datos semiestructurados en `jsonb`.

## Alternativas consideradas
| Alternativa | Motivo de descarte |
|---|---|
| JPA/Hibernate | Overhead y sorpresas (flush, dirty checking) en escrituras batch; menos control del SQL |
| jOOQ | Buena opción, pero agrega generación de código y curva de aprendizaje |
| BD separada para IA (caché persistente) | Rompe la condición stateless; la caché en memoria basta |

## Consecuencias
SQL explícito y visible (bueno para portafolio); más código de mapeo manual.
