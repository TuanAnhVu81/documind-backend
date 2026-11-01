# Rà soát bộ câu hỏi DocuMind — questions-vi-v3

Trạng thái: **APPROVED**. Người duyệt: Tuấn Anh. Chưa tự đánh dấu APPROVED.

## Các thay đổi để duyệt

- PRD mục 7 yêu cầu tổng hợp nhiều đoạn, chưa yêu cầu nhiều tài liệu. Đổi nhãn Q06–Q10 từ multi_source sang multi_passage. Mỗi câu có 2–3 trang/đoạn riêng, mỗi vị trí đóng góp một nhận định cần thiết. Cả năm vẫn dùng một tài liệu; bộ này chưa đo tổng hợp nhiều tài liệu (multi_document).
- Viết lại cả 25 câu: giữ 5 nhiều lượt, 5 nhiều đoạn, 3 từ khóa chính xác, 7 trực tiếp và 5 ngoài nội dung. Không còn các câu chỉ thay số sổ tay. Q13 có hai đoạn trích trong cùng một đoạn DOCX nên vẫn là exact_keyword, không tính là multi_passage.
- Q01–Q05: đa dạng hóa câu hỏi tiếp, đổi phạm vi từ một tài liệu sang ALL_READY (20 tài liệu) để lịch sử cung cấp danh tính tài liệu; nội dung lịch sử không nêu sẵn đáp án mới. sourceRefs chỉ là nhãn kiểm chứng, không phải context bổ sung cho mô hình.
- Q06: hạn thông báo + mức tiền. Q07: hồ sơ thiếu + thời hạn lưu. Q08: quy tắc phạm vi + hạn nộp. Q09: ngày hiệu lực + mã + mức tiền. Q10: hạn thông báo + danh mục giấy tờ.
- Q11–Q13: vị trí sử dụng mã, trích mã nguyên dạng và sửa mã thiếu dấu gạch nối. Q14–Q20: tổ quản lý/ngày hiệu lực, ngày nghỉ, kiểm tra ngày mua, điều kiện đóng, quyền truy cập, số tiền/đơn vị và mốc bắt đầu lưu trữ.
- Q21–Q25: đổi từ phạm vi một tài liệu sang ALL_READY; câu hỏi nói rõ toàn bộ thư viện, tránh đại từ mơ hồ. Giữ năm chủ đề ngoài nội dung và lời từ chối theo từng chủ đề.
- Thêm scopeMode, taskType, expectedOutcome, expectedClaims; claim có sourceIndices tính từ 0 và các mảnh văn bản để kiểm tra nhãn. exactKeywords/formatComparison dùng cho ca mã hồ sơ, noEvidenceTopic liên kết từng câu từ chối với audit. Các trường này là dữ liệu eval, không phải API DTO.
- Tách bộ câu hỏi biên soạn khỏi generator tài liệu (question_suite.py); thêm no_evidence_audit.json, ràng buộc hash trong manifest, validator và unit tests. CI chạy cả validator và unit tests eval.
- Giữ corpusVersion=synthetic-vi-v2, thêm evaluationVersion=questions-vi-v3. SHA-256 của 20 tài liệu và nội dung 20 canonical không đổi. Không đổi font, số tiền, thời hạn, mã hồ sơ hoặc pipeline; không chạy RAG/provider và không tuyên bố điểm chất lượng đã đạt.

Tệp thay đổi: `eval/questions.json`, `eval/manifest.json` (thêm evaluationVersion/questionsSha256/noEvidenceAuditSha256), `eval/generate_fixtures.py`, `eval/validate.py`, `eval/question_suite.py` (mới), `eval/test_validate.py` (mới), `eval/no_evidence_audit.json` (mới), `eval/REVIEW.md` (mới), `eval/README.md`, `README.md`, `.github/workflows/ci.yml`. Corpus và backend Java không đổi.

## Kiểm tra năm câu NO_EVIDENCE

Phạm vi mỗi câu: **20 tài liệu / 100 trang hoặc đoạn**. PDF/DOCX/TXT được trích xuất và đối chiếu với canonical bởi validator trước khi kiểm tra corpus.

