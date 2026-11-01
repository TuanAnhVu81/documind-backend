"""Check corpus integrity and ground-truth locations; does not evaluate an AI model."""
import hashlib
import json
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent


def validate():
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    questions = json.loads((ROOT / "questions.json").read_text(encoding="utf-8"))
    documents = {item["id"]: item for item in manifest["documents"]}
    assert len(documents) == len(manifest["documents"]) == 20
    assert Counter(item["format"] for item in documents.values()) == {"pdf": 8, "docx": 6, "txt": 6}
    assert len(questions) == len({item["id"] for item in questions}) == 25
    assert sum(item["answerable"] for item in questions) == 20
    kinds = Counter(item["kind"] for item in questions)
    assert kinds["multi_turn"] >= 5 and kinds["multi_source"] >= 5 and kinds["exact_keyword"] >= 3
    canonical = {}
    for fixture_id, document in documents.items():
        path = ROOT / document["path"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == document["sha256"], path
        paragraphs = json.loads((ROOT / document["canonicalPath"]).read_text(encoding="utf-8"))["paragraphs"]
        assert len(paragraphs) == 5
        canonical[fixture_id] = paragraphs
        if document["format"] == "docx":
            with ZipFile(path) as archive:
                assert archive.testzip() is None
                root = ElementTree.fromstring(archive.read("word/document.xml"))
                ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
                extracted = ["".join(p.itertext()) for p in root.findall(".//w:p", ns)]
                assert extracted == paragraphs
        elif document["format"] == "txt":
            assert path.read_text(encoding="utf-8").strip().split("\n\n") == paragraphs
        else:
            pdf = path.read_bytes()
            assert pdf.startswith(b"%PDF-1.4") and pdf.count(b"/Type /Page ") == 5
    for question in questions:
        assert question["documentFixtureIds"] and set(question["documentFixtureIds"]) <= documents.keys()
        assert bool(question["expectedSources"]) == question["answerable"]
        if not question["answerable"]:
            assert question["expectedOutcome"] == "NO_EVIDENCE"
        refs = list(question["expectedSources"])
        for message in question["history"]:
            refs.extend(message.get("sourceRefs", []))
        for reference in refs:
            doc = documents[reference["fixtureId"]]
            section = reference["canonicalSection"]
            assert reference["fixtureId"] in question["documentFixtureIds"]
            text = canonical[reference["fixtureId"]][section - 1]
            assert text[reference["startOffset"]:reference["endOffset"]] == reference["quote"]
            assert reference["pageNumber"] == (section if doc["format"] == "pdf" else None)
            assert reference["paragraphIndex"] == (section if doc["format"] != "pdf" else None)
    print(f"PASS: 20 documents, 25 questions, hashes and source locations. Review status: {manifest['reviewStatus']}")


if __name__ == "__main__":
    validate()
