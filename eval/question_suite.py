"""Curated questions and auditable evidence; no model calls or automatic approval."""
import hashlib
import json
import re
import unicodedata

EVALUATION_VERSION = "questions-vi-v3"
QUESTION_KINDS = {"multi_turn", "multi_passage", "exact_keyword", "direct", "unanswerable"}
NO_EVIDENCE_TOPICS = {
    "bank_account": ["tài khoản ngân hàng", "số tài khoản", "ngân hàng", "IBAN", "SWIFT"],
    "legal_signatory": ["giám đốc pháp lý", "người ký ban hành", "ký ban hành", "chữ ký", "họ tên"],
    "mobile_application": ["ứng dụng di động", "ứng dụng điện thoại", "ứng dụng nộp hồ sơ",
                           "tên ứng dụng", "mobile app", "Android", "iOS"],
    "late_refund_interest": ["lãi suất", "tiền lãi", "lãi chậm trả", "lãi hoàn tiền",
                             "chậm hoàn tiền", "hoàn tiền chậm"],
    "international_shipping": ["phí vận chuyển", "cước vận chuyển", "vận chuyển quốc tế",
                               "phí giao hàng", "công thức vận chuyển"],
}


def normalize_text(text):
    return " ".join(unicodedata.normalize("NFC", text).casefold().split())


