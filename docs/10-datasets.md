# 10 — Datasets y estrategia de datos "sucios"

## 1. Estrategia

Se combinan **dos tipos de datos**, porque cada uno demuestra algo distinto:

| Tipo | Qué demuestra | *Ground truth* |
|---|---|---|
| **A. Suciedad controlada** — baseline real limpio + defectos inyectados con semilla | Que la auto-corrección es **correcta y medible** (FR-20) | Sí (`labels.csv`) |
| **B. Suciedad natural** — muestra cruda del dataset real sin filtrar | Que el sistema se comporta de forma razonable con anomalías reales no diseñadas | No (se muestran como "hallazgos") |

La combinación evita la crítica habitual a los proyectos de demo ("solo funciona con los errores que tú mismo inventaste") y a la vez permite números objetivos.

## 2. Dataset principal (MVP): Online Retail II

| Atributo | Valor |
|---|---|
| Fuente | UCI Machine Learning Repository — <https://archive.ics.uci.edu/dataset/502/online+retail+ii> |
| Licencia | CC BY 4.0 (citar la fuente en el README) |
| Contenido | ~1 067 000 líneas de transacciones de un minorista online del Reino Unido, 01/12/2009 – 09/12/2011 |
| Formato | Excel (.xlsx) con dos hojas (2009–2010 y 2010–2011) |
| Columnas del archivo | `Invoice`, `StockCode`, `Description`, `Quantity`, `InvoiceDate`, `Price`, `Customer ID`, `Country` |

> ⚠️ La ficha de UCI lista los nombres de variables de la versión anterior (`InvoiceNo`, `UnitPrice`, `CustomerID`). **En el Sprint 1 se verifican las cabeceras reales del archivo descargado** y el schema v1 se alinea con ellas. Esa misma diferencia de nombres entre versiones es la base del escenario de *schema drift* (FR-05).

### Por qué este dataset

- Dominio universal y fácil de explicar (ventas).
- **Suciedad natural real y documentada:** ~20–25 % sin `Customer ID`; cantidades negativas (cancelaciones con prefijo `C`, y ajustes de inventario sin prefijo); precios negativos en facturas de ajuste (`A...`); códigos de stock no estándar (`POST`, `DOT`, `BANK CHARGES`, `M`, códigos en minúscula); descripciones vacías o de texto libre; países como `EIRE`, `RSA`, `Unspecified`, `European Community`.
- **Schema drift real:** la versión anterior del dataset (*Online Retail*, UCI id 352, CC BY 4.0, ~541 000 filas 2010–2011) usa `InvoiceNo`, `UnitPrice`, `CustomerID`. Procesar ese archivo con el schema v1 es un caso real de drift, no inventado.
- Reglas de negocio naturales (BR-01/BR-02) que producen casos `CRITICAL` legítimos.

### Preparación (Sprint 1)

`tools/download_dataset.py`:
1. Descarga el ZIP/XLSX a `data/raw/` (ignorado por git). Si la descarga automática falla, el README indica descargarlo manualmente.
2. Convierte ambas hojas a CSV UTF-8 **sin BOM**, finales de línea `\n`, con `InvoiceDate` en `yyyy-MM-dd HH:mm:ss`, `Price` con punto decimal y `Customer ID` como entero sin decimales (Excel lo almacena como número: `13085.0` → `13085`; si no se convierte, **todo** el baseline fallaría `PATTERN_MISMATCH`). <!-- rev: R-27 -->
3. Perfilado: nulos por columna, patrones de `Invoice` y `StockCode` (incluida la presencia de códigos que solo difieren en mayúsculas), países distintos, rangos de `Quantity`/`Price`. Resultado en `docs/perfilado-dataset.md` (breve).
3 bis. **Orden obligatorio** (evita la definición circular del catálogo, ver `02` §2): (a) `countries.v1.txt` = países distintos del dataset completo menos exclusiones justificadas en el perfilado; (b) `country-aliases.v1.yaml` con las variantes que usará el generador (`UK`, `Deutschland`, `Ireland`, ...) más códigos ISO 3166 alfa-2/alfa-3; (c) congelar `sales_transaction.v1.yaml`; (d) recién entonces construir el baseline. <!-- rev: R-02, R-06 -->
4. **Baseline limpio**: filas que pasan el schema v1 (después de congelarlo). Se muestrean con semilla fija:
   - `clean-1k.csv`, `clean-10k.csv`, `clean-100k.csv` (este último solo local para performance, no se commitea).
5. **Muestra natural**: 2 000 filas crudas **sin filtrar** → `raw-natural-2k.csv` (tipo B).
6. **Drift**: 1 000 filas del dataset *Online Retail* original con sus cabeceras → `drift-legacy-headers.csv`.

