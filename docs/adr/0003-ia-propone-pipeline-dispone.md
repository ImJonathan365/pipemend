# ADR-0003 — La IA propone, el pipeline dispone (política determinista)

- **Estado:** Accepted
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** FR-09, FR-10, FR-11, NFR-03
- **Complementado por:** ADR-0008 (`Accepted`) — especificación de verificadores que recalculan y rechazan ambigüedad

## Contexto
Un LLM puede equivocarse con alta confianza aparente. Aplicar directamente sus correcciones a datos productivos es un riesgo inaceptable, y es exactamente lo que un evaluador técnico buscará criticar.

## Decisión
1. El LLM solo **diagnostica, explica, clasifica y propone**.
2. `CorrectionPolicy` (Java puro, en `pipeline-service`) decide con reglas deterministas: allowlist de operaciones, campos permitidos por operación, severidad `LOW`, umbral de confianza, códigos de error no corregibles, todo o nada por registro.
3. Cada corrección aprobada pasa por un **verificador determinista** que comprueba la equivalencia semántica entre valor original y propuesto.
4. El registro corregido se **revalida completo**.
5. Nunca se rellenan valores faltantes ni se cambia signo o magnitud.
6. El servicio de IA aplica reglas duras equivalentes como defensa en profundidad, pero no es la autoridad.

## Justificación del uso de LLM
El verificador comprueba, no descubre: el LLM aporta la identificación de la transformación y sus parámetros (patrón de fecha, separador decimal, sinónimo de catálogo, mapeo de columnas) y la explicación humana para los casos en cuarentena, que es el principal producto para el equipo de datos.

## Alternativas consideradas
| Alternativa | Motivo de descarte |
|---|---|
| Aplicar directamente lo que diga el LLM si `confidence` alta | La confianza autoinformada no está calibrada |
| Solo reglas deterministas, sin LLM | No escala a variantes nuevas; sin explicaciones; no demuestra IA aplicada |
| LLM como juez de su propia corrección | Mismo modelo, mismos sesgos; costo doble |

## Consecuencias
- Menor tasa de auto-corrección que un enfoque agresivo (aceptado: se prioriza precisión sobre recall).
- Agregar operaciones requiere ADR + verificador + tests.
