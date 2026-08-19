#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Download receipt datasets and export ground-truth text boxes as JSON.

Images never touch the repo: only {text, box} tuples and the labeled total are
written, into the gitignored corpus/ directory. See README.md for the caveat
about what these datasets can and cannot tell us.
"""
import argparse
import json
import pathlib


def export_cord(split: str, limit: int, out_dir: pathlib.Path) -> int:
    from datasets import load_dataset  # pip install datasets

    ds = load_dataset("naver-clova-ix/cord-v2", split=split, streaming=True)
    count = 0
    for sample in ds:
        gt = json.loads(sample["ground_truth"])
        boxes = []
        for line in gt.get("valid_line", []):
            for word in line.get("words", []):
                quad = word["quad"]
                xs = [quad[k] for k in ("x1", "x2", "x3", "x4")]
                ys = [quad[k] for k in ("y1", "y2", "y3", "y4")]
                boxes.append({
                    "text": word["text"],
                    "left": min(xs), "top": min(ys),
                    "right": max(xs), "bottom": max(ys),
                })
        total = gt.get("gt_parse", {}).get("total", {}).get("total_price")
        out = {"boxes": boxes, "total": total}
        (out_dir / f"{count:04d}.json").write_text(json.dumps(out))
        count += 1
        if count >= limit:
            break
    return count


def export_sroie(split: str, limit: int, out_dir: pathlib.Path) -> int:
    import ast

    from datasets import load_dataset

    # Parquet mirror of ICDAR-2019 SROIE with task-1 OCR boxes and task-2
    # entity labels (the original darentang/sroie uses a loading script,
    # which recent `datasets` versions refuse). Train split only.
    ds = load_dataset("arvindrajan92/sroie_document_understanding", split="train", streaming=True)
    count = 0
    for sample in ds:
        ocr = sample["ocr"]
        if isinstance(ocr, str):
            ocr = ast.literal_eval(ocr)
        boxes = []
        total = None
        for entry in ocr:
            quad = entry["box"]
            xs = [p[0] for p in quad]
            ys = [p[1] for p in quad]
            boxes.append({
                "text": entry["text"],
                "left": int(min(xs)), "top": int(min(ys)),
                "right": int(max(xs)), "bottom": int(max(ys)),
            })
            if entry.get("label") == "total" and total is None:
                total = entry["text"]
        out = {"boxes": boxes, "total": total}
        (out_dir / f"{count:04d}.json").write_text(json.dumps(out))
        count += 1
        if count >= limit:
            break
    return count


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", choices=["cord", "sroie"], required=True)
    parser.add_argument("--split", default="test")
    parser.add_argument("--limit", type=int, default=100)
    args = parser.parse_args()

    out_dir = pathlib.Path(__file__).parent / "corpus" / args.dataset
    out_dir.mkdir(parents=True, exist_ok=True)
    exporter = export_cord if args.dataset == "cord" else export_sroie
    count = exporter(args.split, args.limit, out_dir)
    print(f"exported {count} receipts to {out_dir}")


if __name__ == "__main__":
    main()