## 3. Catálogo de defectos inyectados (tipo A)

`tools/dirty_data_generator` aplica estos defectos sobre el baseline. Cada defecto tiene un **resultado esperado** que es la base de la evaluación. El proveedor `mock` DEBE cubrir todos (regla 7.4 de `09`) **sin leer este catálogo ni `labels.csv`**: el mock infiere a partir de la solicitud (AC-17.2). Las precondiciones y garantías del generador están en AC-19.4. <!-- rev: R-26, R-27 -->

| `defect_type` | Campo | Ejemplo (limpio → sucio) | `errorCode` esperado | Resultado esperado | Operación esperada |
|---|---|---|---|---|---|
| `WHITESPACE` | `stock_code`, `invoice_no`, `country` | `85123A` → `"  85123A "` (solo U+0020/tab) | `PATTERN_MISMATCH` / `INVALID_ENUM_VALUE` | `AUTO_CORRECTED` | `TRIM` |
| `CASE` | `country` | `United Kingdom` → `united kingdom` | `INVALID_ENUM_VALUE` | `AUTO_CORRECTED` | `NORMALIZE_CASE` |
| `DATE_DMY_UNAMBIGUOUS` | `invoice_date` | `2010-12-25 08:26:00` → `25/12/2010 08:26` (día > 12) | `INVALID_FORMAT` | `AUTO_CORRECTED` | `PARSE_DATE` |
| `DATE_ISO_T` | `invoice_date` | `2010-12-25 08:26:00` → `2010-12-25T08:26:00` (día > 12 o día = mes; con otro día también parsea `yyyy-dd-MM` y sería `AMBIGUOUS_DATE`) | `INVALID_FORMAT` | `AUTO_CORRECTED` | `PARSE_DATE` |
| `DATE_AMBIGUOUS` | `invoice_date` | `2010-04-03 09:15:00` → `03/04/2010 09:15` (día y mes ≤ 12 **y día ≠ mes**; con día = mes la fecha no es ambigua) | `INVALID_FORMAT` | `QUARANTINED` | — |
| `DECIMAL_COMMA` | `unit_price` | `2.55` → `2,55` | `INVALID_FORMAT` | `AUTO_CORRECTED` | `PARSE_NUMBER` |
| `CURRENCY_SYMBOL` | `unit_price` | `2.55` → `£2.55` (solo `£`; `$`/`€` implicarían conversión de moneda y no son corregibles, `02` §4) | `INVALID_FORMAT` | `AUTO_CORRECTED` | `PARSE_NUMBER` |
| `FLOAT_ID` | `customer_id` | `17850` → `17850.0` | `PATTERN_MISMATCH` | `AUTO_CORRECTED` | `PARSE_NUMBER` |
| `COUNTRY_SYNONYM` | `country` | `United Kingdom` → `UK`; `Germany` → `Deutschland`; `EIRE` → `Ireland` (solo pares presentes en `country-aliases.v1.yaml`) | `INVALID_ENUM_VALUE` | `AUTO_CORRECTED` | `MAP_TO_ENUM` |
| `NULL_TOKEN_OPTIONAL` | `customer_id` | `""` → `N/A` (solo filas sin cliente; se espera `null`, que coincide con `clean_value`) | `PATTERN_MISMATCH` | `AUTO_CORRECTED` | `NULLIFY_TOKEN` |
| `NULL_TOKEN_REQUIRED` | `unit_price`, `quantity` | `2.55` → `N/A` | `MISSING_REQUIRED_FIELD` | `QUARANTINED` | — |
| `MISSING_REQUIRED` | `unit_price`, `invoice_date`, `country` | → `""` | `MISSING_REQUIRED_FIELD` | `QUARANTINED` | — |
| `NEGATIVE_PRICE` | `unit_price` | `2.55` → `-2.55` | `OUT_OF_RANGE` | `QUARANTINED` | — |
| `QUANTITY_OUTLIER` | `quantity` | `6` → `999999` | `OUT_OF_RANGE` | `QUARANTINED` | — |
| `GARBAGE_NUMBER` | `quantity` | `6` → `seis` / `6x` | `INVALID_TYPE` | `QUARANTINED` | — |
| `FUTURE_DATE` | `invoice_date` | → `2031-01-01 10:00:00` | `OUT_OF_RANGE` | `QUARANTINED` | — |
| `CANCEL_SIGN_MISMATCH` | registro | `C536379` con `Quantity = 5` | `BUSINESS_RULE_VIOLATION` | `QUARANTINED` | — |
| `COLUMN_SHIFT` | registro | coma extra sin comillas en `Description` | `MALFORMED_ROW` | `QUARANTINED` | — |
| `COMBINED_FIXABLE` | varios | `DATE_DMY_UNAMBIGUOUS` + `CURRENCY_SYMBOL` + `COUNTRY_SYNONYM` en la misma fila | varios | `AUTO_CORRECTED` | varias |
| `COMBINED_MIXED` | varios | un defecto corregible + uno no corregible | varios | `QUARANTINED` (todo o nada) | — |

