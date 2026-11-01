"""Check corpus integrity and ground-truth locations; does not evaluate an AI model."""
import hashlib
import json
import re
import unicodedata
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZipFile
from pypdf import PdfReader
from question_suite import (EVALUATION_VERSION, NO_EVIDENCE_TOPICS, QUESTION_KINDS,
                            corpus_fingerprint, find_topic_hits, normalize_text)

ROOT = Path(__file__).resolve().parent


def validate_reference(reference, documents, canonical, allowed_ids):
    fixture_id = reference["fixtureId"]
    assert fixture_id in allowed_ids, "Source is outside the question scope"
    doc = documents[fixture_id]
    section = reference["canonicalSection"]
    assert type(section) is int and 1 <= section <= len(canonical[fixture_id]), "Invalid source section"
    text = canonical[fixture_id][section - 1]
    start, end = reference["startOffset"], reference["endOffset"]
    assert type(start) is int and type(end) is int and 0 <= start < end <= len(text), "Invalid source offsets"
    assert reference["quote"] and text[start:end] == reference["quote"], "Source quote/offset mismatch"
    assert reference["pageNumber"] == (section if doc["format"] == "pdf" else None), "Incorrect source page"
    assert reference["paragraphIndex"] == (section if doc["format"] != "pdf" else None), "Incorrect source paragraph"


def validate_question(question, documents, canonical):
    label = question["id"]
    kind = question["kind"]
    assert kind in QUESTION_KINDS, f"{label}: unknown/ambiguous kind (multi_source is retired)"
    scope = question["documentFixtureIds"]
    assert scope and len(scope) == len(set(scope)) and set(scope) <= documents.keys(), f"{label}: invalid scope"
    assert question["scopeMode"] in {"SELECTED", "ALL_READY"}, f"{label}: invalid scope mode"
    if question["scopeMode"] == "ALL_READY":
        assert scope == sorted(documents), f"{label}: ALL_READY must cover the full corpus snapshot"
    assert question["question"].strip() and question["expectedAnswer"].strip(), f"{label}: blank question/answer"
    sources = question["expectedSources"]
    claims = question["expectedClaims"]
    history = question["history"]
    assert type(question["answerable"]) is bool
    if not question["answerable"]:
        assert kind == "unanswerable" and question["expectedOutcome"] == "NO_EVIDENCE", f"{label}: wrong refusal label"
        assert question["scopeMode"] == "ALL_READY" and scope == sorted(documents), f"{label}: NO_EVIDENCE must cover the full corpus"
        assert not sources and not claims and not history, f"{label}: refusal must not contain factual evidence/history"
        assert question["noEvidenceTopic"] in NO_EVIDENCE_TOPICS
        return

    assert kind != "unanswerable", f"{label}: invalid answerable kind"
    assert question["expectedOutcome"] == "ANSWER" and sources and claims, f"{label}: missing grounded answer"
    for reference in sources:
        validate_reference(reference, documents, canonical, scope)
    covered = set()
    for claim in claims:
        assert claim["text"].strip() and claim["sourceIndices"], f"{label}: empty claim"
        indices = claim["sourceIndices"]
        assert len(indices) == len(set(indices)) and all(type(i) is int and 0 <= i < len(sources) for i in indices), f"{label}: invalid claim source index"
        evidence = normalize_text(" ".join(sources[i]["quote"] for i in indices))
        assert claim["evidenceFragments"] and all(normalize_text(part) and normalize_text(part) in evidence
                                                  for part in claim["evidenceFragments"]), f"{label}: claim evidence fragment missing"
        answer = normalize_text(question["expectedAnswer"])
        assert claim["answerFragments"] and all(normalize_text(part) and normalize_text(part) in answer
                                                for part in claim["answerFragments"]), f"{label}: expected answer fragment missing"
        covered.update(indices)
    assert covered == set(range(len(sources))), f"{label}: extra source not supporting an annotated claim"

    if kind == "direct":
        assert len({(source["fixtureId"], source["canonicalSection"]) for source in sources}) == 1, f"{label}: direct must use one passage"
    if kind == "multi_passage":
        passages = [(source["fixtureId"], source["canonicalSection"]) for source in sources]
        assert len(set(passages)) >= 2, f"{label}: multi_passage needs at least two distinct passages"
        # Separate offsets in the same paragraph/page do not count as two passages.
        for passage in set(passages):
            assert any({passages[i] for i in claim["sourceIndices"]} == {passage}
                       for claim in claims), f"{label}: each passage needs its own evidence-backed claim"
    if kind == "multi_turn":
        assert question["scopeMode"] == "ALL_READY", f"{label}: multi_turn must resolve document identity from history"
        assert len(history) >= 2 and [entry["role"] for entry in history] == ["USER", "ASSISTANT"], f"{label}: missing coherent prior turn"
        prior_text = normalize_text(" ".join(entry["content"] for entry in history))
        for claim in claims:
            assert any(normalize_text(fragment) not in prior_text for fragment in claim["answerFragments"]), f"{label}: answer already disclosed in history"
        for message in history:
            if message["role"] == "ASSISTANT":
                assert message.get("sourceRefs"), f"{label}: ungrounded history"
            for reference in message.get("sourceRefs", []):
                validate_reference(reference, documents, canonical, scope)
    else:
        assert not history, f"{label}: unexpected history for a single-turn case"
    if kind == "exact_keyword":
        keywords = question["exactKeywords"]
        evidence = " ".join(source["quote"] for source in sources)
        assert keywords and all(word in evidence and word in question["question"] + question["expectedAnswer"]
                                for word in keywords), f"{label}: exact identifier not grounded"
    if "formatComparison" in question:
        comparison = question["formatComparison"]
        assert comparison["provided"] in question["question"] and comparison["expected"] in question["expectedAnswer"]
        assert comparison["matches"] is (comparison["provided"] == comparison["expected"])


