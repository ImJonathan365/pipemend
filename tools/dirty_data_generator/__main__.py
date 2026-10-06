import argparse
from pathlib import Path

from dirty_data_generator.defects import ALL_TYPES
from dirty_data_generator.generator import REPO_ROOT, run

# The committed samples of docs/10 section 5; regenerate them with no arguments.
DEFAULT_JOBS = [
    (
        REPO_ROOT / "data" / "samples" / "clean-1k.csv",
        REPO_ROOT / "data" / "samples" / "dirty-1k.csv",
    ),
    (
        REPO_ROOT / "data" / "raw" / "clean-10k.csv",
        REPO_ROOT / "data" / "samples" / "dirty-10k.csv",
    ),
]


def main() -> None:
    parser = argparse.ArgumentParser(
        prog="dirty_data_generator",
        description="Inject seeded defects into a clean baseline (FR-19, docs/10 section 3).",
    )
    parser.add_argument("--input", type=Path, help="clean baseline CSV")
    parser.add_argument("--output", type=Path, help="dirty CSV; labels go next to it")
    parser.add_argument("--rate", type=float, default=0.05, help="share of rows with defects")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--defects", default=",".join(ALL_TYPES), help="comma-separated defect_type values"
    )
    args = parser.parse_args()

    if not 0 < args.rate <= 1:
        parser.error("--rate must be in (0, 1]")
    defect_types = [name.strip() for name in args.defects.split(",") if name.strip()]
    unknown = sorted(set(defect_types) - set(ALL_TYPES))
    if unknown or not defect_types:
        parser.error(f"unknown or empty --defects: {unknown}")
    if (args.input is None) != (args.output is None):
        parser.error("--input and --output go together")

    jobs = [(args.input, args.output)] if args.input else DEFAULT_JOBS
    for source, output in jobs:
        run(source, output, args.rate, args.seed, defect_types)


if __name__ == "__main__":
    main()
