#!/usr/bin/env python3
"""Guard: display formatting goes through the shared formatter (design/format: AunFormat, aunString, aunPlural).

Owner decision 2026-11-15: in Arabic the apps show Eastern Arabic (Hindi) numerals ٠–٩ everywhere, independent of the
device's region or numbering settings. Platform formatters (String.format, NumberFormat, java.time localized
formatters, DateUtils, Formatter.formatFileSize, stringResource with arguments ...) pick digits from locale data, so
they are only allowed inside design/format. A line that formats something nobody sees (a hash, an id, a wire format)
carries the marker `display-format: not shown` on the same line or the line above.

Usage: scripts/check-display-format.py [repo root]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent)
ALLOWED_DIR = "design/src/main/kotlin/net/alummahnow/design/format/"
MARKER = "display-format: not shown"

LINE_RULES = [
    (re.compile(r"\bString\.format\(|\"\s*\.format\(|\.format\(\s*Locale\."), "String.format: use AunFormat"),
    (re.compile(r"\b(NumberFormat|DecimalFormat|CompactDecimalFormat|MeasureFormat|SimpleDateFormat|RelativeDateTimeFormatter)\b"),
     "platform number/date formatter: use AunFormat"),
    (re.compile(r"\bDateTimeFormatter\.ofLocalized|\bDateTimeFormatter\.ofPattern"), "java.time display formatter: use AunFormat.date/dateTime"),
    (re.compile(r"\bDateUtils\.(format|getRelative)"), "DateUtils: use AunFormat"),
    (re.compile(r"\bFormatter\.format(Short)?FileSize\b"), "file size: use fileSizeLabel"),
    (re.compile(r"\b(pluralStringResource|getQuantityString)\("), "plurals: use aunPlural"),
    (re.compile(r"localized(Digits|Decimal)\([^)]*(toString\(\)|\"\$)"), "a number as text: use numberLabel / AunFormat.number"),
]

# stringResource / getString with arguments: Android formats integers with the platform locale data.
CALL = re.compile(r"\b(stringResource|getString)\(")


def has_arguments(text: str, start: int) -> bool:
    depth = 0
    i = start
    while i < len(text):
        c = text[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return False
        elif c == "," and depth == 1:
            return True
        elif c == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
        i += 1
    return False


def allowed(lines: list[str], index: int) -> bool:
    return MARKER in lines[index] or (index > 0 and MARKER in lines[index - 1])


def main() -> int:
    problems = []
    for path in sorted(ROOT.glob("*/src/main/**/*.kt")):
        rel = path.relative_to(ROOT).as_posix()
        if rel.startswith(ALLOWED_DIR):
            continue
        text = path.read_text(encoding="utf-8")
        lines = text.split("\n")
        for n, line in enumerate(lines):
            if line.lstrip().startswith(("import ", "//", "*", "/*")) or allowed(lines, n):
                continue
            for pattern, message in LINE_RULES:
                if pattern.search(line):
                    problems.append(f"{rel}:{n + 1}: {message}\n    {line.strip()}")
        for match in CALL.finditer(text):
            n = text.count("\n", 0, match.start())
            if "prefs." in lines[n] or allowed(lines, n):
                continue
            if has_arguments(text, match.end() - 1):
                problems.append(f"{rel}:{n + 1}: {match.group(1)} with arguments: use aunString\n    {lines[n].strip()}")
    if problems:
        print("Display formatting must go through design/format (AunFormat, aunString, aunPlural):\n")
        print("\n".join(problems))
        return 1
    print("check-display-format: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