| Câu | Chủ đề | Số vị trí khớp nhóm từ khóa | Đối chiếu nội dung |
|---|---|---|---|
| Q21 | bank_account | 0 | Các sổ tay nêu mã hồ sơ REF và số tiền, không nêu ngân hàng hoặc tài khoản nhận tiền. Mã REF không phải số tài khoản. |
| Q22 | legal_signatory | 0 | Phần mở đầu chỉ nêu tổ dịch vụ và ngày hiệu lực; không có tên người ký hoặc giám đốc pháp lý. |
| Q23 | mobile_application | 0 | Danh mục giấy tờ và yêu cầu thông tin liên hệ không chỉ định ứng dụng hoặc nền tảng di động để nộp hồ sơ. |
| Q24 | late_refund_interest | 0 | Mức trần hoàn tiền và hạn nộp hồ sơ không phải lãi suất; không có quy định về tiền lãi hoặc hoàn tiền chậm. |
| Q25 | international_shipping | 0 | Hồ sơ yêu cầu hóa đơn và giải trình nhưng không quy định vận chuyển, mức phí hoặc công thức tính cước. |

Không có từ khóa **không chứng minh** vắng bằng chứng về ngữ nghĩa. Đây là kết quả rà soát tĩnh và cảnh báo hồi quy, vẫn cần người duyệt; không phải đo hành vi từ chối của mô hình. Các nguồn gần chủ đề trong audit giúp người duyệt phân biệt mã hồ sơ với tài khoản, tên tổ với tên người ký, danh mục giấy tờ với ứng dụng, mức trần với lãi suất, hóa đơn với cước vận chuyển. Chúng không phải citation cho lời từ chối.

## Bản chi tiết từng câu

Các nguồn dưới đây là ground truth. Chỉ index fixtures/; không đưa đáp án mẫu, expectedClaims, sourceRefs, canonical, audit hoặc báo cáo này vào context retrieval.

### Q01 — multi_turn

Phạm vi: `ALL_READY`, 20 tài liệu.

Lịch sử chuẩn:

- USER: Sổ tay 01 thuộc tổ dịch vụ nào?
- ASSISTANT: Sổ tay 01 thuộc Tổ dịch vụ 01.

**Câu hỏi:** Vậy tổ đó phải thông báo yêu cầu hoàn tiền trong bao lâu, có tính ngày nghỉ không?

**Đáp án mẫu:** Phải thông báo trong vòng 8 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.

- Nguồn 1: `handbook-01`, trang 2, offsets [18,143). Trích: Điều kiện hoàn tiền thứ nhất: thông báo cho bộ phận tiếp nhận trong vòng 8 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.

Các nhận định bắt buộc:

- Hạn thông báo là 8 ngày kể từ ngày mua, gồm ngày nghỉ và lễ. (nguồn 1).

### Q02 — multi_turn

Phạm vi: `ALL_READY`, 20 tài liệu.

Lịch sử chuẩn:

- USER: Hồ sơ đầy đủ theo Sổ tay 02 gồm những gì?
- ASSISTANT: Hồ sơ gồm hóa đơn, bản giải trình bằng văn bản và thông tin liên hệ của người đề nghị.

**Câu hỏi:** Nếu thiếu một trong các thành phần đó thì hồ sơ được xử lý thế nào?

**Đáp án mẫu:** Hồ sơ thiếu thành phần phải được trả lại để bổ sung trước khi xét duyệt.

- Nguồn 1: `handbook-02`, trang 3, offsets [276,348). Trích: Hồ sơ thiếu thành phần phải được trả lại để bổ sung trước khi xét duyệt.

Các nhận định bắt buộc:

- Hồ sơ thiếu phải trả lại để bổ sung trước xét duyệt. (nguồn 1).

### Q03 — multi_turn

Phạm vi: `ALL_READY`, 20 tài liệu.

Lịch sử chuẩn:

- USER: Mã hồ sơ của Sổ tay 03 là gì?
- ASSISTANT: Mã hồ sơ là REF-2026-003.

**Câu hỏi:** Còn mức hoàn tiền tối đa cho hồ sơ đó là bao nhiêu?

**Đáp án mẫu:** Mức hoàn tiền tối đa là 175 đô la Mỹ (USD).

- Nguồn 1: `handbook-03`, trang 3, offsets [129,172). Trích: Mức hoàn tiền tối đa là 175 đô la Mỹ (USD).

Các nhận định bắt buộc:

- Mức trần hoàn tiền là 175 USD. (nguồn 1).

### Q04 — multi_turn

Phạm vi: `ALL_READY`, 20 tài liệu.

Lịch sử chuẩn:

- USER: Cho biết mã hồ sơ trong Sổ tay 04.
- ASSISTANT: Mã hồ sơ là REF-2026-004.

