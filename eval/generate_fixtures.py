"""Generate deterministic, synthetic fixtures using only the Python standard library.

These are evaluation inputs, not measured RAG results. Review them before freezing.
"""
import hashlib
import json
import textwrap
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parent


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def make_pdf(path, sections):
    objects = [b"", b"", b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    pages = []
    for section in sections:
        page_id = len(objects) + 1
        stream_id = page_id + 1
        pages.append(f"{page_id} 0 R")
        objects.append((f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                        f"/Resources << /Font << /F1 3 0 R >> >> /Contents {stream_id} 0 R >>").encode())
        lines = textwrap.wrap(section, width=88)
        if len(lines) > 52:
            raise ValueError("A PDF section would overflow its page")
        commands = ["BT /F1 10 Tf 13 TL 36 756 Td"]
        for line in lines:
            quoted = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            commands.append(f"({quoted}) Tj T*")
        stream = ("\n".join(commands) + "\nET\n").encode("ascii")
        objects.append(f"<< /Length {len(stream)} >>\nstream\n".encode() + stream + b"endstream")
    objects[0] = b"<< /Type /Catalog /Pages 2 0 R >>"
    objects[1] = f"<< /Type /Pages /Kids [{' '.join(pages)}] /Count {len(pages)} >>".encode()
    content = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for number, body in enumerate(objects, 1):
        offsets.append(len(content))
        content.extend(f"{number} 0 obj\n".encode() + body + b"\nendobj\n")
    xref = len(content)
    content.extend(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]:
        content.extend(f"{offset:010d} 00000 n \n".encode())
    content.extend((f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
                    f"startxref\n{xref}\n%%EOF\n").encode())
    path.write_bytes(content)


def make_docx(path, sections):
    paragraphs = []
    for index, section in enumerate(sections):
        page_break = '<w:br w:type="page"/>' if index < len(sections) - 1 else ""
        paragraphs.append(f'<w:p><w:r><w:t xml:space="preserve">{escape(section)}</w:t>'
                          f'{page_break}</w:r></w:p>')
    parts = {
        "[Content_Types].xml": '<?xml version="1.0" encoding="UTF-8"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
            '</Types>',
        "_rels/.rels": '<?xml version="1.0" encoding="UTF-8"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
            '</Relationships>',
        "word/document.xml": '<?xml version="1.0" encoding="UTF-8"?>'
            '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
            '<w:body>' + ''.join(paragraphs) + '<w:sectPr><w:pgSz w:w="12240" w:h="15840"/>'
            '<w:pgMar w:top="720" w:bottom="720" w:left="720" w:right="720"/></w:sectPr>'
            '</w:body></w:document>'
    }
    with ZipFile(path, "w", compression=ZIP_DEFLATED) as archive:
        for name, value in parts.items():
            info = ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, value.encode("utf-8"))


