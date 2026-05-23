#!/usr/bin/env python3
import csv
import html
import json
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET


TEXT_EXTS = {".txt", ".md", ".markdown", ".log", ".json", ".yaml", ".yml", ".xml", ".html", ".htm", ".csv"}


def strip_tags(value: str) -> str:
    value = re.sub(r"(?is)<script.*?</script>", " ", value)
    value = re.sub(r"(?is)<style.*?</style>", " ", value)
    value = re.sub(r"(?s)<[^>]+>", " ", value)
    return re.sub(r"\s+", " ", html.unescape(value)).strip()


def read_text(path: Path) -> str:
    data = path.read_text(encoding="utf-8", errors="replace")
    if path.suffix.lower() in {".html", ".htm"}:
        return strip_tags(data)
    if path.suffix.lower() == ".json":
        try:
            return json.dumps(json.loads(data), ensure_ascii=False, indent=2)
        except Exception:
            return data
    if path.suffix.lower() == ".csv":
        rows = []
        with path.open("r", encoding="utf-8", errors="replace", newline="") as handle:
            for row in csv.reader(handle):
                rows.append("\t".join(row))
        return "\n".join(rows)
    return data


def xml_text(xml_bytes: bytes) -> str:
    root = ET.fromstring(xml_bytes)
    parts = []
    for element in root.iter():
        if element.text and element.text.strip():
            parts.append(element.text.strip())
    return "\n".join(parts)


def read_docx(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        names = [name for name in archive.namelist() if name.startswith("word/document") and name.endswith(".xml")]
        if not names:
            raise RuntimeError("not a readable docx document")
        return xml_text(archive.read(names[0]))


def read_xlsx(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        shared = []
        if "xl/sharedStrings.xml" in archive.namelist():
            shared_text = xml_text(archive.read("xl/sharedStrings.xml"))
            shared = shared_text.splitlines()

        rows = []
        sheet_names = sorted(name for name in archive.namelist() if name.startswith("xl/worksheets/sheet") and name.endswith(".xml"))
        for sheet in sheet_names:
            root = ET.fromstring(archive.read(sheet))
            rows.append(f"=== {sheet} ===")
            for row in root.iter():
                if not row.tag.endswith("}row"):
                    continue
                cells = []
                for cell in row:
                    if not cell.tag.endswith("}c"):
                        continue
                    cell_type = cell.attrib.get("t")
                    value = ""
                    for child in cell:
                        if child.tag.endswith("}v") and child.text:
                            value = child.text
                            break
                    if cell_type == "s" and value.isdigit():
                        index = int(value)
                        value = shared[index] if index < len(shared) else value
                    cells.append(value)
                if cells:
                    rows.append("\t".join(cells))
        return "\n".join(rows)


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: parse_document.py <path>", file=sys.stderr)
        return 2

    path = Path(sys.argv[1])
    suffix = path.suffix.lower()
    try:
        if suffix in TEXT_EXTS:
            text = read_text(path)
        elif suffix == ".docx":
            text = read_docx(path)
        elif suffix == ".xlsx":
            text = read_xlsx(path)
        else:
            print(
                "office-parser: Apache Tika sidecar is required for this file type. "
                "Set JOBCLAW_TIKA_APP_JAR or install ~/.jobclaw/skills/office-parser/lib/tika-app.jar",
                file=sys.stderr,
            )
            return 1
        print(text.strip())
        return 0
    except Exception as exc:
        print(f"office-parser: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