**Câu hỏi:** Khi sao chép mã này, những chi tiết nào phải giữ nguyên?

**Đáp án mẫu:** Phải giữ nguyên dấu gạch nối và các chữ số 0 ở đầu mỗi phần của mã.

- Nguồn 1: `handbook-04`, trang 4, offsets [202,282). Trích: Khi sao chép phải giữ nguyên dấu gạch nối và các chữ số 0 ở đầu mỗi phần của mã.

Các nhận định bắt buộc:

- Giữ dấu gạch nối và chữ số 0 đầu mỗi phần. (nguồn 1).

### Q05 — multi_turn

Phạm vi: `ALL_READY`, 20 tài liệu.

Lịch sử chuẩn:

- USER: Sổ tay 05 thuộc tổ nào?
- ASSISTANT: Sổ tay 05 thuộc Tổ dịch vụ 05.

**Câu hỏi:** Sau khi đóng hồ sơ của tổ đó, phải giữ chứng từ thêm bao nhiêu ngày?

**Đáp án mẫu:** Phải lưu giữ chứng từ trong 35 ngày kể từ ngày đóng hồ sơ, tính cả ngày nghỉ và ngày lễ.

- Nguồn 1: `handbook-05`, trang 5, offsets [18,121). Trích: Chứng từ liên quan phải được lưu giữ trong 35 ngày kể từ ngày đóng hồ sơ, tính cả ngày nghỉ và ngày lễ.

Các nhận định bắt buộc:

- Lưu giữ 35 ngày kể từ ngày đóng hồ sơ. (nguồn 1).

### Q06 — multi_passage

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Theo Sổ tay 06, hãy nêu cả thời hạn thông báo yêu cầu và mức hoàn tiền tối đa.

**Đáp án mẫu:** Phải thông báo trong vòng 13 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ; mức hoàn tiền tối đa là 250 đô la Mỹ (USD).

- Nguồn 1: `handbook-06`, trang 2, offsets [18,144). Trích: Điều kiện hoàn tiền thứ nhất: thông báo cho bộ phận tiếp nhận trong vòng 13 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.
- Nguồn 2: `handbook-06`, trang 3, offsets [129,172). Trích: Mức hoàn tiền tối đa là 250 đô la Mỹ (USD).

Các nhận định bắt buộc:

- Hạn thông báo 13 ngày kể từ ngày mua. (nguồn 1).
- Mức trần 250 USD. (nguồn 2).

### Q07 — multi_passage

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Trong Sổ tay 07, hồ sơ thiếu giấy tờ được xử lý thế nào và chứng từ phải giữ bao lâu sau khi đóng hồ sơ?

**Đáp án mẫu:** Hồ sơ thiếu thành phần phải được trả lại để bổ sung trước khi xét duyệt. Chứng từ được lưu giữ trong 37 ngày kể từ ngày đóng hồ sơ, tính cả ngày nghỉ và ngày lễ.

- Nguồn 1: `handbook-07`, trang 3, offsets [276,348). Trích: Hồ sơ thiếu thành phần phải được trả lại để bổ sung trước khi xét duyệt.
- Nguồn 2: `handbook-07`, trang 5, offsets [18,121). Trích: Chứng từ liên quan phải được lưu giữ trong 37 ngày kể từ ngày đóng hồ sơ, tính cả ngày nghỉ và ngày lễ.

Các nhận định bắt buộc:

- Hồ sơ thiếu phải trả lại để bổ sung. (nguồn 1).
- Lưu giữ chứng từ 37 ngày sau đóng hồ sơ. (nguồn 2).

### Q08 — multi_passage

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Sổ tay 08 có cho lấy thời hạn của tổ khác để thay thế không? Hãy nêu thời hạn nộp hồ sơ đúng của sổ tay này.

**Đáp án mẫu:** Không được lấy thời hạn của tổ khác để thay thế. Phải nộp hồ sơ đầy đủ trong vòng 22 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.

- Nguồn 1: `handbook-08`, trang 1, offsets [146,256). Trích: Mỗi tổ khác sử dụng sổ tay riêng; không được lấy thời hạn của tổ khác để thay thế quy định trong tài liệu này.
- Nguồn 2: `handbook-08`, trang 3, offsets [18,128). Trích: Điều kiện hoàn tiền thứ hai: nộp hồ sơ đầy đủ trong vòng 22 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.

