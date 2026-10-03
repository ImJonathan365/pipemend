#!/usr/bin/env python
"""Prepare the Online Retail II dataset: download, convert to CSV and profile it.

Implements docs/10 section 2. Three subcommands, meant to be run in order:

    download  fetch both UCI archives into data/raw/ (git-ignored)
    convert   turn the .xlsx sheets into canonical CSV, also in data/raw/
    profile   measure the CSV and write docs/perfilado-dataset.md

The profiling report is the evidence used to decide the country-catalogue exclusions, the
alias list and the final field rules before sales_transaction.v1.yaml is frozen, in the
mandatory order of docs/10 section 2, step 3 bis. The samples in data/samples/ are built
afterwards, once the schema exists.

Nothing here validates records: the authority on validation is the Java validator in
pipeline-service, which reads the same declarative YAML schema.
"""

import argparse
import csv
import re
import shutil
import sys
import urllib.request
import zipfile
from collections import Counter
from datetime import datetime
from decimal import Decimal
from pathlib import Path

from openpyxl import load_workbook

REPO_ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = REPO_ROOT / "data" / "raw"
PROFILE_REPORT = REPO_ROOT / "docs" / "perfilado-dataset.md"

HEADERS = [
    "Invoice",
    "StockCode",
    "Description",
    "Quantity",
    "InvoiceDate",
    "Price",
    "Customer ID",
    "Country",
]

LEGACY_HEADERS = [
    "InvoiceNo",
    "StockCode",
    "Description",
    "Quantity",
    "InvoiceDate",
    "UnitPrice",
    "CustomerID",
    "Country",
]

class Source:
    def __init__(self, key: str, url: str, xlsx: str, csv_name: str, headers: list[str]) -> None:
        self.key = key
        self.url = url
        self.xlsx = RAW_DIR / xlsx
        self.csv = RAW_DIR / csv_name
        self.headers = headers


CURRENT = Source(
    key="online-retail-ii",
    url="https://archive.ics.uci.edu/static/public/502/online+retail+ii.zip",
    xlsx="online_retail_ii.xlsx",
    csv_name="online_retail_ii.csv",
    headers=HEADERS,
)

LEGACY = Source(
    key="online-retail-legacy",
    url="https://archive.ics.uci.edu/static/public/352/online+retail.zip",
    xlsx="online_retail_legacy.xlsx",
    csv_name="online_retail_legacy.csv",
    headers=LEGACY_HEADERS,
)
SOURCES = [CURRENT, LEGACY]

def download(force: bool = False) -> None:
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    for source in SOURCES:
        if source.xlsx.exists() and not force:
            print(f"  {source.xlsx.name}: already present, skipping")
            continue
        archive = RAW_DIR / f"{source.key}.zip"
        print(f"  downloading {source.url}")
        try:
            urllib.request.urlretrieve(source.url, archive)
        except OSError as exc:
            print(
                f"\nERROR: could not download {source.url}\n  {exc}\n\n"
                f"Download it by hand, unzip it and place the .xlsx at {source.xlsx}, "
                f"then re-run `convert`.",
                file=sys.stderr,
            )
            raise SystemExit(1) from exc

        with zipfile.ZipFile(archive) as zf:
            members = [n for n in zf.namelist() if n.lower().endswith(".xlsx")]
            if len(members) != 1:
                raise SystemExit(f"expected exactly one .xlsx in {archive}, found {members}")
            with zf.open(members[0]) as src, source.xlsx.open("wb") as dst:
                shutil.copyfileobj(src, dst)
        archive.unlink()
        print(f"  {source.xlsx.name}: {source.xlsx.stat().st_size} bytes")

def _cell_to_text(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, datetime):
        return value.strftime("%Y-%m-%d %H:%M:%S")
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        dec = Decimal(repr(value))
        if dec == dec.to_integral_value():
            return str(dec.to_integral_value())
        return format(dec.normalize(), "f")
    return str(value)


def _convert_source(source: Source) -> int:
    if not source.xlsx.exists():
        raise SystemExit(f"{source.xlsx} not found - run `download` first")

    workbook = load_workbook(source.xlsx, read_only=True, data_only=True)
    written = 0
    # newline="" is required by the csv module; \n keeps the line endings stable across platforms
    # and utf-8 (not utf-8-sig) keeps the file free of a BOM, which would fake a schema drift
    # on the first header (AC-01.7).
    with source.csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n", quoting=csv.QUOTE_MINIMAL)
        writer.writerow(source.headers)
        for sheet_name in workbook.sheetnames:
            sheet = workbook[sheet_name]
            rows = sheet.iter_rows(values_only=True)
            header_row = next(rows, None)
            if header_row is None:
                continue
            found = [str(cell).strip() if cell is not None else "" for cell in header_row]
            if found != source.headers:
                raise SystemExit(
                    f"unexpected headers in sheet {sheet_name!r} of {source.xlsx.name}:\n"
                    f"  found:    {found}\n  expected: {source.headers}\n"
                    "The schema is pinned to these headers (docs/02 section 2); a change here is "
                    "a dataset change and needs an owner decision, not a silent remap."
                )
            for row in rows:
                if all(cell is None for cell in row):
                    continue
                writer.writerow([_cell_to_text(cell) for cell in row])
                written += 1
        workbook.close()
    print(f"  {source.csv.name}: {written} data rows")
    return written


