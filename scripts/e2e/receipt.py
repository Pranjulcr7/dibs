# SPDX-License-Identifier: Apache-2.0
"""Draws a plain, legible till receipt for the scan flow. Needs Pillow (`pip install pillow`).

A synthetic image, so the check is repeatable and contains nobody's data. It
proves the pipeline end to end (picker, on-device text recognition, layout
rules, arithmetic gate); it says nothing about accuracy on real crumpled
receipts, which docs/requirements.yaml tracks separately.
"""
from __future__ import annotations

LINES = [
    ("CORNER CAFE", ""),
    ("", ""),
    ("Latte", "4.50"),
    ("Bagel", "3.25"),
    ("Cookie", "5.00"),
    ("", ""),
    ("SUBTOTAL", "12.75"),
    ("TAX", "1.02"),
    ("TOTAL", "13.77"),
]


def _font(size: int):
    from PIL import ImageFont

    for path in (
        "/System/Library/Fonts/Menlo.ttc",
        "/System/Library/Fonts/Supplemental/Courier New.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
    ):
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default(size=size)


def write_receipt(path: str) -> None:
    from PIL import Image, ImageDraw

    width, row, margin = 900, 90, 70
    image = Image.new("RGB", (width, margin * 2 + row * len(LINES)), "white")
    draw = ImageDraw.Draw(image)
    font = _font(52)
    for index, (label, amount) in enumerate(LINES):
        y = margin + index * row
        draw.text((margin, y), label, fill="black", font=font)
        if amount:
            amount_width = draw.textlength(amount, font=font)
            draw.text((width - margin - amount_width, y), amount, fill="black", font=font)
    image.save(path)