Distribución por defecto: 5 % de filas afectadas; ~60 % de los defectos corregibles y ~40 % no corregibles; semilla `42`. Configurable por CLI (`--rate`, `--seed`, `--defects`).

### Formato de `*.labels.csv`

```csv
source_row_number,defect_type,field,clean_value,dirty_value,expected_error_code,expected_outcome,expected_operation
152,DATE_DMY_UNAMBIGUOUS,invoice_date,2010-12-25 08:26:00,25/12/2010 08:26,INVALID_FORMAT,AUTO_CORRECTED,PARSE_DATE
377,CANCEL_SIGN_MISMATCH,_record,-24,24,BUSINESS_RULE_VIOLATION,QUARANTINED,
```

<!-- rev: R-27 -->
- Una fila puede tener varias entradas (defectos combinados), con **a lo sumo una por campo**. Las filas no listadas son limpias y se espera `DIRECT`.
- `expected_outcome` es el mismo en todas las entradas de una fila (el resultado es por registro, todo o nada).
- CSV con comillas RFC 4180 (los valores sucios pueden contener comas y espacios iniciales/finales, que deben preservarse).
- `dirty-1k.json` se genera con su propio `dirty-1k.json.labels.csv` y **sin** `COLUMN_SHIFT` (no existe en JSON); `source_row_number` = índice en el array + 1 (AC-02.4).

## 4. Datasets alternativos / de fase 2

| Dataset | Fuente | Por qué es útil | Consideraciones |
|---|---|---|---|
| **Online Retail** (versión original) | UCI, id 352 | Escenario de *schema drift* real respecto a Online Retail II | CC BY 4.0 |
| **Cafe Sales – Dirty Data for Cleaning Training** | Kaggle (ahmedmohamed2003) | 10 000 filas sintéticas ya sucias (`ERROR`, `UNKNOWN`, vacíos, tipos mezclados); buen segundo schema de demo | Verificar licencia en la página del dataset; no trae *ground truth* por celda |
| **Brazilian E-Commerce (Olist)** | Kaggle (olistbr) | Multi-tabla, fechas y textos en portugués, relaciones entre entidades | Licencia no comercial (verificar); más complejo que el MVP |
| **NYC TLC Trip Record Data** | nyc.gov (TLC) | Volumen alto, anomalías reales (tarifas negativas, distancias 0, fechas fuera de rango), cambios de schema entre años | Formato Parquet; archivos grandes; bueno para pruebas de performance |
| **Chicago Food Inspections** | Chicago Data Portal | Texto libre, direcciones inconsistentes, categorías con variantes | Schema muy distinto; para una fase 2 multi-schema |
| **Generación sintética** | Faker / Mimesis (Python) | Control total de volumen y defectos | Menos creíble que datos reales; útil para tests unitarios |

**Regla:** el MVP usa **solo** Online Retail II (+ Online Retail original para drift). Agregar otro dataset como fuente de demo es fase 2 y requiere ADR (implica un segundo schema canónico).

## 5. Qué se commitea

| Archivo | ¿Git? | Tamaño aprox. |
|---|---|---|
| `data/samples/clean-1k.csv` | Sí | ~90 KB |
| `data/samples/dirty-1k.csv` + `.labels.csv` | Sí | ~100 KB |
| `data/samples/dirty-10k.csv` + `.labels.csv` | Sí | ~1 MB |
| `data/samples/dirty-1k.json` + `.labels.csv` | Sí | ~200 KB |
| `data/samples/drift-legacy-headers.csv` | Sí | ~90 KB |
| `data/samples/raw-natural-2k.csv` | Sí | ~180 KB |
| `data/raw/*` (xlsx, csv completos, 100k) | No (`.gitignore`) | 40+ MB |

Las muestras se regeneran de forma reproducible con `tools/` (misma semilla ⇒ mismos archivos, AC-19.3). **Nunca se editan a mano**, ni las muestras ni sus `labels`: si un test falla contra ellas, se corrige el código o el generador, no los datos (`09` §2.3).

## 6. Atribución (para el README)

> Este proyecto utiliza el dataset *Online Retail II* (Chen, D., 2012), disponible en el UCI Machine Learning Repository bajo licencia CC BY 4.0. Los defectos de los archivos `dirty-*` fueron inyectados sintéticamente por el generador del proyecto.
