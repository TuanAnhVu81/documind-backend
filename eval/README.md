# Bộ eval DocuMind — synthetic-v1

20 tài liệu tự tạo, không chứa dữ liệu cá nhân: 8 PDF (5 trang/file), 6 DOCX và 6 TXT (5 đoạn dài tương đương). Nội dung tài liệu tiếng Anh, câu hỏi tiếng Việt để kiểm tra hỏi đáp khác ngôn ngữ. Có 25 câu đã gán nhãn: 20 có đáp án (5 nhiều lượt, 5 cần nhiều nguồn, 3 từ khóa chính xác, 7 trực tiếp) và 5 không có đáp án.

Trạng thái **PENDING_REVIEW**: Tuấn Anh duyệt nội dung, vị trí nguồn và rubric trước tuần 2, sau đó cập nhật reviewStatus/reviewedAt trong manifest. Không ghi APPROVED thay người duyệt; không có kết quả chất lượng RAG trong Phase 1. Pipeline/model chưa chọn được đánh dấu UNSET, không coi fixtureVersion là phiên bản ingestion đã chạy.

```powershell
python eval/generate_fixtures.py
python eval/validate.py
```

Generator không cần thư viện ngoài, tạo file lặp lại có cùng nội dung/hash; từ chối ghi đè corpus đã APPROVED. `canonical/` chứa nội dung chuẩn để kiểm tra nhãn, không được đưa vào index retrieval cùng `fixtures/`. Validator kiểm tra hash, cấu trúc DOCX/TXT, trang PDF và vị trí ground truth; parser PDF thực được kiểm chứng thêm ở Phase 6.

`questions.json` là dữ liệu eval, không phải MessageDto của API. `documentFixtureIds` phải ánh xạ sang UUID upload thực khi chạy eval. `history` là lịch sử chuẩn; sourceRefs dùng fixture/vị trí, được adapter eval ánh xạ sang nguồn thực. `startOffset/endOffset` đếm Unicode code point trong đoạn canonical, không phải byte PDF. Khi Phase 6 có parser/pipeline, ghi version và kiểm chứng lại ánh xạ trang/đoạn; không đổi ground truth để che lỗi parser.

Rubric theo PRD mục 7:

- Source hit@3: trên 20 câu có đáp án, một trong tối đa 3 nguồn thực sự được dẫn đầu tiên phải hỗ trợ đáp án kỳ vọng. Từ chối/không nguồn tính trượt; đạt ít nhất 16/20.
- Ingestion: ít nhất 19/20 file READY sau retry tự động, không can thiệp.
- Từ chối: ít nhất 4/5 câu ngoài tài liệu có NO_EVIDENCE và không bịa thông tin. Lỗi provider/citation không tính là đúng.
- Citation ID sai trong đáp án hoàn tất phải bằng 0. Chấm riêng answer correctness, citation precision/recall; mỗi nhận định đối chiếu expectedAnswer và nguồn, không chỉ so chuỗi trả lời.
- Source refs kỳ vọng không phải `[S1]`; nhãn S1 chỉ tồn tại trong một response thực tế.

Không tuning trên 25 câu nghiệm thu. Tạo corpus và câu hỏi riêng trong `development/` khi bắt đầu tuning; mọi ablation dùng cùng bộ nghiệm thu đã duyệt. Báo cáo model/pipeline/prompt, tham số, từng ca pass/fail và ngày đo ở Phase 11.
