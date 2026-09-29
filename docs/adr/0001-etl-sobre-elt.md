# ADR-0001 — ETL en lugar de ELT

- **Estado:** Accepted
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** FR-06, FR-09, FR-10, FR-13

## Contexto
El producto valida registros, pide diagnóstico a un LLM, aplica correcciones bajo una política determinista y separa los casos dudosos en cuarentena. Hay que decidir si esa lógica ocurre antes de cargar (ETL) o después de cargar datos crudos en el destino (ELT, típicamente con dbt en un warehouse).

## Decisión
Se usa **ETL clásico**: Extract → Transform/Validate (incluye triage, política, verificación, revalidación) → Load. La tabla `sales_transaction` solo recibe registros validados. El crudo se conserva únicamente para registros en cuarentena (`raw_payload`) por auditoría.

## Alternativas consideradas
| Alternativa | Pros | Contras | Motivo de descarte |
|---|---|---|---|
| ELT (carga cruda + dbt) | Estándar moderno en analítica; reprocesable con SQL | Los datos sucios ya viven en el destino; llamadas HTTP a un LLM y política imperativa son forzadas en SQL; requiere warehouse | El valor del producto está en decidir **antes** de persistir |
| Híbrido (staging raw + ETL a limpio) | Reprocesamiento fácil | Más tablas, más scope, doble escritura | Scope; posible fase 2 |

## Consecuencias
- Positivas: contrato de calidad en la frontera; consumidores confían en la tabla limpia; lógica en Java testeable.
- Negativas: no hay capa raw completa para reprocesar todo (solo cuarentena); reprocesar exige reenviar el archivo.
- Fase 2 posible: capa analítica ELT/dbt **sobre** la tabla limpia.

## Criterio de revisión
Necesidad de reprocesar históricos completos o de volúmenes que requieran cómputo de warehouse.
