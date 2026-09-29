# ADR-0008 — Verificadores que recalculan y rechazan ambigüedad (anti-alucinación)

- **Estado:** Proposed
- **Fecha:** 2026-09-27
- **Autor:** revisión técnica (agente: Claude)
- **Aprobado por:** — (pendiente del owner)
- **Requisitos relacionados:** FR-09, FR-10, OBJ-2, NFR-16
- **Complementa a:** ADR-0003 (no lo reemplaza)

## Contexto

ADR-0003 establece que la confianza autoinformada del LLM no está calibrada y que un verificador determinista comprueba cada corrección. Pero la especificación original de algunos verificadores (`02` §4) comprobaba **coherencia con los parámetros que eligió la propia IA**, no la corrección del resultado:

1. `PARSE_NUMBER` normalizaba según el `decimalSeparator` propuesto por la IA. Si la IA interpreta `"2,550"` como `2.55` (coma decimal) en lugar de `2550` (coma de miles), el verificador lo acepta: **cambio de magnitud ×1000 aprobado**.
2. `PARSE_NUMBER` permitía quitar `$` y `€` de un precio en un dataset en libras: `"€2.55"` → `2.55 GBP` es una **conversión de moneda inventada**.
3. `MAP_TO_ENUM` solo comprobaba que el valor propuesto perteneciera al catálogo y que `confidence ≥ 0.90`. `"Austria"` → `"Australia"` con confianza 0.95 pasaba. Esto contradice directamente ADR-0003.
4. `NORMALIZE_CASE` estaba permitido en `stock_code`, donde el dataset real tiene códigos que solo difieren en mayúsculas (`85123a` vs `85123A`): cambiar la caja cambia el producto.
5. `PARSE_DATE` no exigía hora, de modo que `"25/12/2010"` → `2010-12-25 00:00:00` inventaría precisión; y con `ResolverStyle.STRICT`, los patrones `yyyy` que proponen los LLM fallan sin era (trampa de `java.time`).
6. El mapeo de cabeceras por IA (FR-05) no tenía verificador: "Ver FR-05" remitía solo a condiciones de forma (1:1, confianza), que un mapeo `UnitPrice → quantity` cumple.

## Decisión

1. **Principio:** cada verificador **recalcula** el valor corregido a partir de `original` y de los parámetros, exige igualdad exacta con `proposed`, y **rechaza toda entrada que admita más de una interpretación razonable**, independientemente de la confianza. La tabla completa está en `02` §4.
2. Reglas concretas: ambigüedad de miles en `PARSE_NUMBER` (`AMBIGUOUS_NUMBER`); solo `£` es removible y solo en `unit_price` (`CURRENCY_NOT_CONVERTIBLE`); `PARSE_DATE` exige hora y minuto y prueba el patrón con día/mes intercambiados (`AMBIGUOUS_DATE`); `NORMALIZE_CASE` fuera de `stock_code`.
3. `MAP_TO_ENUM` se verifica contra un archivo versionado `country-aliases.v1.yaml` (códigos ISO 3166 alfa-2/alfa-3, nombres nativos y variantes curadas) o por igualdad tras normalización (minúsculas, sin acentos ni signos). Un par no verificable queda en cuarentena con la sugerencia de la IA (`UNVERIFIABLE_MAPPING`).
4. El mapeo de cabeceras por IA se verifica validando una muestra de filas con el mapeo propuesto (AC-05.7).
5. Una **suite adversarial** de tests (AC-10.6) alimenta respuestas plausibles pero incorrectas con `severity = LOW` y `confidence = 0.99` y exige que ninguna llegue a `sales_transaction`.

## Alternativas consideradas

| Alternativa | Pros | Contras | Motivo de descarte |
|---|---|---|---|
| Mantener los verificadores originales y subir los umbrales de confianza | Sin trabajo extra | La confianza no está calibrada: subir el umbral no filtra errores confiados | Contradice ADR-0003 |
| `MAP_TO_ENUM` sin archivo de alias, solo similitud de texto (Levenshtein) | Más "IA-friendly" | `Austria`/`Australia` son muy similares; la similitud premia exactamente el error a evitar | Inseguro |
| Eliminar `MAP_TO_ENUM` de la allowlist | Máxima seguridad | Pierde un caso de demo muy visible (`UK` → `United Kingdom`) | Recorta valor sin necesidad |
| LLM como juez de su propia corrección | Sin archivos curados | Mismo modelo, mismos sesgos; costo doble (ya descartado en ADR-0003) | — |

## Consecuencias

- Positivas: la afirmación "0 correcciones incorrectas" pasa a estar respaldada por evidencia (suite adversarial), no solo por el mock; responde por adelantado a la pregunta más probable de un evaluador técnico.
- Negativas / trade-offs aceptados: menor tasa de auto-corrección con LLM real (alias no previstos, números con miles ambiguos van a cuarentena). El valor del LLM en `MAP_TO_ENUM` se desplaza de "aplicar" a "sugerir y explicar" cuando el alias no está curado. Esfuerzo estimado: +3 h en el Sprint 4 y +0.5 h en el Sprint 1.
- Documentos actualizados: `02` §2, §4, FR-05, FR-09, FR-10; `04` §7, §11; `06` A.1; `10` §2–§3; `09` §2, §7; `07` Sprints 1 y 4.

## Criterio de revisión

Si la evaluación con LLM real muestra que una parte significativa de los defectos corregibles termina en cuarentena por `UNVERIFIABLE_MAPPING` o `AMBIGUOUS_*`, reconsiderar ampliar `country-aliases` (con ADR) antes que relajar los verificadores.