def validate_no_evidence_audit(audit, questions, documents, canonical):
    assert audit["evaluationVersion"] == EVALUATION_VERSION
    assert audit["reviewStatus"] in {"PENDING_REVIEW", "APPROVED"}
    assert audit["checkedDocumentFixtureIds"] == sorted(documents), "Audit must cover every document"
    assert audit["checkedPassageCount"] == sum(map(len, canonical.values())), "Audit passage count is stale"
    assert audit["corpusTextSha256"] == corpus_fingerprint(canonical), "Audit corpus fingerprint is stale"
    negatives = {question["id"]: question for question in questions if not question["answerable"]}
    cases = {case["questionId"]: case for case in audit["cases"]}
    assert len(cases) == len(audit["cases"]) == len(negatives) == 5
    assert cases.keys() == negatives.keys(), "Missing or unrelated NO_EVIDENCE audit case"
    assert {case["topic"] for case in cases.values()} == NO_EVIDENCE_TOPICS.keys()
    for label, case in cases.items():
        topic = negatives[label]["noEvidenceTopic"]
        assert case["topic"] == topic and case["probeTerms"] == NO_EVIDENCE_TOPICS[topic]
        hits = find_topic_hits(canonical, case["probeTerms"])
        assert hits == case["lexicalHits"], f"{label}: lexical audit result is stale"
        assert not hits, f"{label}: topic mentioned in corpus; manually reassess NO_EVIDENCE"
        assert case["assessment"] == "NO_EVIDENCE_CANDIDATE" and case["rationale"].strip()
        for reference in case["nearbyEvidence"]:
            validate_reference(reference, documents, canonical, sorted(documents))


def validate():
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    questions = json.loads((ROOT / "questions.json").read_text(encoding="utf-8"))
    audit = json.loads((ROOT / "no_evidence_audit.json").read_text(encoding="utf-8"))
    documents = {item["id"]: item for item in manifest["documents"]}
    assert manifest["language"] == "vi" and manifest["corpusVersion"] == "synthetic-vi-v2"
    assert manifest["evaluationVersion"] == EVALUATION_VERSION
    assert hashlib.sha256((ROOT / "questions.json").read_bytes()).hexdigest() == manifest["questionsSha256"]
    assert hashlib.sha256((ROOT / "no_evidence_audit.json").read_bytes()).hexdigest() == manifest["noEvidenceAuditSha256"]
    font = manifest["pdfFont"]
    assert hashlib.sha256((ROOT / font["path"]).read_bytes()).hexdigest() == font["sha256"]
    assert len(documents) == len(manifest["documents"]) == 20
    assert Counter(item["format"] for item in documents.values()) == {"pdf": 8, "docx": 6, "txt": 6}
    assert len(questions) == len({item["id"] for item in questions}) == 25
    assert sum(item["answerable"] for item in questions) == 20
    kinds = Counter(item["kind"] for item in questions)
    assert kinds == {"multi_turn": 5, "multi_passage": 5, "exact_keyword": 3, "direct": 7, "unanswerable": 5}
    # Catch the previous repeated templates with only numbers changed; not a semantic diversity score.
    templates = {re.sub(r"\d+", "#", normalize_text(question["question"])) for question in questions}
    assert len(templates) == 25, "Repeated question templates with only numeric substitutions"
    canonical = {}
    for fixture_id, document in documents.items():
        path = ROOT / document["path"]
        assert hashlib.sha256(path.read_bytes()).hexdigest() == document["sha256"], path
        paragraphs = json.loads((ROOT / document["canonicalPath"]).read_text(encoding="utf-8"))["paragraphs"]
        assert len(paragraphs) == 5
        assert document["language"] == "vi"
        assert all(unicodedata.normalize("NFC", text) == text and "Sổ tay" in text for text in paragraphs)
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
            pdf = PdfReader(path, strict=True)
            assert len(pdf.pages) == 5
            for page, expected in zip(pdf.pages, paragraphs):
                extracted = unicodedata.normalize("NFC", page.extract_text())
                assert " ".join(extracted.split()) == " ".join(expected.split()), document["id"]
                fonts = [entry.get_object() for entry in page["/Resources"]["/Font"].values()]
                assert any(entry.get("/ToUnicode") and entry.get("/FontDescriptor")
                           and "/FontFile2" in entry["/FontDescriptor"] for entry in fonts)
    for question in questions:
        validate_question(question, documents, canonical)
    validate_no_evidence_audit(audit, questions, documents, canonical)
    print(f"PASS: 20 documents, 25 diverse questions, claim/source links, and 5 full-corpus NO_EVIDENCE audits. Review status: {manifest['reviewStatus']}")


if __name__ == "__main__":
    validate()
