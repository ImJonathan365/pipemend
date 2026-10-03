# Perfilado del dataset — Online Retail II

Generado por `tools/download_dataset.py profile` el 2026-10-02 sobre `data/raw/online_retail_ii.csv` (1067371 filas de datos).

> Este documento es la **evidencia** con la que se deciden las exclusiones del catálogo de países, la lista de alias y las reglas finales de campo antes de congelar `sales_transaction.v1.yaml`, en el orden obligatorio de `10` §2 paso 3 bis. Los números no se editan a mano: se regeneran con el script (`09` §2.3).

## 1. Cabeceras reales

Hojas: `Year 2009-2010` y `Year 2010-2011`. Cabeceras: `Invoice`, `StockCode`, `Description`, `Quantity`, `InvoiceDate`, `Price`, `Customer ID`, `Country`.

Coinciden exactamente con `02` §2. La ficha de UCI publica los nombres de la versión anterior (`InvoiceNo`, `StockCode`, `Description`, `Quantity`, `InvoiceDate`, `UnitPrice`, `CustomerID`, `Country`), que es justamente el caso de *schema drift* de FR-05; esa versión se conserva en `data/raw/online_retail_legacy.csv` para construir `drift-legacy-headers.csv`.

## 2. Ausencias por columna

| Columna | Vacías | % | Token nulo (`N/A`, `-`, ...) |
|---|---:|---:|---:|
| `Invoice` | 0 | 0.00 % | 0 |
| `StockCode` | 0 | 0.00 % | 0 |
| `Description` | 4382 | 0.41 % | 92 |
| `Quantity` | 0 | 0.00 % | 0 |
| `InvoiceDate` | 0 | 0.00 % | 0 |
| `Price` | 0 | 0.00 % | 0 |
| `Customer ID` | 243007 | 22.77 % | 0 |
| `Country` | 0 | 0.00 % | 0 |

## 3. `Invoice`

- Filas que cumplen `^[CA]?\d{6}$`: 1067371 (100.00 %).
- Filas que no lo cumplen: 0 (0 valores distintos).
- Prefijos observados: `(digit)` x1047871, `A` x6, `C` x19494.

## 4. `StockCode`

- Longitudes: mín. 1, máx. 12.
- Filas que no cumplen la regex propuesta: 1 (1 valores distintos).
- Ejemplos: `47503J ` x1.

**Códigos que solo difieren en mayúsculas/minúsculas: 173 grupos.** Es la evidencia de que `NORMALIZE_CASE` está prohibido en `stock_code` (ADR-0008): cambiar la caja cambia de producto.
Ejemplos: `48173C` / `48173c`; `84596F` / `84596f`; `84596L` / `84596l`; `84970S` / `84970s`; `84031A` / `84031a`; `84031B` / `84031b`; `85132A` / `85132a`; `85132C` / `85132c`; `85014A` / `85014a`; `85014B` / `85014b`.

## 5. `Quantity` y `Price`

- `Quantity`: rango [-80995, 80995]; valores 0: 0.
- `Price`: rango [-53594.36, 38970]; negativos: 5.
- Decimales de `Price`: 0 → 10024 filas, 1 → 62493 filas, 2 → 994836 filas, 3 → 18 filas.
- Filas con más de 2 decimales en `Price`: 18. Con `decimal(12,2)` quedan fuera del baseline: el schema exige máximo 2 decimales y `02` §2 prohíbe redondear (sería `INVALID_FORMAT`).

## 6. `InvoiceDate`

- Rango: 2009-12-01 07:45:00 … 2011-12-09 12:50:00.
- Filas anteriores al mínimo del schema (2009-01-01): 0.

## 7. `Customer ID`

- Vacías: 243007 (22.77 %).
- No vacías que no cumplen `^\d{5}$`: 0.

## 8. `Description`

- Más de 255 caracteres: 0.
- Con espacios al inicio o final: 213035 (suciedad natural; el MVP no normaliza texto libre, `02` §2).

## 9. Reglas de negocio

- `BR-01` (factura `C...` debe tener `quantity < 0`) incumplida: 1.
- `BR-02` (factura sin `C` debe tener `quantity > 0`) incumplida: 3457.

## 10. Catálogo de países

Valores distintos de `Country` en el dataset completo (ambas hojas): **43**.

| País | Filas | % |
|---|---:|---:|
| `United Kingdom` | 981330 | 91.94 % |
| `EIRE` | 17866 | 1.67 % |
| `Germany` | 17624 | 1.65 % |
| `France` | 14330 | 1.34 % |
| `Netherlands` | 5140 | 0.48 % |
| `Spain` | 3811 | 0.36 % |
| `Switzerland` | 3189 | 0.30 % |
| `Belgium` | 3123 | 0.29 % |
| `Portugal` | 2620 | 0.25 % |
| `Australia` | 1913 | 0.18 % |
| `Channel Islands` | 1664 | 0.16 % |
| `Italy` | 1534 | 0.14 % |
| `Norway` | 1455 | 0.14 % |
| `Sweden` | 1364 | 0.13 % |
| `Cyprus` | 1176 | 0.11 % |
| `Finland` | 1049 | 0.10 % |
| `Austria` | 938 | 0.09 % |
| `Denmark` | 817 | 0.08 % |
| `Unspecified` | 756 | 0.07 % |
| `Greece` | 663 | 0.06 % |
| `Japan` | 582 | 0.05 % |
| `Poland` | 535 | 0.05 % |
| `USA` | 535 | 0.05 % |
| `United Arab Emirates` | 500 | 0.05 % |
| `Israel` | 371 | 0.03 % |
| `Hong Kong` | 364 | 0.03 % |
| `Singapore` | 346 | 0.03 % |
| `Malta` | 299 | 0.03 % |
| `Iceland` | 253 | 0.02 % |
| `Canada` | 228 | 0.02 % |
| `Lithuania` | 189 | 0.02 % |
| `RSA` | 169 | 0.02 % |
| `Bahrain` | 126 | 0.01 % |
| `Brazil` | 94 | 0.01 % |
| `Thailand` | 76 | 0.01 % |
| `Korea` | 63 | 0.01 % |
| `European Community` | 61 | 0.01 % |
| `Lebanon` | 58 | 0.01 % |
| `West Indies` | 54 | 0.01 % |
| `Bermuda` | 34 | 0.00 % |
| `Nigeria` | 32 | 0.00 % |
| `Czech Republic` | 30 | 0.00 % |
| `Saudi Arabia` | 10 | 0.00 % |

### Exclusiones propuestas

`02` §2 define `countries.v1.txt` como estos valores distintos **menos** las exclusiones decididas aquí, cada una con su motivo. Candidatos nombrados por el documento:

| Candidato | Filas | Motivo propuesto |
|---|---:|---|
| `Unspecified` | 756 | No es un país: es un marcador de dato ausente o un bloque supranacional. Si entrara al catálogo, una fila sin país real pasaría la validación como válida en lugar de ir a cuarentena. |
| `European Community` | 61 | No es un país: es un marcador de dato ausente o un bloque supranacional. Si entrara al catálogo, una fila sin país real pasaría la validación como válida en lugar de ir a cuarentena. |