Các nhận định bắt buộc:

- Không thay bằng thời hạn của tổ khác. (nguồn 1).
- Hạn nộp hồ sơ 22 ngày kể từ ngày mua. (nguồn 2).

### Q09 — multi_passage

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Tóm tắt Sổ tay 09 theo ba mục: ngày hiệu lực, mã hồ sơ phải ghi trên đơn và mức hoàn tiền tối đa.

**Đáp án mẫu:** Ngày hiệu lực: 01/10/2026. Mã hồ sơ phải ghi trên mọi đơn đề nghị hoàn tiền: REF-2026-009. Mức hoàn tiền tối đa: 325 đô la Mỹ (USD).

- Nguồn 1: `handbook-09`, đoạn 1, offsets [18,80). Trích: Sổ tay 09 thuộc Tổ dịch vụ 09, có hiệu lực từ ngày 01/10/2026.
- Nguồn 2: `handbook-09`, đoạn 4, offsets [18,111). Trích: Mã hồ sơ chính xác là REF-2026-009. Người nộp phải ghi mã này trên mọi đơn đề nghị hoàn tiền.
- Nguồn 3: `handbook-09`, đoạn 3, offsets [129,172). Trích: Mức hoàn tiền tối đa là 325 đô la Mỹ (USD).

Các nhận định bắt buộc:

- Hiệu lực từ 01/10/2026. (nguồn 1).
- Mã REF-2026-009 phải ghi trên mọi đơn. (nguồn 2).
- Mức trần 325 USD. (nguồn 3).

### Q10 — multi_passage

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Theo Sổ tay 10, cần thông báo trong thời hạn nào và một bộ hồ sơ đầy đủ phải gồm những gì?

**Đáp án mẫu:** Cần thông báo trong vòng 17 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ. Hồ sơ gồm hóa đơn, bản giải trình bằng văn bản và thông tin liên hệ của người đề nghị.

- Nguồn 1: `handbook-10`, đoạn 2, offsets [18,144). Trích: Điều kiện hoàn tiền thứ nhất: thông báo cho bộ phận tiếp nhận trong vòng 17 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.
- Nguồn 2: `handbook-10`, đoạn 3, offsets [182,275). Trích: Hồ sơ đầy đủ gồm hóa đơn, bản giải trình bằng văn bản và thông tin liên hệ của người đề nghị.

Các nhận định bắt buộc:

- Hạn thông báo 17 ngày kể từ ngày mua. (nguồn 1).
- Hồ sơ có hóa đơn, giải trình và liên hệ. (nguồn 2).

### Q11 — exact_keyword

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Trong Sổ tay 11, mã REF-2026-011 phải được ghi ở đâu?

**Đáp án mẫu:** Mã REF-2026-011 phải được ghi trên mọi đơn đề nghị hoàn tiền.

- Nguồn 1: `handbook-11`, đoạn 4, offsets [18,111). Trích: Mã hồ sơ chính xác là REF-2026-011. Người nộp phải ghi mã này trên mọi đơn đề nghị hoàn tiền.

Các nhận định bắt buộc:

- Ghi REF-2026-011 trên mọi đơn đề nghị hoàn tiền. (nguồn 1).

### Q12 — exact_keyword

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Ghi lại mã hồ sơ đầy đủ của Sổ tay 12, giữ nguyên dấu gạch nối và các chữ số 0.

**Đáp án mẫu:** REF-2026-012

- Nguồn 1: `handbook-12`, đoạn 4, offsets [18,53). Trích: Mã hồ sơ chính xác là REF-2026-012.

Các nhận định bắt buộc:

- Mã hồ sơ là REF-2026-012. (nguồn 1).

### Q13 — exact_keyword

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Một người ghi REF2026013 vào đơn theo Sổ tay 13. Mã đó đã đúng định dạng chưa? Hãy sửa lại nếu cần.

**Đáp án mẫu:** Chưa đúng: mã chính xác là REF-2026-013, cần giữ dấu gạch nối và các chữ số 0 ở đầu mỗi phần của mã.

- Nguồn 1: `handbook-13`, đoạn 4, offsets [18,53). Trích: Mã hồ sơ chính xác là REF-2026-013.
- Nguồn 2: `handbook-13`, đoạn 4, offsets [202,282). Trích: Khi sao chép phải giữ nguyên dấu gạch nối và các chữ số 0 ở đầu mỗi phần của mã.

Các nhận định bắt buộc:

