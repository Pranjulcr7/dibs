# model/ — receipt-parser evaluation corpus

**Status: the VLM tier is dropped for v1** (SPEC §7). This directory now serves
one purpose: downloading public receipt datasets as a *layout-variety corpus*
to exercise the rule-based layout parser in `:core:domain` and find its bugs.

## The honest caveat

SROIE (~1k receipts) and CORD (~1k receipts) are **scanned Southeast Asian
receipts**. They do not predict performance on crumpled Western receipts
photographed at an angle in bad light — the actual v1 use case. Parse rates
measured here are a lower bound on layout-rule robustness, not an accuracy
claim. Real evaluation needs a held-out set of 100+ real photographed
receipts, which does not exist yet (see ISSUES.md #22).

## Datasets

| Dataset | License | What we use |
|---|---|---|
| CORD v2 (`naver-clova-ix/cord-v2`) | CC BY-SA 4.0 | Ground-truth word boxes + amounts |
| SROIE (ICDAR 2019 task 1/2) | MIT | Ground-truth OCR line boxes |

Raw images are **never committed** — `corpus/` is gitignored. Scripts download
on demand and export only text boxes + labels as JSON.

## Usage

```bash
pip install datasets pillow
python3 download_corpus.py --dataset cord --split test --limit 100
# writes corpus/cord/<n>.json: {"boxes": [{text,left,top,right,bottom}], "total": "..."}
```

Then run the JVM harness (skips automatically when corpus/ is absent):

```bash
DIBS_RECEIPT_CORPUS=$PWD/corpus ../gradlew -p .. :core:domain:test --tests '*CorpusEvaluation*'
```

It feeds the ground-truth boxes straight into `RuleBasedLayoutParser` —
deliberately bypassing OCR, because this corpus evaluates *layout rules*, not
text recognition — and reports items-found / total-found / gate-pass rates.
