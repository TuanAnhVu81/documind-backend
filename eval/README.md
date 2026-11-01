# Bộ eval DocuMind — synthetic-vi-v2

20 tài liệu tự tạo, không chứa dữ liệu cá nhân: 8 PDF (5 trang/file), 6 DOCX và 6 TXT (5 đoạn dài tương đương). **Tài liệu, câu hỏi, lịch sử hội thoại, đáp án và đoạn nguồn đều bằng tiếng Việt có dấu**; manifest ghi `language=vi`. Có 25 câu: 20 có đáp án (5 `multi_turn`, 5 `multi_passage`, 3 `exact_keyword`, 7 `direct`) và 5 `unanswerable`. Corpus giữ phiên bản `synthetic-vi-v2`, bộ câu hỏi là **`questions-vi-v3`**; không đổi 20 tài liệu/canonical, ID kỹ thuật, thời hạn, số tiền hoặc mã hồ sơ.

[REVIEW.md](REVIEW.md) trình bày mọi thay đổi và đủ 25 câu kèm lịch sử, đáp án, trang/đoạn trích và từng nhận định để Tuấn Anh duyệt. Đây là báo cáo được generator tạo lại, không phải biên bản phê duyệt.

Trạng thái **PENDING_REVIEW**: Tuấn Anh duyệt nội dung, vị trí nguồn và rubric trước tuần 2, sau đó cập nhật reviewStatus/reviewedAt trong manifest. Không ghi APPROVED thay người duyệt; không có kết quả chất lượng RAG trong Phase 1. Pipeline/model chưa chọn được đánh dấu UNSET, không coi fixtureVersion là phiên bản ingestion đã chạy.

```powershell
python -m venv .local/eval-venv
.\.local\eval-venv\Scripts\python.exe -m pip install -r eval/requirements.txt
.\.local\eval-venv\Scripts\python.exe eval/generate_fixtures.py
.\.local\eval-venv\Scripts\python.exe eval/validate.py
.\.local\eval-venv\Scripts\python.exe -m unittest discover -s eval -p "test_*.py"
```

Hoặc dùng môi trường Python đã cài dependencies:

```powershell
python -m pip install -r eval/requirements.txt
python eval/generate_fixtures.py
python eval/validate.py
python -m unittest discover -s eval -p "test_*.py"
```

Generator dùng ReportLab với font Noto Sans đi kèm giấy phép trong `assets/fonts/`, nhúng font Unicode vào PDF; tạo file lặp lại có cùng nội dung/hash và từ chối ghi đè corpus đã APPROVED. `canonical/` chứa nội dung chuẩn Unicode NFC để kiểm tra nhãn, không được đưa vào index retrieval cùng `fixtures/`. Validator dùng pypdf đọc đủ 40 trang PDF, đối chiếu toàn bộ text tiếng Việt với canonical và kiểm tra font nhúng/ToUnicode; đồng thời kiểm tra hash, DOCX/TXT và vị trí ground truth. Kiểm thử bằng parser Java của sản phẩm vẫn thực hiện ở Phase 6.

`question_suite.py` chứa 25 câu biên soạn, tách khỏi template tài liệu trong `generate_fixtures.py`. `questions.json` là dữ liệu eval, không phải MessageDto của API. `startOffset/endOffset` đếm Unicode code point trong đoạn canonical, không phải byte PDF. Khi Phase 6 có parser/pipeline, ghi version và kiểm chứng lại ánh xạ trang/đoạn; không đổi ground truth để che lỗi parser.

## Định nghĩa nhãn và phạm vi

- **multi_passage (Q06–Q10):** đáp án cần ít nhất hai vị trí `(fixtureId, canonicalSection)` riêng biệt. Hai vị trí được phép nằm trong cùng tài liệu, đúng yêu cầu “tổng hợp nhiều đoạn” ở PRD mục 7. Mỗi vị trí phải hỗ trợ một claim riêng; hai đoạn trích/offset khác nhau trong cùng trang/đoạn không tính là hai passage. Q09 dùng ba vị trí. Nhãn mơ hồ `multi_source` đã bỏ và validator từ chối nhãn này.
- **multi_document:** nếu dùng sau này phải cần bằng chứng từ ít nhất hai `fixtureId` khác nhau. Bộ 25 câu hiện tại chưa đo loại này; không được gọi Q06–Q10 là tổng hợp nhiều tài liệu.
- **multi_turn (Q01–Q05):** mỗi câu có một lượt lịch sử chuẩn, cung cấp danh tính tài liệu nhưng không nêu sẵn thông tin đang hỏi tiếp. Cả năm tìm trên ALL_READY để không cho bộ lọc một tài liệu tiết lộ sẵn danh tính cần resolve.
- **exact_keyword (Q11–Q13):** dùng mã REF nguyên dạng, gồm sử dụng mã, trích đúng mã và phát hiện/sửa dấu gạch nối. Q13 có hai nguồn trích trong cùng đoạn, vẫn là exact_keyword.
- **direct (Q14–Q20):** một vị trí chứng cứ cho mỗi câu; hỏi tổ quản lý/ngày hiệu lực, ngày nghỉ, kiểm tra ngày mua, điều kiện đóng hồ sơ, quyền truy cập, số tiền/đơn vị và mốc thời gian.
- **unanswerable (Q21–Q25):** không có chứng cứ trong toàn bộ 20 tài liệu; kỳ vọng NO_EVIDENCE với sources/claims/history rỗng. Không coi lỗi kỹ thuật hoặc yêu cầu làm rõ là từ chối đúng.