- Mã đúng là REF-2026-013. (nguồn 1).
- Phải giữ dấu gạch nối và chữ số 0. (nguồn 2).

### Q14 — direct

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Sổ tay 14 thuộc tổ dịch vụ nào và có hiệu lực từ ngày nào?

**Đáp án mẫu:** Sổ tay 14 thuộc Tổ dịch vụ 14, có hiệu lực từ ngày 01/10/2026.

- Nguồn 1: `handbook-14`, đoạn 1, offsets [18,80). Trích: Sổ tay 14 thuộc Tổ dịch vụ 14, có hiệu lực từ ngày 01/10/2026.

Các nhận định bắt buộc:

- Tổ dịch vụ 14 quản lý; hiệu lực 01/10/2026. (nguồn 1).

### Q15 — direct

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Thời hạn thông báo hoàn tiền trong Sổ tay 15 có loại trừ ngày nghỉ và ngày lễ không?

**Đáp án mẫu:** Không. Thời hạn thông báo tính cả ngày nghỉ và ngày lễ.

- Nguồn 1: `handbook-15`, đoạn 2, offsets [18,144). Trích: Điều kiện hoàn tiền thứ nhất: thông báo cho bộ phận tiếp nhận trong vòng 22 ngày kể từ ngày mua, tính cả ngày nghỉ và ngày lễ.

Các nhận định bắt buộc:

- Không loại trừ ngày nghỉ/lễ. (nguồn 1).

### Q16 — direct

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Theo Sổ tay 16, phải kiểm tra ngày nào trước khi xác nhận đã nhận thông báo?

**Đáp án mẫu:** Bộ phận tiếp nhận phải kiểm tra ngày mua được ghi nhận.

- Nguồn 1: `handbook-16`, đoạn 2, offsets [198,313). Trích: Bộ phận tiếp nhận kiểm tra ngày mua được ghi nhận trước khi xác nhận đã nhận thông báo của người đề nghị hoàn tiền.

Các nhận định bắt buộc:

- Kiểm tra ngày mua đã ghi nhận. (nguồn 1).

### Q17 — direct

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Theo Sổ tay 17, có thể đóng hồ sơ trước khi ghi nhận quyết định xử lý không?

**Đáp án mẫu:** Không. Chỉ đóng hồ sơ sau khi đã ghi nhận quyết định xử lý.

- Nguồn 1: `handbook-17`, đoạn 5, offsets [131,183). Trích: Chỉ đóng hồ sơ sau khi đã ghi nhận quyết định xử lý.

Các nhận định bắt buộc:

- Ghi nhận quyết định trước khi đóng hồ sơ. (nguồn 1).

### Q18 — direct

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Theo Sổ tay 18, ai được cấp quyền truy cập chứng từ đã lưu cùng hồ sơ?

**Đáp án mẫu:** Chỉ tổ dịch vụ chịu trách nhiệm giải quyết mới được cấp quyền truy cập.

- Nguồn 1: `handbook-18`, đoạn 5, offsets [184,290). Trích: Chứng từ phải được lưu cùng hồ sơ; chỉ tổ dịch vụ chịu trách nhiệm giải quyết mới được cấp quyền truy cập.

Các nhận định bắt buộc:

- Chỉ tổ chịu trách nhiệm giải quyết được truy cập. (nguồn 1).

### Q19 — direct

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Mức hoàn tiền tối đa theo Sổ tay 19 là bao nhiêu và dùng đơn vị tiền nào?

**Đáp án mẫu:** Mức hoàn tiền tối đa là 575 đô la Mỹ (USD).

- Nguồn 1: `handbook-19`, đoạn 3, offsets [129,172). Trích: Mức hoàn tiền tối đa là 575 đô la Mỹ (USD).

Các nhận định bắt buộc:

- Mức trần 575 USD. (nguồn 1).

### Q20 — direct

Phạm vi: `SELECTED`, 1 tài liệu.

**Câu hỏi:** Theo Sổ tay 20, thời gian lưu giữ chứng từ bắt đầu từ ngày mua hàng hay ngày đóng hồ sơ?

**Đáp án mẫu:** Thời gian lưu giữ bắt đầu kể từ ngày đóng hồ sơ, không phải ngày mua hàng.

- Nguồn 1: `handbook-20`, đoạn 5, offsets [18,121). Trích: Chứng từ liên quan phải được lưu giữ trong 50 ngày kể từ ngày đóng hồ sơ, tính cả ngày nghỉ và ngày lễ.