def convert() -> None:
    for source in SOURCES:
        _convert_source(source)


INVOICE_RE = re.compile(r"^[CA]?\d{6}$")
STOCK_CODE_RE = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9_ \-]{0,18}[A-Za-z0-9_])?$")
CUSTOMER_ID_RE = re.compile(r"^\d{5}$")
NULL_TOKENS = {"", "N/A", "NA", "null", "NULL", "None", "-", "?"}
DATE_FORMAT = "%Y-%m-%d %H:%M:%S"
MIN_DATE = datetime(2009, 1, 1)


class Profile:
    def __init__(self) -> None:
        self.rows = 0
        self.empty: Counter[str] = Counter()
        self.null_token: Counter[str] = Counter()
        self.countries: Counter[str] = Counter()
        self.invoice_bad: Counter[str] = Counter()
        self.invoice_prefix: Counter[str] = Counter()
        self.stock_bad: Counter[str] = Counter()
        self.stock_lengths: Counter[int] = Counter()
        self.stock_seen: dict[str, set[str]] = {}
        self.customer_bad: Counter[str] = Counter()
        self.price_scale: Counter[int] = Counter()
        self.price_negative = 0
        self.quantity_zero = 0
        self.quantity_min: int | None = None
        self.quantity_max: int | None = None
        self.price_min: Decimal | None = None
        self.price_max: Decimal | None = None
        self.date_min: datetime | None = None
        self.date_max: datetime | None = None
        self.description_over_255 = 0
        self.description_untrimmed = 0
        self.br01_violations = 0
        self.br02_violations = 0
        self.unparsable: Counter[str] = Counter()

    def feed(self, row: dict[str, str]) -> None:
        self.rows += 1

        for field, value in row.items():
            if value == "":
                self.empty[field] += 1
            elif value.strip() in NULL_TOKENS:
                self.null_token[field] += 1

        invoice = row["Invoice"]
        if not INVOICE_RE.match(invoice):
            self.invoice_bad[invoice] += 1
        self.invoice_prefix[invoice[:1] if invoice[:1].isalpha() else "(digit)"] += 1

        stock = row["StockCode"]
        self.stock_lengths[len(stock)] += 1
        if not STOCK_CODE_RE.match(stock):
            self.stock_bad[stock] += 1
        # Codes that differ only in case are different products in this dataset, which is why
        # NORMALIZE_CASE is forbidden on stock_code (ADR-0008).
        self.stock_seen.setdefault(stock.casefold(), set()).add(stock)

        description = row["Description"]
        if len(description) > 255:
            self.description_over_255 += 1
        if description != description.strip():
            self.description_untrimmed += 1

        quantity: int | None = None
        try:
            quantity = int(row["Quantity"])
        except ValueError:
            self.unparsable["Quantity"] += 1
        else:
            if quantity == 0:
                self.quantity_zero += 1
            self.quantity_min = (
                quantity if self.quantity_min is None else min(self.quantity_min, quantity)
            )
            self.quantity_max = (
                quantity if self.quantity_max is None else max(self.quantity_max, quantity)
            )

        try:
            price = Decimal(row["Price"])
        except ArithmeticError:
            self.unparsable["Price"] += 1
        else:
            self.price_scale[max(0, -price.as_tuple().exponent)] += 1  # type: ignore[operator]
            if price < 0:
                self.price_negative += 1
            self.price_min = price if self.price_min is None else min(self.price_min, price)
            self.price_max = price if self.price_max is None else max(self.price_max, price)

        try:
            moment = datetime.strptime(row["InvoiceDate"], DATE_FORMAT)
        except ValueError:
            self.unparsable["InvoiceDate"] += 1
        else:
            self.date_min = moment if self.date_min is None else min(self.date_min, moment)
            self.date_max = moment if self.date_max is None else max(self.date_max, moment)

        customer = row["Customer ID"]
        if customer != "" and not CUSTOMER_ID_RE.match(customer):
            self.customer_bad[customer] += 1

        self.countries[row["Country"]] += 1

        # BR-01 / BR-02 are only meaningful when both fields parsed.
        if quantity is not None and INVOICE_RE.match(invoice):
            if invoice.startswith("C") and quantity >= 0:
                self.br01_violations += 1
            elif not invoice.startswith("C") and quantity <= 0:
                self.br02_violations += 1


