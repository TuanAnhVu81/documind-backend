# Bộ eval DocuMind — synthetic-vi-v2

20 tài liệu tự tạo, không chứa dữ liệu cá nhân: 8 PDF (5 trang/file), 6 DOCX và 6 TXT (5 đoạn dài tương đương). **Tài liệu, câu hỏi, lịch sử hội thoại, đáp án và đoạn nguồn đều bằng tiếng Việt có dấu**; manifest ghi `language=vi`. Có 25 câu đã gán nhãn: 20 có đáp án (5 nhiều lượt, 5 cần nhiều nguồn, 3 từ khóa chính xác, 7 trực tiếp) và 5 không có đáp án. Giữ nguyên ID kỹ thuật `handbook-XX`, mã hồ sơ, các thời hạn và số tiền của bản trước để so sánh; tên tài liệu hiển thị trong nội dung là “Sổ tay XX”.

Trạng thái **PENDING_REVIEW**: Tuấn Anh duyệt nội dung, vị trí nguồn và rubric trước tuần 2, sau đó cập nhật reviewStatus/reviewedAt trong manifest. Không ghi APPROVED thay người duyệt; không có kết quả chất lượng RAG trong Phase 1. Pipeline/model chưa chọn được đánh dấu UNSET, không coi fixtureVersion là phiên bản ingestion đã chạy.

```powershell
python -m venv .local/eval-venv
.\.local\eval-venv\Scripts\python.exe -m pip install -r eval/requirements.txt
.\.local\eval-venv\Scripts\python.exe eval/generate_fixtures.py
.\.local\eval-venv\Scripts\python.exe eval/validate.py
```

Hoặc dùng môi trường Python đã cài dependencies:

```powershell
python -m pip install -r eval/requirements.txt
python eval/generate_fixtures.py
python eval/validate.py
```

Generator dùng ReportLab với font Noto Sans đi kèm giấy phép trong `assets/fonts/`, nhúng font Unicode vào PDF; tạo file lặp lại có cùng nội dung/hash và từ chối ghi đè corpus đã APPROVED. `canonical/` chứa nội dung chuẩn Unicode NFC để kiểm tra nhãn, không được đưa vào index retrieval cùng `fixtures/`. Validator dùng pypdf đọc đủ 40 trang PDF, đối chiếu toàn bộ text tiếng Việt với canonical và kiểm tra font nhúng/ToUnicode; đồng thời kiểm tra hash, DOCX/TXT và vị trí ground truth. Kiểm thử bằng parser Java của sản phẩm vẫn thực hiện ở Phase 6.

`questions.json` là dữ liệu eval, không phải MessageDto của API. `documentFixtureIds` phải ánh xạ sang UUID upload thực khi chạy eval. `history` là lịch sử chuẩn; sourceRefs dùng fixture/vị trí, được adapter eval ánh xạ sang nguồn thực. `startOffset/endOffset` đếm Unicode code point trong đoạn canonical, không phải byte PDF. Khi Phase 6 có parser/pipeline, ghi version và kiểm chứng lại ánh xạ trang/đoạn; không đổi ground truth để che lỗi parser.

Rubric theo PRD mục 7:

- Source hit@3: trên 20 câu có đáp án, một trong tối đa 3 nguồn thực sự được dẫn đầu tiên phải hỗ trợ đáp án kỳ vọng. Từ chối/không nguồn tính trượt; đạt ít nhất 16/20.
- Ingestion: ít nhất 19/20 file READY sau retry tự động, không can thiệp.
- Từ chối: ít nhất 4/5 câu ngoài tài liệu có NO_EVIDENCE và không bịa thông tin. Lỗi provider/citation không tính là đúng.
- Citation ID sai trong đáp án hoàn tất phải bằng 0. Chấm riêng answer correctness, citation precision/recall; mỗi nhận định đối chiếu expectedAnswer và nguồn, không chỉ so chuỗi trả lời.
- Source refs kỳ vọng không phải `[S1]`; nhãn S1 chỉ tồn tại trong một response thực tế.

Không tuning trên 25 câu nghiệm thu. Tạo corpus và câu hỏi riêng trong `development/` khi bắt đầu tuning; mọi ablation dùng cùng bộ nghiệm thu đã duyệt. Báo cáo model/pipeline/prompt, tham số, từng ca pass/fail và ngày đo ở Phase 11.