Các nhận định bắt buộc:

- Mốc bắt đầu là ngày đóng hồ sơ. (nguồn 1).

### Q21 — unanswerable

Phạm vi: `ALL_READY`, 20 tài liệu.

**Câu hỏi:** Tra cứu toàn bộ các sổ tay: số tài khoản ngân hàng được chỉ định để nhận tiền hoàn là gì?

**Đáp án mẫu:** Không tìm thấy số tài khoản ngân hàng nhận tiền hoàn trong các sổ tay đã cung cấp.

- Kỳ vọng NO_EVIDENCE, không có nguồn/nhận định thực tế; đối chiếu audit toàn corpus ở trên.

### Q22 — unanswerable

Phạm vi: `ALL_READY`, 20 tài liệu.

**Câu hỏi:** Trong toàn bộ thư viện, có nêu họ tên giám đốc pháp lý ký ban hành các sổ tay không? Nếu có, cho biết tên.

**Đáp án mẫu:** Không tìm thấy họ tên giám đốc pháp lý ký ban hành trong các sổ tay đã cung cấp.

- Kỳ vọng NO_EVIDENCE, không có nguồn/nhận định thực tế; đối chiếu audit toàn corpus ở trên.

### Q23 — unanswerable

Phạm vi: `ALL_READY`, 20 tài liệu.

**Câu hỏi:** Tra cứu toàn bộ sổ tay: ứng dụng di động cụ thể nào được chỉ định để nộp hồ sơ hoàn tiền?

**Đáp án mẫu:** Không tìm thấy tên ứng dụng di động được chỉ định để nộp hồ sơ trong các sổ tay đã cung cấp.

- Kỳ vọng NO_EVIDENCE, không có nguồn/nhận định thực tế; đối chiếu audit toàn corpus ở trên.

### Q24 — unanswerable

Phạm vi: `ALL_READY`, 20 tài liệu.

**Câu hỏi:** Toàn bộ thư viện có quy định mức lãi suất áp dụng khi hoàn tiền chậm không? Nếu có, mức đó là bao nhiêu?

**Đáp án mẫu:** Không tìm thấy quy định về lãi suất hoàn tiền chậm trong các sổ tay đã cung cấp.

- Kỳ vọng NO_EVIDENCE, không có nguồn/nhận định thực tế; đối chiếu audit toàn corpus ở trên.

### Q25 — unanswerable

Phạm vi: `ALL_READY`, 20 tài liệu.

**Câu hỏi:** Dựa trên toàn bộ sổ tay, hãy cho biết công thức tính phí vận chuyển quốc tế.

**Đáp án mẫu:** Không tìm thấy công thức tính phí vận chuyển quốc tế trong các sổ tay đã cung cấp.

- Kỳ vọng NO_EVIDENCE, không có nguồn/nhận định thực tế; đối chiếu audit toàn corpus ở trên.

## Giới hạn và cách chấm

Giữ nguyên ngưỡng PRD: source hit@3 ≥16/20, từ chối đúng ≥4/5, ingestion ≥19/20, citation ID sai =0. Source hit@3 chỉ cần một nguồn đúng nên không chứng minh đã tổng hợp đủ. Với multi_passage, chấm thêm toàn bộ expectedClaims và nguồn hỗ trợ từng claim; không tính đáp án chỉ trả một nửa yêu cầu là trả lời đầy đủ. Cho phép diễn đạt tương đương, không áp answerFragments thành so chuỗi cứng cho đầu ra mô hình.

Kiểm tra chuỗi/claim trong validator chỉ phát hiện nhãn lệch nguồn hoặc đáp án mẫu, không tự chứng minh quan hệ suy luận. Ngân hàng, người ký, ứng dụng, lãi suất, phí vận chuyển vẫn cần người duyệt xác nhận không có bằng chứng; nếu corpus đổi thì fingerprint/hash buộc cập nhật audit và rà lại nhãn.

Tài liệu nền vẫn là 20 sổ tay theo mẫu gần giống nhau, có đoạn hướng dẫn lặp. Câu hỏi đa dạng hơn nhưng chưa đại diện tài liệu thực tế, nguồn mâu thuẫn hoặc bài toán nhiều tài liệu. Không dùng chính 25 câu này để tuning. Chưa có adapter chạy eval/RAG hoặc số đo provider thật trong thay đổi này.