def _pct(part: int, total: int) -> str:
    return f"{(100 * part / total):.2f} %" if total else "n/a"


def profile() -> None:
    if not CURRENT.csv.exists():
        raise SystemExit(f"{CURRENT.csv} not found - run `convert` first")

    data = Profile()
    with CURRENT.csv.open(encoding="utf-8", newline="") as handle:
        for row in csv.DictReader(handle):
            data.feed(row)

    case_collisions = {k: v for k, v in data.stock_seen.items() if len(v) > 1}
    lines: list[str] = []
    add = lines.append

    add("# Perfilado del dataset — Online Retail II")
    add("")
    add(
        f"Generado por `tools/download_dataset.py profile` el "
        f"{datetime.now().strftime('%Y-%m-%d')} sobre `data/raw/{CURRENT.csv.name}` "
        f"({data.rows} filas de datos)."
    )
    add("")
    add(
        "> Este documento es la **evidencia** con la que se deciden las exclusiones del catálogo "
        "de países, la lista de alias y las reglas finales de campo antes de congelar "
        "`sales_transaction.v1.yaml`, en el orden obligatorio de `10` §2 paso 3 bis. "
        "Los números no se editan a mano: se regeneran con el script (`09` §2.3)."
    )
    add("")

    add("## 1. Cabeceras reales")
    add("")
    add(f"Hojas: `Year 2009-2010` y `Year 2010-2011`. Cabeceras: `{'`, `'.join(HEADERS)}`.")
    add("")
    add(
        "Coinciden exactamente con `02` §2. La ficha de UCI publica los nombres de la versión "
        f"anterior (`{'`, `'.join(LEGACY_HEADERS)}`), que es justamente el caso de *schema "
        "drift* de FR-05; esa versión se conserva en "
        f"`data/raw/{LEGACY.csv.name}` para construir `drift-legacy-headers.csv`."
    )
    add("")

    add("## 2. Ausencias por columna")
    add("")
    add("| Columna | Vacías | % | Token nulo (`N/A`, `-`, ...) |")
    add("|---|---:|---:|---:|")
    for field in HEADERS:
        add(
            f"| `{field}` | {data.empty[field]} | {_pct(data.empty[field], data.rows)} "
            f"| {data.null_token[field]} |"
        )
    add("")

    add("## 3. `Invoice`")
    add("")
    add(
        f"- Filas que cumplen `^[CA]?\\d{{6}}$`: {data.rows - sum(data.invoice_bad.values())} "
        f"({_pct(data.rows - sum(data.invoice_bad.values()), data.rows)})."
    )
    add(
        f"- Filas que no lo cumplen: {sum(data.invoice_bad.values())} "
        f"({len(data.invoice_bad)} valores distintos)."
    )
    if data.invoice_bad:
        sample = ", ".join(f"`{v}` x{n}" for v, n in data.invoice_bad.most_common(10))
        add(f"- Ejemplos: {sample}.")
    add(
        "- Prefijos observados: "
        + ", ".join(f"`{p}` x{n}" for p, n in sorted(data.invoice_prefix.items()))
        + "."
    )
    add("")

    add("## 4. `StockCode`")
    add("")
    add(f"- Longitudes: mín. {min(data.stock_lengths)}, máx. {max(data.stock_lengths)}.")
    add(
        f"- Filas que no cumplen la regex propuesta: {sum(data.stock_bad.values())} "
        f"({len(data.stock_bad)} valores distintos)."
    )
    if data.stock_bad:
        sample = ", ".join(f"`{v}` x{n}" for v, n in data.stock_bad.most_common(15))
        add(f"- Ejemplos: {sample}.")
    add("")
    add(
        f"**Códigos que solo difieren en mayúsculas/minúsculas: {len(case_collisions)} grupos.** "
        "Es la evidencia de que `NORMALIZE_CASE` está prohibido en `stock_code` (ADR-0008): "
        "cambiar la caja cambia de producto."
    )
    if case_collisions:
        sample = "; ".join(
            " / ".join(sorted(f"`{s}`" for s in variants))
            for variants in list(case_collisions.values())[:10]
        )
        add(f"Ejemplos: {sample}.")
    add("")

    add("## 5. `Quantity` y `Price`")
    add("")
    add(
        f"- `Quantity`: rango [{data.quantity_min}, {data.quantity_max}]; "
        f"valores 0: {data.quantity_zero}."
    )
    add(f"- `Price`: rango [{data.price_min}, {data.price_max}]; negativos: {data.price_negative}.")
    add(
        "- Decimales de `Price`: "
        + ", ".join(f"{k} → {v} filas" for k, v in sorted(data.price_scale.items()))
        + "."
    )
    over_two = sum(v for k, v in data.price_scale.items() if k > 2)
    add(
        f"- Filas con más de 2 decimales en `Price`: {over_two}. "
        "Con `decimal(12,2)` quedan fuera del baseline: el schema exige máximo 2 decimales y "
        "`02` §2 prohíbe redondear (sería `INVALID_FORMAT`)."
    )
    add("")

    add("## 6. `InvoiceDate`")
    add("")
    add(f"- Rango: {data.date_min} … {data.date_max}.")
    add(
        f"- Filas anteriores al mínimo del schema ({MIN_DATE:%Y-%m-%d}): "
        f"{'0' if data.date_min and data.date_min >= MIN_DATE else 'revisar'}."
    )
    add("")

    add("## 7. `Customer ID`")
    add("")
    add(f"- Vacías: {data.empty['Customer ID']} ({_pct(data.empty['Customer ID'], data.rows)}).")
    add(f"- No vacías que no cumplen `^\\d{{5}}$`: {sum(data.customer_bad.values())}.")
    if data.customer_bad:
        sample = ", ".join(f"`{v}` x{n}" for v, n in data.customer_bad.most_common(10))
        add(f"- Ejemplos: {sample}.")
    add("")

    add("## 8. `Description`")
    add("")
    add(f"- Más de 255 caracteres: {data.description_over_255}.")
    add(
        f"- Con espacios al inicio o final: {data.description_untrimmed} "
        "(suciedad natural; el MVP no normaliza texto libre, `02` §2)."
    )
    add("")

    add("## 9. Reglas de negocio")
    add("")
    add(f"- `BR-01` (factura `C...` debe tener `quantity < 0`) incumplida: {data.br01_violations}.")
    add(
        f"- `BR-02` (factura sin `C` debe tener `quantity > 0`) incumplida: {data.br02_violations}."
    )
    add("")

    add("## 10. Catálogo de países")
    add("")
    add(
        f"Valores distintos de `Country` en el dataset completo (ambas hojas): "
        f"**{len(data.countries)}**."
    )
    add("")
    add("| País | Filas | % |")
    add("|---|---:|---:|")
    for country, count in sorted(data.countries.items(), key=lambda kv: (-kv[1], kv[0])):
        add(f"| `{country}` | {count} | {_pct(count, data.rows)} |")
    add("")
    add("### Exclusiones propuestas")
    add("")
    add(
        "`02` §2 define `countries.v1.txt` como estos valores distintos **menos** las exclusiones "
        "decididas aquí, cada una con su motivo. Candidatos nombrados por el documento:"
    )
    add("")
    add("| Candidato | Filas | Motivo propuesto |")
    add("|---|---:|---|")
    for candidate in ("Unspecified", "European Community"):
        candidate_rows = data.countries.get(candidate)
        present = str(candidate_rows) if candidate_rows is not None else "no aparece"
        add(
            f"| `{candidate}` | {present} | No es un país: es un marcador de dato ausente o un "
            "bloque supranacional. Si entrara al catálogo, una fila sin país real pasaría la "
            "validación como válida en lugar de ir a cuarentena. |"
        )
    add("")

    if data.unparsable:
        add("## 11. Celdas no interpretables")
        add("")
        for field, count in data.unparsable.items():
            add(f"- `{field}`: {count}.")
        add("")

    PROFILE_REPORT.parent.mkdir(parents=True, exist_ok=True)
    PROFILE_REPORT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"  {PROFILE_REPORT.relative_to(REPO_ROOT)}: {len(lines)} lines")
    print(
        f"  rows={data.rows} countries={len(data.countries)} "
        f"stock_case_collisions={len(case_collisions)}"
    )


# --------------------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    download_parser = sub.add_parser("download", help="fetch both UCI archives into data/raw/")
    download_parser.add_argument(
        "--force", action="store_true", help="re-download even if the .xlsx is already there"
    )
    sub.add_parser("convert", help="convert the .xlsx sheets to canonical CSV")
    sub.add_parser("profile", help="write docs/perfilado-dataset.md from the CSV")
    sub.add_parser("all", help="download, convert and profile in order")

    args = parser.parse_args()
    if args.command == "download":
        download(force=args.force)
    elif args.command == "convert":
        convert()
    elif args.command == "profile":
        profile()
    else:
        download()
        convert()
        profile()


if __name__ == "__main__":
    main()
