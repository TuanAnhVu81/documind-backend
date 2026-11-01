"""Generate deterministic Vietnamese evaluation fixtures with embedded Unicode fonts.

These are evaluation inputs, not measured RAG results. Review them before freezing.
"""
import hashlib
import json
import unicodedata
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parent
CORPUS_VERSION = "synthetic-vi-v2"
FONT_PATH = ROOT / "assets/fonts/NotoSans-Regular.ttf"


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def make_pdf(path, sections):
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.pdfgen.canvas import Canvas
    from reportlab.platypus import Paragraph

    font_name = "DocuMindNotoSans"
    if font_name not in pdfmetrics.getRegisteredFontNames():
        pdfmetrics.registerFont(TTFont(font_name, str(FONT_PATH)))
    glyphs = pdfmetrics.getFont(font_name).face.charToGlyph
    for character in set("".join(sections)):
        if not character.isspace() and not glyphs.get(ord(character)):
            raise ValueError(f"Font is missing glyph U+{ord(character):04X}")
    style = ParagraphStyle("Vietnamese", fontName=font_name, fontSize=10, leading=14)
    canvas = Canvas(str(path), pagesize=A4, pageCompression=1, invariant=1, pdfVersion=(1, 4))
    canvas.setTitle(f"Bộ eval tiếng Việt — {path.stem}")
    canvas.setAuthor("DocuMind")
    width, height = A4
    for section in sections:
        paragraph = Paragraph(escape(section), style)
        _, text_height = paragraph.wrap(width - 72, height - 72)
        if text_height > height - 72:
            raise ValueError("A PDF section would overflow its page")
        paragraph.drawOn(canvas, 36, height - 36 - text_height)
        canvas.showPage()
    canvas.save()


def make_docx(path, sections):
    paragraphs = []
    for index, section in enumerate(sections):
        page_break = '<w:br w:type="page"/>' if index < len(sections) - 1 else ""
        paragraphs.append(f'<w:p><w:r><w:rPr><w:lang w:val="vi-VN"/></w:rPr>'
                          f'<w:t xml:space="preserve">{escape(section)}</w:t>'
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
            f"Sổ tay {index:02d} thuộc Tổ dịch vụ {index:02d}, có hiệu lực từ ngày 01/10/2026.",
            f"Điều kiện hoàn tiền thứ nhất: thông báo cho bộ phận tiếp nhận trong vòng {notify} ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.",
            f"Điều kiện hoàn tiền thứ hai: nộp hồ sơ đầy đủ trong vòng {submit} ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ. Mức hoàn tiền tối đa là {cap} đô la Mỹ (USD).",
            f"Mã hồ sơ chính xác là REF-2026-{index:03d}. Người nộp phải ghi mã này trên mọi đơn đề nghị hoàn tiền.",
            f"Chứng từ liên quan phải được lưu giữ trong {retention} ngày kể từ ngày đóng hồ sơ, tính cả ngày nghỉ và ngày lễ.",
        ]
        notes = [
            "Sổ tay mẫu này chỉ áp dụng cho tổ dịch vụ được nêu tên. Mỗi tổ khác sử dụng sổ tay riêng; không được lấy thời hạn của tổ khác để thay thế quy định trong tài liệu này.",
            "Lưu hóa đơn mua hàng cùng các thư trao đổi. Bộ phận tiếp nhận kiểm tra ngày mua được ghi nhận trước khi xác nhận đã nhận thông báo của người đề nghị hoàn tiền.",
            "Hồ sơ đầy đủ gồm hóa đơn, bản giải trình bằng văn bản và thông tin liên hệ của người đề nghị. Hồ sơ thiếu thành phần phải được trả lại để bổ sung trước khi xét duyệt.",
            "Mã hồ sơ dùng để phân biệt sổ tay này với những chính sách có nội dung tương tự. Khi sao chép phải giữ nguyên dấu gạch nối và các chữ số 0 ở đầu mỗi phần của mã.",
            "Chỉ đóng hồ sơ sau khi đã ghi nhận quyết định xử lý. Chứng từ phải được lưu cùng hồ sơ; chỉ tổ dịch vụ chịu trách nhiệm giải quyết mới được cấp quyền truy cập.",
        ]
        sections = []
        for position, fact in enumerate(facts):
            # ~300 words per section, five pages or equivalent text per document.
            guidance = " ".join(f"Lưu ý {item}: {notes[position]}" for item in range(1, 12))
            sections.append(unicodedata.normalize("NFC", f"Sổ tay {index:02d}, mục {position + 1}. {fact} {guidance}"))
        file = ROOT / "fixtures" / f"{fixture_id}.{extension}"
        if extension == "pdf":
            make_pdf(file, sections)
        elif extension == "docx":
            make_docx(file, sections)
        else:
            file.write_text("\n\n".join(sections) + "\n", encoding="utf-8", newline="\n")
        canonical = f"canonical/{fixture_id}.json"
        write_json(ROOT / canonical, {"fixtureId": fixture_id, "language": "vi", "paragraphs": sections})
        documents.append({"id": fixture_id, "path": f"fixtures/{file.name}",
                          "format": extension, "sha256": hashlib.sha256(file.read_bytes()).hexdigest(),
                          "canonicalPath": canonical, "pageCount": 5 if extension == "pdf" else None,
                          "paragraphCount": None if extension == "pdf" else 5,
                          "fixtureVersion": CORPUS_VERSION, "language": "vi"})

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
            history = [{"role": "USER", "content": f"Sổ tay {index:02d} có hai điều kiện hoàn tiền nào?"},
                       {"role": "ASSISTANT", "content": facts[1] + " " + facts[2],
                        "sourceRefs": [source(2), source(3)]}]
        elif index <= 10:
            kind = "multi_source"
            question = f"Theo Sổ tay {index:02d}, phải thông báo và nộp hồ sơ hoàn tiền trong bao nhiêu ngày kể từ khi mua?"
            answer = f"Thông báo trong {notify} ngày và nộp hồ sơ đầy đủ trong {submit} ngày kể từ khi mua."
            references = [source(2), source(3)]
        elif index <= 13:
            kind = "exact_keyword"
            question = f"Mã hồ sơ chính xác của Sổ tay {index:02d} là gì? Giữ nguyên dấu và số 0 ở đầu."
            answer, references = f"REF-2026-{index:03d}", [source(4)]
        else:
            kind = "direct"
            question = f"Theo Sổ tay {index:02d}, cần giữ chứng từ bao nhiêu ngày sau khi đóng hồ sơ?"
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
    write_json(manifest_path, {"corpusVersion": CORPUS_VERSION, "language": "vi", "reviewStatus": "PENDING_REVIEW",
                              "reviewOwner": "Tuan Anh", "reviewedAt": None,
                              "pdfFont": {"path": "assets/fonts/NotoSans-Regular.ttf",
                                          "sha256": hashlib.sha256(FONT_PATH.read_bytes()).hexdigest()},
                              "pipelineVersion": "UNSET_UNTIL_PHASE_6", "documents": documents})
    write_json(ROOT / "questions.json", questions)
    print("Generated 20 synthetic documents and 25 labeled questions; review is pending.")


if __name__ == "__main__":
    generate()