def generate():
    manifest_path = ROOT / "manifest.json"
    if manifest_path.exists() and json.loads(manifest_path.read_text(encoding="utf-8"))["reviewStatus"] == "APPROVED":
        raise SystemExit("Approved fixtures are frozen; create a new corpus version instead")
    (ROOT / "fixtures").mkdir(parents=True, exist_ok=True)
    documents, questions = [], []
    for index in range(1, 21):
        fixture_id = f"handbook-{index:02d}"
        extension = "pdf" if index <= 8 else "docx" if index <= 14 else "txt"
        notify, submit, cap, retention = 7 + index, 14 + index, 100 + index * 25, 30 + index
        facts = [
            f"Handbook {index:02d} belongs to Service Team {index:02d} and takes effect on 2026-10-01.",
            f"Refund condition one: notify the service desk within {notify} calendar days after purchase.",
            f"Refund condition two: submit the complete claim within {submit} calendar days after purchase. The reimbursement cap is USD {cap}.",
            f"The exact claim identifier is REF-2026-{index:03d}. Quote this identifier on every claim form.",
            f"Supporting records must be retained for {retention} calendar days after case closure.",
        ]
        notes = [
            "This synthetic handbook applies only to the named service team. Other teams maintain separate handbooks and their deadlines must not be substituted.",
            "Keep the purchase receipt and the correspondence together. The service desk checks the recorded purchase date before accepting a notification.",
            "A complete claim contains a receipt, a written explanation and the claimant contact details. An incomplete claim is returned for correction.",
            "The claim identifier distinguishes this handbook from similar policies. Copy it exactly, including punctuation and leading zeroes.",
            "Close a case only after the decision is recorded. Store supporting evidence with access limited to the service team responsible for the case.",
        ]
        sections = []
        for position, fact in enumerate(facts):
            # ~300 words per section, five pages or equivalent text per document.
            guidance = " ".join(f"Guidance item {item}: {notes[position]}" for item in range(1, 12))
            sections.append(f"Handbook {index:02d}, section {position + 1}. {fact} {guidance}")
        file = ROOT / "fixtures" / f"{fixture_id}.{extension}"
        if extension == "pdf":
            make_pdf(file, sections)
        elif extension == "docx":
            make_docx(file, sections)
        else:
            file.write_text("\n\n".join(sections) + "\n", encoding="utf-8", newline="\n")
        canonical = f"canonical/{fixture_id}.json"
        write_json(ROOT / canonical, {"fixtureId": fixture_id, "paragraphs": sections})
        documents.append({"id": fixture_id, "path": f"fixtures/{file.name}",
                          "format": extension, "sha256": hashlib.sha256(file.read_bytes()).hexdigest(),
                          "canonicalPath": canonical, "pageCount": 5 if extension == "pdf" else None,
                          "paragraphCount": None if extension == "pdf" else 5,
                          "fixtureVersion": "synthetic-v1"})

        def source(section):
            paragraph = sections[section - 1]
            quote = facts[section - 1]
            return {"fixtureId": fixture_id, "pageNumber": section if extension == "pdf" else None,
                    "paragraphIndex": section if extension != "pdf" else None,
                    "canonicalSection": section, "quote": quote,
                    "startOffset": paragraph.index(quote), "endOffset": paragraph.index(quote) + len(quote)}

        history = []
        if index <= 5:
            kind = "multi_turn"
            question = "Điều kiện thứ hai phải hoàn tất trong bao nhiêu ngày kể từ khi mua?"
            answer = f"Nộp hồ sơ đầy đủ trong {submit} ngày kể từ khi mua."
            references = [source(3)]
            history = [{"role": "USER", "content": f"Handbook {index:02d} có hai điều kiện hoàn tiền nào?"},
                       {"role": "ASSISTANT", "content": facts[1] + " " + facts[2],
                        "sourceRefs": [source(2), source(3)]}]
        elif index <= 10:
            kind = "multi_source"
            question = f"Theo Handbook {index:02d}, phải thông báo và nộp hồ sơ hoàn tiền trong bao nhiêu ngày kể từ khi mua?"
            answer = f"Thông báo trong {notify} ngày và nộp hồ sơ đầy đủ trong {submit} ngày kể từ khi mua."
            references = [source(2), source(3)]
        elif index <= 13:
            kind = "exact_keyword"
            question = f"Mã hồ sơ chính xác của Handbook {index:02d} là gì? Giữ nguyên dấu và số 0 ở đầu."
            answer, references = f"REF-2026-{index:03d}", [source(4)]
        else:
            kind = "direct"
            question = f"Theo Handbook {index:02d}, cần giữ chứng từ bao nhiêu ngày sau khi đóng hồ sơ?"
            answer, references = f"{retention} ngày sau khi đóng hồ sơ.", [source(5)]
        questions.append({"id": f"q-{index:02d}", "kind": kind, "documentFixtureIds": [fixture_id],
                          "question": question, "history": history, "answerable": True,
                          "expectedAnswer": answer, "expectedSources": references})
    unknowns = ["Số tài khoản ngân hàng để nhận tiền là gì?", "Ai là giám đốc pháp lý ký tài liệu này?",
                "Có thể nộp hồ sơ qua ứng dụng di động nào?", "Lãi suất cho khoản hoàn tiền chậm là bao nhiêu?",
                "Phí vận chuyển quốc tế được tính theo công thức nào?"]
    for index, question in enumerate(unknowns, 1):
        questions.append({"id": f"q-{20 + index:02d}", "kind": "unanswerable",
                          "documentFixtureIds": [f"handbook-{index:02d}"], "question": question,
                          "history": [], "answerable": False, "expectedOutcome": "NO_EVIDENCE",
                          "expectedAnswer": "Không tìm thấy thông tin này trong tài liệu.", "expectedSources": []})
    write_json(manifest_path, {"corpusVersion": "synthetic-v1", "reviewStatus": "PENDING_REVIEW",
                              "reviewOwner": "Tuan Anh", "reviewedAt": None,
                              "pipelineVersion": "UNSET_UNTIL_PHASE_6", "documents": documents})
    write_json(ROOT / "questions.json", questions)
    print("Generated 20 synthetic documents and 25 labeled questions; review is pending.")


if __name__ == "__main__":
    generate()