def corpus_fingerprint(canonical):
    payload = json.dumps(canonical, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def find_topic_hits(canonical, terms):
    """A conservative lexical guard; zero hits is NOT proof of semantic absence."""
    hits = []
    for fixture_id, passages in canonical.items():
        for section, passage in enumerate(passages, 1):
            text = normalize_text(passage)
            for term in terms:
                if re.search(r"(?<!\w)" + re.escape(normalize_text(term)) + r"(?!\w)", text):
                    hits.append({"fixtureId": fixture_id, "canonicalSection": section, "term": term})
    return hits


def build_questions(documents, canonical):
    docs = {document["id"]: document for document in documents}
    all_ids = sorted(docs)
    questions = []

    def ref(number, section, quote):
        fixture_id = f"handbook-{number:02d}"
        text = canonical[fixture_id][section - 1]
        start = text.index(quote)  # Fail on changed facts; never silently rewrite an expected answer.
        pdf = docs[fixture_id]["format"] == "pdf"
        return {"fixtureId": fixture_id, "pageNumber": section if pdf else None,
                "paragraphIndex": None if pdf else section, "canonicalSection": section,
                "quote": quote, "startOffset": start, "endOffset": start + len(quote)}

    def claim(text, source_index, evidence, answer):
        return {"text": text, "sourceIndices": [source_index],
                "evidenceFragments": evidence, "answerFragments": answer}

    def history(question, answer, sources):
        return [{"role": "USER", "content": question},
                {"role": "ASSISTANT", "content": answer, "sourceRefs": sources}]

    def add(number, kind, task, question, answer, sources, claims, prior=None, keywords=None):
        item = {"id": f"q-{number:02d}", "kind": kind, "taskType": task,
                "scopeMode": "ALL_READY" if kind == "multi_turn" else "SELECTED",
                "documentFixtureIds": all_ids.copy() if kind == "multi_turn" else [f"handbook-{number:02d}"],
                "question": question, "history": prior or [], "answerable": True,
                "expectedOutcome": "ANSWER", "expectedAnswer": answer,
                "expectedSources": sources, "expectedClaims": claims}
        if keywords:
            item["exactKeywords"] = keywords
        questions.append(item)

    add(1, "multi_turn", "notification_followup",
        "Vậy tổ đó phải thông báo yêu cầu hoàn tiền trong bao lâu, có tính ngày nghỉ không?",
        "Phải thông báo trong vòng 8 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.",
        [ref(1, 2, "Điều kiện hoàn tiền thứ nhất: thông báo cho bộ phận tiếp nhận trong vòng 8 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.")],
        [claim("Hạn thông báo là 8 ngày kể từ ngày mua, gồm ngày nghỉ và lễ.", 0,
               ["8 ngày kể từ ngày mua", "tính cả ngày nghỉ và ngày lễ"],
               ["8 ngày kể từ ngày mua", "tính cả ngày nghỉ và ngày lễ"])],
        history("Sổ tay 01 thuộc tổ dịch vụ nào?", "Sổ tay 01 thuộc Tổ dịch vụ 01.",
                [ref(1, 1, "Sổ tay 01 thuộc Tổ dịch vụ 01, có hiệu lực từ ngày 01/10/2026.")]))

    add(2, "multi_turn", "missing_document_followup",
        "Nếu thiếu một trong các thành phần đó thì hồ sơ được xử lý thế nào?",
        "Hồ sơ thiếu thành phần phải được trả lại để bổ sung trước khi xét duyệt.",
        [ref(2, 3, "Hồ sơ thiếu thành phần phải được trả lại để bổ sung trước khi xét duyệt.")],
        [claim("Hồ sơ thiếu phải trả lại để bổ sung trước xét duyệt.", 0,
               ["trả lại để bổ sung trước khi xét duyệt"], ["trả lại để bổ sung trước khi xét duyệt"])],
        history("Hồ sơ đầy đủ theo Sổ tay 02 gồm những gì?",
                "Hồ sơ gồm hóa đơn, bản giải trình bằng văn bản và thông tin liên hệ của người đề nghị.",
                [ref(2, 3, "Hồ sơ đầy đủ gồm hóa đơn, bản giải trình bằng văn bản và thông tin liên hệ của người đề nghị.")]))

    add(3, "multi_turn", "amount_followup", "Còn mức hoàn tiền tối đa cho hồ sơ đó là bao nhiêu?",
        "Mức hoàn tiền tối đa là 175 đô la Mỹ (USD).",
        [ref(3, 3, "Mức hoàn tiền tối đa là 175 đô la Mỹ (USD).")],
        [claim("Mức trần hoàn tiền là 175 USD.", 0, ["175 đô la Mỹ (USD)"], ["175 đô la Mỹ (USD)"])],
        history("Mã hồ sơ của Sổ tay 03 là gì?", "Mã hồ sơ là REF-2026-003.",
                [ref(3, 4, "Mã hồ sơ chính xác là REF-2026-003.")]))

    add(4, "multi_turn", "identifier_followup", "Khi sao chép mã này, những chi tiết nào phải giữ nguyên?",
        "Phải giữ nguyên dấu gạch nối và các chữ số 0 ở đầu mỗi phần của mã.",
        [ref(4, 4, "Khi sao chép phải giữ nguyên dấu gạch nối và các chữ số 0 ở đầu mỗi phần của mã.")],
        [claim("Giữ dấu gạch nối và chữ số 0 đầu mỗi phần.", 0,
               ["dấu gạch nối", "các chữ số 0 ở đầu"], ["dấu gạch nối", "các chữ số 0 ở đầu"])],
        history("Cho biết mã hồ sơ trong Sổ tay 04.", "Mã hồ sơ là REF-2026-004.",
                [ref(4, 4, "Mã hồ sơ chính xác là REF-2026-004.")]))

    add(5, "multi_turn", "retention_followup",
        "Sau khi đóng hồ sơ của tổ đó, phải giữ chứng từ thêm bao nhiêu ngày?",
        "Phải lưu giữ chứng từ trong 35 ngày kể từ ngày đóng hồ sơ, tính cả ngày nghỉ và ngày lễ.",
        [ref(5, 5, "Chứng từ liên quan phải được lưu giữ trong 35 ngày kể từ ngày đóng hồ sơ, tính cả ngày nghỉ và ngày lễ.")],
        [claim("Lưu giữ 35 ngày kể từ ngày đóng hồ sơ.", 0,
               ["35 ngày kể từ ngày đóng hồ sơ"], ["35 ngày kể từ ngày đóng hồ sơ"])],
        history("Sổ tay 05 thuộc tổ nào?", "Sổ tay 05 thuộc Tổ dịch vụ 05.",
                [ref(5, 1, "Sổ tay 05 thuộc Tổ dịch vụ 05, có hiệu lực từ ngày 01/10/2026.")]))

    add(6, "multi_passage", "deadline_and_amount",
        "Theo Sổ tay 06, hãy nêu cả thời hạn thông báo yêu cầu và mức hoàn tiền tối đa.",
        "Phải thông báo trong vòng 13 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ; mức hoàn tiền tối đa là 250 đô la Mỹ (USD).",
        [ref(6, 2, "Điều kiện hoàn tiền thứ nhất: thông báo cho bộ phận tiếp nhận trong vòng 13 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ."),
         ref(6, 3, "Mức hoàn tiền tối đa là 250 đô la Mỹ (USD).")],
        [claim("Hạn thông báo 13 ngày kể từ ngày mua.", 0, ["13 ngày kể từ ngày mua"], ["13 ngày kể từ ngày mua"]),
         claim("Mức trần 250 USD.", 1, ["250 đô la Mỹ (USD)"], ["250 đô la Mỹ (USD)"])])

    add(7, "multi_passage", "process_and_retention",
        "Trong Sổ tay 07, hồ sơ thiếu giấy tờ được xử lý thế nào và chứng từ phải giữ bao lâu sau khi đóng hồ sơ?",
        "Hồ sơ thiếu thành phần phải được trả lại để bổ sung trước khi xét duyệt. Chứng từ được lưu giữ trong 37 ngày kể từ ngày đóng hồ sơ, tính cả ngày nghỉ và ngày lễ.",
        [ref(7, 3, "Hồ sơ thiếu thành phần phải được trả lại để bổ sung trước khi xét duyệt."),
         ref(7, 5, "Chứng từ liên quan phải được lưu giữ trong 37 ngày kể từ ngày đóng hồ sơ, tính cả ngày nghỉ và ngày lễ.")],
        [claim("Hồ sơ thiếu phải trả lại để bổ sung.", 0, ["trả lại để bổ sung trước khi xét duyệt"], ["trả lại để bổ sung trước khi xét duyệt"]),
         claim("Lưu giữ chứng từ 37 ngày sau đóng hồ sơ.", 1, ["37 ngày kể từ ngày đóng hồ sơ"], ["37 ngày kể từ ngày đóng hồ sơ"])])

    add(8, "multi_passage", "scope_rule_and_deadline",
        "Sổ tay 08 có cho lấy thời hạn của tổ khác để thay thế không? Hãy nêu thời hạn nộp hồ sơ đúng của sổ tay này.",
        "Không được lấy thời hạn của tổ khác để thay thế. Phải nộp hồ sơ đầy đủ trong vòng 22 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.",
        [ref(8, 1, "Mỗi tổ khác sử dụng sổ tay riêng; không được lấy thời hạn của tổ khác để thay thế quy định trong tài liệu này."),
         ref(8, 3, "Điều kiện hoàn tiền thứ hai: nộp hồ sơ đầy đủ trong vòng 22 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.")],
        [claim("Không thay bằng thời hạn của tổ khác.", 0, ["không được lấy thời hạn của tổ khác để thay thế"], ["Không được lấy thời hạn của tổ khác để thay thế"]),
         claim("Hạn nộp hồ sơ 22 ngày kể từ ngày mua.", 1, ["22 ngày kể từ ngày mua"], ["22 ngày kể từ ngày mua"])])

    add(9, "multi_passage", "three_fact_summary",
        "Tóm tắt Sổ tay 09 theo ba mục: ngày hiệu lực, mã hồ sơ phải ghi trên đơn và mức hoàn tiền tối đa.",
        "Ngày hiệu lực: 01/10/2026. Mã hồ sơ phải ghi trên mọi đơn đề nghị hoàn tiền: REF-2026-009. Mức hoàn tiền tối đa: 325 đô la Mỹ (USD).",
        [ref(9, 1, "Sổ tay 09 thuộc Tổ dịch vụ 09, có hiệu lực từ ngày 01/10/2026."),
         ref(9, 4, "Mã hồ sơ chính xác là REF-2026-009. Người nộp phải ghi mã này trên mọi đơn đề nghị hoàn tiền."),
         ref(9, 3, "Mức hoàn tiền tối đa là 325 đô la Mỹ (USD).")],
        [claim("Hiệu lực từ 01/10/2026.", 0, ["01/10/2026"], ["01/10/2026"]),
         claim("Mã REF-2026-009 phải ghi trên mọi đơn.", 1, ["REF-2026-009", "trên mọi đơn đề nghị hoàn tiền"], ["REF-2026-009", "trên mọi đơn đề nghị hoàn tiền"]),
         claim("Mức trần 325 USD.", 2, ["325 đô la Mỹ (USD)"], ["325 đô la Mỹ (USD)"])])

    add(10, "multi_passage", "deadline_and_checklist",
        "Theo Sổ tay 10, cần thông báo trong thời hạn nào và một bộ hồ sơ đầy đủ phải gồm những gì?",
        "Cần thông báo trong vòng 17 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ. Hồ sơ gồm hóa đơn, bản giải trình bằng văn bản và thông tin liên hệ của người đề nghị.",
        [ref(10, 2, "Điều kiện hoàn tiền thứ nhất: thông báo cho bộ phận tiếp nhận trong vòng 17 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ."),
         ref(10, 3, "Hồ sơ đầy đủ gồm hóa đơn, bản giải trình bằng văn bản và thông tin liên hệ của người đề nghị.")],
        [claim("Hạn thông báo 17 ngày kể từ ngày mua.", 0, ["17 ngày kể từ ngày mua"], ["17 ngày kể từ ngày mua"]),
         claim("Hồ sơ có hóa đơn, giải trình và liên hệ.", 1,
               ["hóa đơn", "bản giải trình bằng văn bản", "thông tin liên hệ của người đề nghị"],
               ["hóa đơn", "bản giải trình bằng văn bản", "thông tin liên hệ của người đề nghị"])])

    add(11, "exact_keyword", "identifier_usage", "Trong Sổ tay 11, mã REF-2026-011 phải được ghi ở đâu?",
        "Mã REF-2026-011 phải được ghi trên mọi đơn đề nghị hoàn tiền.",
        [ref(11, 4, "Mã hồ sơ chính xác là REF-2026-011. Người nộp phải ghi mã này trên mọi đơn đề nghị hoàn tiền.")],
        [claim("Ghi REF-2026-011 trên mọi đơn đề nghị hoàn tiền.", 0,
               ["REF-2026-011", "trên mọi đơn đề nghị hoàn tiền"], ["REF-2026-011", "trên mọi đơn đề nghị hoàn tiền"])],
        keywords=["REF-2026-011"])

    add(12, "exact_keyword", "exact_identifier", "Ghi lại mã hồ sơ đầy đủ của Sổ tay 12, giữ nguyên dấu gạch nối và các chữ số 0.",
        "REF-2026-012", [ref(12, 4, "Mã hồ sơ chính xác là REF-2026-012.")],
        [claim("Mã hồ sơ là REF-2026-012.", 0, ["REF-2026-012"], ["REF-2026-012"])], keywords=["REF-2026-012"])

    add(13, "exact_keyword", "format_correction",
        "Một người ghi REF2026013 vào đơn theo Sổ tay 13. Mã đó đã đúng định dạng chưa? Hãy sửa lại nếu cần.",
        "Chưa đúng: mã chính xác là REF-2026-013, cần giữ dấu gạch nối và các chữ số 0 ở đầu mỗi phần của mã.",
        [ref(13, 4, "Mã hồ sơ chính xác là REF-2026-013."),
         ref(13, 4, "Khi sao chép phải giữ nguyên dấu gạch nối và các chữ số 0 ở đầu mỗi phần của mã.")],
        [claim("Mã đúng là REF-2026-013.", 0, ["REF-2026-013"], ["REF-2026-013"]),
         claim("Phải giữ dấu gạch nối và chữ số 0.", 1,
               ["dấu gạch nối", "các chữ số 0 ở đầu"], ["dấu gạch nối", "các chữ số 0 ở đầu"])],
        keywords=["REF-2026-013"])
    questions[-1]["formatComparison"] = {"provided": "REF2026013", "expected": "REF-2026-013", "matches": False}

    add(14, "direct", "owner_and_effective_date", "Sổ tay 14 thuộc tổ dịch vụ nào và có hiệu lực từ ngày nào?",
        "Sổ tay 14 thuộc Tổ dịch vụ 14, có hiệu lực từ ngày 01/10/2026.",
        [ref(14, 1, "Sổ tay 14 thuộc Tổ dịch vụ 14, có hiệu lực từ ngày 01/10/2026.")],
        [claim("Tổ dịch vụ 14 quản lý; hiệu lực 01/10/2026.", 0,
               ["Tổ dịch vụ 14", "01/10/2026"], ["Tổ dịch vụ 14", "01/10/2026"])])

    add(15, "direct", "calendar_day_interpretation",
        "Thời hạn thông báo hoàn tiền trong Sổ tay 15 có loại trừ ngày nghỉ và ngày lễ không?",
        "Không. Thời hạn thông báo tính cả ngày nghỉ và ngày lễ.",
        [ref(15, 2, "Điều kiện hoàn tiền thứ nhất: thông báo cho bộ phận tiếp nhận trong vòng 22 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.")],
        [claim("Không loại trừ ngày nghỉ/lễ.", 0, ["tính cả ngày nghỉ và ngày lễ"], ["Không", "tính cả ngày nghỉ và ngày lễ"])])

    add(16, "direct", "date_verification", "Theo Sổ tay 16, phải kiểm tra ngày nào trước khi xác nhận đã nhận thông báo?",
        "Bộ phận tiếp nhận phải kiểm tra ngày mua được ghi nhận.",
        [ref(16, 2, "Bộ phận tiếp nhận kiểm tra ngày mua được ghi nhận trước khi xác nhận đã nhận thông báo của người đề nghị hoàn tiền.")],
        [claim("Kiểm tra ngày mua đã ghi nhận.", 0, ["ngày mua được ghi nhận"], ["ngày mua được ghi nhận"])])

    add(17, "direct", "closing_precondition", "Theo Sổ tay 17, có thể đóng hồ sơ trước khi ghi nhận quyết định xử lý không?",
        "Không. Chỉ đóng hồ sơ sau khi đã ghi nhận quyết định xử lý.",
        [ref(17, 5, "Chỉ đóng hồ sơ sau khi đã ghi nhận quyết định xử lý.")],
        [claim("Ghi nhận quyết định trước khi đóng hồ sơ.", 0,
               ["Chỉ đóng hồ sơ sau khi đã ghi nhận quyết định xử lý"],
               ["Không", "Chỉ đóng hồ sơ sau khi đã ghi nhận quyết định xử lý"])])

    add(18, "direct", "access_control", "Theo Sổ tay 18, ai được cấp quyền truy cập chứng từ đã lưu cùng hồ sơ?",
        "Chỉ tổ dịch vụ chịu trách nhiệm giải quyết mới được cấp quyền truy cập.",
        [ref(18, 5, "Chứng từ phải được lưu cùng hồ sơ; chỉ tổ dịch vụ chịu trách nhiệm giải quyết mới được cấp quyền truy cập.")],
        [claim("Chỉ tổ chịu trách nhiệm giải quyết được truy cập.", 0,
               ["chỉ tổ dịch vụ chịu trách nhiệm giải quyết"], ["Chỉ tổ dịch vụ chịu trách nhiệm giải quyết"])])

    add(19, "direct", "amount_and_currency", "Mức hoàn tiền tối đa theo Sổ tay 19 là bao nhiêu và dùng đơn vị tiền nào?",
        "Mức hoàn tiền tối đa là 575 đô la Mỹ (USD).", [ref(19, 3, "Mức hoàn tiền tối đa là 575 đô la Mỹ (USD).")],
        [claim("Mức trần 575 USD.", 0, ["575 đô la Mỹ (USD)"], ["575 đô la Mỹ (USD)"])])

    add(20, "direct", "retention_time_anchor",
        "Theo Sổ tay 20, thời gian lưu giữ chứng từ bắt đầu từ ngày mua hàng hay ngày đóng hồ sơ?",
        "Thời gian lưu giữ bắt đầu kể từ ngày đóng hồ sơ, không phải ngày mua hàng.",
        [ref(20, 5, "Chứng từ liên quan phải được lưu giữ trong 50 ngày kể từ ngày đóng hồ sơ, tính cả ngày nghỉ và ngày lễ.")],
        [claim("Mốc bắt đầu là ngày đóng hồ sơ.", 0, ["kể từ ngày đóng hồ sơ"], ["kể từ ngày đóng hồ sơ"])])

    negatives = [
        (21, "bank_account", "Tra cứu toàn bộ các sổ tay: số tài khoản ngân hàng được chỉ định để nhận tiền hoàn là gì?",
         "Không tìm thấy số tài khoản ngân hàng nhận tiền hoàn trong các sổ tay đã cung cấp.",
         "Các sổ tay nêu mã hồ sơ REF và số tiền, không nêu ngân hàng hoặc tài khoản nhận tiền. Mã REF không phải số tài khoản.",
         ref(1, 4, "Mã hồ sơ chính xác là REF-2026-001.")),
        (22, "legal_signatory", "Trong toàn bộ thư viện, có nêu họ tên giám đốc pháp lý ký ban hành các sổ tay không? Nếu có, cho biết tên.",
         "Không tìm thấy họ tên giám đốc pháp lý ký ban hành trong các sổ tay đã cung cấp.",
         "Phần mở đầu chỉ nêu tổ dịch vụ và ngày hiệu lực; không có tên người ký hoặc giám đốc pháp lý.",
         ref(1, 1, "Sổ tay 01 thuộc Tổ dịch vụ 01, có hiệu lực từ ngày 01/10/2026.")),
        (23, "mobile_application", "Tra cứu toàn bộ sổ tay: ứng dụng di động cụ thể nào được chỉ định để nộp hồ sơ hoàn tiền?",
         "Không tìm thấy tên ứng dụng di động được chỉ định để nộp hồ sơ trong các sổ tay đã cung cấp.",
         "Danh mục giấy tờ và yêu cầu thông tin liên hệ không chỉ định ứng dụng hoặc nền tảng di động để nộp hồ sơ.",
         ref(3, 3, "Hồ sơ đầy đủ gồm hóa đơn, bản giải trình bằng văn bản và thông tin liên hệ của người đề nghị.")),
        (24, "late_refund_interest", "Toàn bộ thư viện có quy định mức lãi suất áp dụng khi hoàn tiền chậm không? Nếu có, mức đó là bao nhiêu?",
         "Không tìm thấy quy định về lãi suất hoàn tiền chậm trong các sổ tay đã cung cấp.",
         "Mức trần hoàn tiền và hạn nộp hồ sơ không phải lãi suất; không có quy định về tiền lãi hoặc hoàn tiền chậm.",
         ref(4, 3, "Mức hoàn tiền tối đa là 200 đô la Mỹ (USD).")),
        (25, "international_shipping", "Dựa trên toàn bộ sổ tay, hãy cho biết công thức tính phí vận chuyển quốc tế.",
         "Không tìm thấy công thức tính phí vận chuyển quốc tế trong các sổ tay đã cung cấp.",
         "Hồ sơ yêu cầu hóa đơn và giải trình nhưng không quy định vận chuyển, mức phí hoặc công thức tính cước.",
         ref(5, 3, "Hồ sơ đầy đủ gồm hóa đơn, bản giải trình bằng văn bản và thông tin liên hệ của người đề nghị.")),
    ]
    audit_cases = []
    for number, topic, question, answer, rationale, nearby in negatives:
        question_id = f"q-{number:02d}"
        questions.append({"id": question_id, "kind": "unanswerable", "taskType": topic,
                          "scopeMode": "ALL_READY", "documentFixtureIds": all_ids.copy(),
                          "question": question, "history": [], "answerable": False,
                          "expectedOutcome": "NO_EVIDENCE", "expectedAnswer": answer,
                          "expectedSources": [], "expectedClaims": [], "noEvidenceTopic": topic})
        audit_cases.append({"questionId": question_id, "topic": topic,
                            "probeTerms": NO_EVIDENCE_TOPICS[topic],
                            "lexicalHits": find_topic_hits(canonical, NO_EVIDENCE_TOPICS[topic]),
                            "assessment": "NO_EVIDENCE_CANDIDATE", "rationale": rationale,
                            "nearbyEvidence": [nearby]})
    audit = {"evaluationVersion": EVALUATION_VERSION, "reviewStatus": "PENDING_REVIEW",
             "method": "static_template_review_and_full_corpus_lexical_scan",
             "checkedDocumentFixtureIds": all_ids, "checkedPassageCount": sum(map(len, canonical.values())),
             "corpusTextSha256": corpus_fingerprint(canonical),
             "limitation": "Không có từ khóa không chứng minh thiếu bằng chứng về ngữ nghĩa. Cần người duyệt toàn bộ nhãn; chỉ index fixtures, không đưa audit/ground truth vào context.",
             "cases": audit_cases}
    return questions, audit


def render_review(questions, audit):
    """A generated, human-readable review copy, never an approval record."""
    lines = [
        "# Rà soát bộ câu hỏi DocuMind — questions-vi-v3", "",
        "Trạng thái: **PENDING_REVIEW**. Người duyệt: Tuấn Anh. Chưa tự đánh dấu APPROVED.", "",
        "## Các thay đổi để duyệt", "",
        "- PRD mục 7 yêu cầu tổng hợp nhiều đoạn, chưa yêu cầu nhiều tài liệu. Đổi nhãn Q06–Q10 từ multi_source sang multi_passage. Mỗi câu có 2–3 trang/đoạn riêng, mỗi vị trí đóng góp một nhận định cần thiết. Cả năm vẫn dùng một tài liệu; bộ này chưa đo tổng hợp nhiều tài liệu (multi_document).",
        "- Viết lại cả 25 câu: giữ 5 nhiều lượt, 5 nhiều đoạn, 3 từ khóa chính xác, 7 trực tiếp và 5 ngoài nội dung. Không còn các câu chỉ thay số sổ tay. Q13 có hai đoạn trích trong cùng một đoạn DOCX nên vẫn là exact_keyword, không tính là multi_passage.",
        "- Q01–Q05: đa dạng hóa câu hỏi tiếp, đổi phạm vi từ một tài liệu sang ALL_READY (20 tài liệu) để lịch sử cung cấp danh tính tài liệu; nội dung lịch sử không nêu sẵn đáp án mới. sourceRefs chỉ là nhãn kiểm chứng, không phải context bổ sung cho mô hình.",
        "- Q06: hạn thông báo + mức tiền. Q07: hồ sơ thiếu + thời hạn lưu. Q08: quy tắc phạm vi + hạn nộp. Q09: ngày hiệu lực + mã + mức tiền. Q10: hạn thông báo + danh mục giấy tờ.",
        "- Q11–Q13: vị trí sử dụng mã, trích mã nguyên dạng và sửa mã thiếu dấu gạch nối. Q14–Q20: tổ quản lý/ngày hiệu lực, ngày nghỉ, kiểm tra ngày mua, điều kiện đóng, quyền truy cập, số tiền/đơn vị và mốc bắt đầu lưu trữ.",
        "- Q21–Q25: đổi từ phạm vi một tài liệu sang ALL_READY; câu hỏi nói rõ toàn bộ thư viện, tránh đại từ mơ hồ. Giữ năm chủ đề ngoài nội dung và lời từ chối theo từng chủ đề.",
        "- Thêm scopeMode, taskType, expectedOutcome, expectedClaims; claim có sourceIndices tính từ 0 và các mảnh văn bản để kiểm tra nhãn. exactKeywords/formatComparison dùng cho ca mã hồ sơ, noEvidenceTopic liên kết từng câu từ chối với audit. Các trường này là dữ liệu eval, không phải API DTO.",
        "- Tách bộ câu hỏi biên soạn khỏi generator tài liệu (question_suite.py); thêm no_evidence_audit.json, ràng buộc hash trong manifest, validator và unit tests. CI chạy cả validator và unit tests eval.",
        "- Giữ corpusVersion=synthetic-vi-v2, thêm evaluationVersion=questions-vi-v3. SHA-256 của 20 tài liệu và nội dung 20 canonical không đổi. Không đổi font, số tiền, thời hạn, mã hồ sơ hoặc pipeline; không chạy RAG/provider và không tuyên bố điểm chất lượng đã đạt.", "",
        "Tệp thay đổi: `eval/questions.json`, `eval/manifest.json` (thêm evaluationVersion/questionsSha256/noEvidenceAuditSha256), `eval/generate_fixtures.py`, `eval/validate.py`, `eval/question_suite.py` (mới), `eval/test_validate.py` (mới), `eval/no_evidence_audit.json` (mới), `eval/REVIEW.md` (mới), `eval/README.md`, `README.md`, `.github/workflows/ci.yml`. Corpus và backend Java không đổi.", "",
        "## Kiểm tra năm câu NO_EVIDENCE", "",
        f"Phạm vi mỗi câu: **{len(audit['checkedDocumentFixtureIds'])} tài liệu / {audit['checkedPassageCount']} trang hoặc đoạn**. PDF/DOCX/TXT được trích xuất và đối chiếu với canonical bởi validator trước khi kiểm tra corpus.", "",
        "| Câu | Chủ đề | Số vị trí khớp nhóm từ khóa | Đối chiếu nội dung |", "|---|---|---|---|",
    ]
    for case in audit["cases"]:
        lines.append(f"| {case['questionId'].replace('-', '').upper()} | {case['topic']} | {len(case['lexicalHits'])} | {case['rationale']} |")
    lines += ["", "Không có từ khóa **không chứng minh** vắng bằng chứng về ngữ nghĩa. Đây là kết quả rà soát tĩnh và cảnh báo hồi quy, vẫn cần người duyệt; không phải đo hành vi từ chối của mô hình. Các nguồn gần chủ đề trong audit giúp người duyệt phân biệt mã hồ sơ với tài khoản, tên tổ với tên người ký, danh mục giấy tờ với ứng dụng, mức trần với lãi suất, hóa đơn với cước vận chuyển. Chúng không phải citation cho lời từ chối.", "",
              "## Bản chi tiết từng câu", "",
              "Các nguồn dưới đây là ground truth. Chỉ index fixtures/; không đưa đáp án mẫu, expectedClaims, sourceRefs, canonical, audit hoặc báo cáo này vào context retrieval.", ""]
    for question in questions:
        label = question["id"].replace("-", "").upper()
        lines += [f"### {label} — {question['kind']}", "",
                  f"Phạm vi: `{question['scopeMode']}`, {len(question['documentFixtureIds'])} tài liệu.", ""]
        if question["history"]:
            lines.append("Lịch sử chuẩn:")
            lines.append("")
            lines.extend(f"- {message['role']}: {message['content']}" for message in question["history"])
            lines.append("")
        lines += [f"**Câu hỏi:** {question['question']}", "", f"**Đáp án mẫu:** {question['expectedAnswer']}", ""]
        for index, source in enumerate(question["expectedSources"], 1):
            location = f"trang {source['pageNumber']}" if source["pageNumber"] else f"đoạn {source['paragraphIndex']}"
            lines += [f"- Nguồn {index}: `{source['fixtureId']}`, {location}, offsets [{source['startOffset']},{source['endOffset']}). Trích: {source['quote']}"]
        if question["expectedClaims"]:
            lines += ["", "Các nhận định bắt buộc:", ""]
            for item in question["expectedClaims"]:
                sources = ", ".join(str(index + 1) for index in item["sourceIndices"])
                lines.append(f"- {item['text']} (nguồn {sources}).")
        else:
            lines.append("- Kỳ vọng NO_EVIDENCE, không có nguồn/nhận định thực tế; đối chiếu audit toàn corpus ở trên.")
        lines.append("")
    lines += ["## Giới hạn và cách chấm", "",
              "Giữ nguyên ngưỡng PRD: source hit@3 ≥16/20, từ chối đúng ≥4/5, ingestion ≥19/20, citation ID sai =0. Source hit@3 chỉ cần một nguồn đúng nên không chứng minh đã tổng hợp đủ. Với multi_passage, chấm thêm toàn bộ expectedClaims và nguồn hỗ trợ từng claim; không tính đáp án chỉ trả một nửa yêu cầu là trả lời đầy đủ. Cho phép diễn đạt tương đương, không áp answerFragments thành so chuỗi cứng cho đầu ra mô hình.", "",
              "Kiểm tra chuỗi/claim trong validator chỉ phát hiện nhãn lệch nguồn hoặc đáp án mẫu, không tự chứng minh quan hệ suy luận. Ngân hàng, người ký, ứng dụng, lãi suất, phí vận chuyển vẫn cần người duyệt xác nhận không có bằng chứng; nếu corpus đổi thì fingerprint/hash buộc cập nhật audit và rà lại nhãn.", "",
              "Tài liệu nền vẫn là 20 sổ tay theo mẫu gần giống nhau, có đoạn hướng dẫn lặp. Câu hỏi đa dạng hơn nhưng chưa đại diện tài liệu thực tế, nguồn mâu thuẫn hoặc bài toán nhiều tài liệu. Không dùng chính 25 câu này để tuning. Chưa có adapter chạy eval/RAG hoặc số đo provider thật trong thay đổi này.", ""]
    return "\n".join(lines)
