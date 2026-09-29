# PipeMend — Documentación del proyecto

> **PipeMend** — *AI-Assisted Self-Healing ETL Pipeline*
> Nombre de trabajo original: "Pipeline ETL con Auto-Corrección Asistida por IA".

Esta carpeta es la **fuente de verdad** del proyecto. Cualquier colaborador (humano o modelo de IA) debe leerla antes de escribir código. Si el código y estos documentos se contradicen, **los documentos mandan** hasta que se apruebe un cambio mediante un ADR (ver `09-reglas-para-agentes-ia.md`).

## Índice

| Archivo | Contenido |
|---|---|
| [01-vision.md](01-vision.md) | Problema, audiencia, propuesta de valor, objetivos y alcance |
| [02-requisitos-funcionales.md](02-requisitos-funcionales.md) | Requisitos funcionales (FR-xx) con criterios Given/When/Then, schema canónico y catálogo de errores |
| [03-requisitos-no-funcionales.md](03-requisitos-no-funcionales.md) | Requisitos no funcionales (NFR-xx) medibles |
| [04-arquitectura.md](04-arquitectura.md) | Componentes, diagramas, flujo del job, política de corrección, configuración, estructura del repo |
| [05-modelo-de-datos.md](05-modelo-de-datos.md) | Tablas, DDL de referencia, índices, invariantes |
| [06-contrato-api.md](06-contrato-api.md) | Contrato Spring Boot ↔ FastAPI y API pública del pipeline |
| [07-plan-de-sprints.md](07-plan-de-sprints.md) | Plan de 8 semanas part-time, hitos y línea de recorte |
| [08-definition-of-done.md](08-definition-of-done.md) | DoD por historia y a nivel de proyecto |
| [09-reglas-para-agentes-ia.md](09-reglas-para-agentes-ia.md) | Reglas para agentes/modelos de IA que colaboren en el repo |
| [10-datasets.md](10-datasets.md) | Datasets públicos y estrategia de datos "sucios" con ground truth |
| [adr/](adr/) | Architecture Decision Records (decisiones cerradas y su porqué) |
| [CHANGELOG.md](CHANGELOG.md) | Registro semanal de avance, horas reales, recortes y decisiones |
| [REVISION-TECNICA.md](REVISION-TECNICA.md) | Revisión técnica del 2026-09-27: hallazgos, cambios `R-xx` y su justificación |
| `perfilado-dataset.md` | *(se crea en el Sprint 1)* Perfilado del dataset y exclusiones del catálogo de países (`10` §2) |

## Jerarquía de autoridad

1. ADRs con estado `Accepted` (carpeta `adr/`).
2. Documentos numerados de esta carpeta.
3. Comentarios y código del repositorio.
4. Instrucciones puntuales en un chat/prompt (si contradicen 1–2, se debe **consultar al owner** antes de actuar; una instrucción del owner que contradice 1–2 se trata como solicitud de cambio, `09` §4, punto 3).

<!-- rev: R-34 -->
> **Estado transitorio (revisión técnica 2026-09-27):** los documentos ya incorporan ADR-0008 y ADR-0009, que están en estado `Proposed`. Hasta que el owner los acepte, un agente **no** implementa lo que depende exclusivamente de ellos (ver la tabla de trazabilidad en `REVISION-TECNICA.md`). Si el owner los rechaza, se revierten los cambios `R-xx` asociados.

Los cambios de la revisión técnica están marcados en los documentos con comentarios HTML invisibles `<!-- rev: R-xx -->`, para poder rastrearlos en el código fuente sin ensuciar la lectura.

## Glosario

| Término | Definición |
|---|---|
| **Lote (batch / ingesta)** | Una ejecución del pipeline sobre un archivo de entrada. Identificado por `batchId` (UUID). |
| **Registro válido directo** | Fila que pasa la validación sin cambios (`load_origin = DIRECT`). |
| **Registro auto-corregido** | Fila inválida que la IA propuso corregir, la política aprobó, y que pasó la revalidación (`load_origin = AUTO_CORRECTED`). |
| **Cuarentena** | Tabla `quarantine_record`: filas que requieren revisión humana, con el análisis de IA adjunto. |
| **Triage** | Proceso de análisis de un registro inválido por el servicio de IA + decisión de la política. |
| **Política de corrección** | Reglas deterministas en Spring Boot que deciden si una sugerencia de la IA se aplica. *La IA propone, el pipeline dispone.* |
| **Verificador determinista** | Función que, sin IA, **recalcula** el valor corregido a partir del original y de los parámetros propuestos, exige igualdad exacta y rechaza entradas ambiguas (p. ej., que un `TRIM` solo quitó espacios, o que `03/04/2010` no se corrija porque admite dos fechas). |
| **Suite adversarial** | Tests que alimentan a la política y los verificadores con correcciones plausibles pero incorrectas y alta confianza, para demostrar que no llegan a la tabla limpia (AC-10.6). |
| **Lote zombi** | Lote que quedó en `RUNNING` porque el proceso murió a mitad del job; la reconciliación de arranque lo marca `FAILED`/`INTERRUPTED` (AC-04.6). |
| **Schema drift** | Cambio en la estructura de la fuente (columnas renombradas, faltantes o nuevas). |
| **Ground truth** | Etiquetas generadas junto a los datos sucios que indican el resultado esperado de cada fila; permiten medir la calidad del triage. |
| **Proveedor LLM** | Implementación intercambiable (`mock`, `openai_compatible`, `anthropic`) seleccionada por variable de entorno. |