Khi viết adapter eval ở Phase 11:

- `scopeMode=SELECTED`: ánh xạ documentFixtureIds thành UUID upload và gửi documentIds. Áp dụng cho Q06–Q20.
- `scopeMode=ALL_READY`: **bỏ trường documentIds**, không gửi [] hoặc null. documentFixtureIds ghi toàn bộ 20 ID để mô tả snapshot corpus dự kiến, không phải yêu cầu lọc SELECTED. Áp dụng cho Q01–Q05 và Q21–Q25; dùng tài khoản eval riêng chỉ có 20 tài liệu này READY, xác minh đủ corpus trước chạy.
- History sourceRefs, expectedSources, expectedClaims, canonical, audit và REVIEW là nhãn kiểm chứng, **không đưa vào prompt/index**. Chỉ index fixtures/. Lịch sử gửi mô hình chỉ gồm nội dung USER/ASSISTANT và nhãn citation thực do adapter ánh xạ, không đính kèm các đoạn trích ground truth làm context bổ sung.
- expectedClaims ghi claim → sourceIndices (bắt đầu từ 0). evidenceFragments/answerFragments kiểm tra tính nhất quán nhãn; không dùng so chuỗi cứng để chấm đầu ra mô hình, vẫn cần rubric ngữ nghĩa.
- expectedSources là các span chứng cứ gán nhãn, không ấn định số citation phải hiển thị. Hai span trong cùng passage (Q13) có thể ánh xạ về cùng một chunk thực tế; adapter hợp nhất citation theo chunk nhưng vẫn chấm đủ các claim.

## Kiểm tra NO_EVIDENCE trên toàn corpus

`no_evidence_audit.json` ghi snapshot 20 tài liệu/100 trang hoặc đoạn, fingerprint text, nhóm từ khóa, kết quả quét và lập luận cho từng câu. Năm chủ đề được đối chiếu là tài khoản ngân hàng, giám đốc pháp lý ký ban hành, ứng dụng di động nộp hồ sơ, lãi suất hoàn tiền chậm và công thức phí vận chuyển quốc tế. Tất cả hiện không có vị trí khớp nhóm từ khóa sau chuẩn hóa Unicode/hoa-thường/khoảng trắng. Các trường `nearbyEvidence` giải thích thông tin gần chủ đề nhưng không phải đáp án; không dùng chúng làm citation cho NO_EVIDENCE.

Validator xác minh text được trích xuất khớp canonical, audit bao phủ đủ 20 tài liệu, fingerprint và hash còn đúng, kết quả từ khóa không bị cũ. Từ khóa xuất hiện lại làm validator yêu cầu rà nhãn. **Không có từ khóa không chứng minh không có đáp án về ngữ nghĩa**; đây là đối chiếu tĩnh và cảnh báo hồi quy, không phải kiểm thử hành vi từ chối của mô hình hay phê duyệt tự động. Manifest và audit giữ PENDING_REVIEW.

Rubric theo PRD mục 7:

- Source hit@3: trên 20 câu có đáp án, một trong tối đa 3 nguồn thực sự được dẫn đầu tiên phải hỗ trợ đáp án kỳ vọng. Từ chối/không nguồn tính trượt; đạt ít nhất 16/20.
- Ingestion: ít nhất 19/20 file READY sau retry tự động, không can thiệp.
- Từ chối: ít nhất 4/5 câu ngoài tài liệu có NO_EVIDENCE và không bịa thông tin. Lỗi provider/citation không tính là đúng.
- Citation ID sai trong đáp án hoàn tất phải bằng 0. Chấm riêng answer correctness, citation precision/recall; mỗi nhận định đối chiếu expectedAnswer và nguồn, không chỉ so chuỗi trả lời.
- Source refs kỳ vọng không phải `[S1]`; nhãn S1 chỉ tồn tại trong một response thực tế.
- Với multi_passage, chấm riêng việc trả lời đủ từng expectedClaim và nguồn hỗ trợ. Source hit@3 đạt không đồng nghĩa đã tổng hợp đủ các đoạn; không đổi ngưỡng PRD trong lần rà soát này.

Không tuning trên 25 câu nghiệm thu. Tạo corpus và câu hỏi riêng trong `development/` khi bắt đầu tuning; mọi ablation dùng cùng bộ nghiệm thu đã duyệt. Báo cáo model/pipeline/prompt, tham số, từng ca pass/fail và ngày đo ở Phase 11.
