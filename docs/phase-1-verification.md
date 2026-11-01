# Kiểm chứng Phase 1

Ngày chạy: **2026-11-01**, Asia/Bangkok. Môi trường: Windows, Java 21.0.8, Maven Wrapper 3.10.0, Docker Desktop. Không gọi LLM/embedding provider và không cần API key.

## Kết quả thực tế

| Kiểm tra/lệnh | Kết quả |
|---|---|
| `./mvnw.cmd -B -ntp clean verify` sau sửa cấu hình AI | BUILD SUCCESS: 2 unit + 3 integration test, 0 lỗi, 0 skip; chạy DB test riêng trên local_postgres |
| `./mvnw.cmd -B -ntp verify -Ddocumind.testcontainers=true` trên phiên bản cuối | BUILD SUCCESS: **3 unit + 3 integration test**, 0 lỗi, 0 skip; property force Testcontainers=true được xác nhận trong báo cáo Failsafe |
| `python eval/generate_fixtures.py` | Tạo 20 file và 25 câu hỏi gán nhãn, PENDING_REVIEW |
| `python eval/validate.py` | PASS: số lượng/định dạng, SHA-256, lịch sử và vị trí nguồn |
| Parser độc lập `pypdf` | Đọc 8 PDF / 40 trang, text từng trang khớp canonical; từ bản corpus tiếng Việt v2, parser được đưa vào dependencies của validator/CI, không phải dependencies backend |
| `docker compose --profile standalone-db config --quiet` | Exit 0; chỉ validate cấu hình, không tạo DB Compose mới |
| Installer pgvector qua `docker cp` + `docker exec ... sh /tmp/documind-install-pgvector.sh` | Exit 0, chạy lại an toàn: pgvector 0.8.7 đã cài |
| SQL kiểm tra database `documind` | Extension 0.8.7; bảng public duy nhất là `flyway_schema_history`; không có bảng nghiệp vụ Phase 2 |
| Log JAR dev/prod | JSON có requestId; 0 dòng DEBUG, không có default-generated security password |

Integration test kiểm chứng Flyway V1, phép toán vector thật, không có bean ChatModel/EmbeddingModel, health/readiness, OpenAPI, Swagger và API chưa triển khai bị đóng. Unit test kiểm chứng lỗi 400/500 dùng ProblemDetail, không lộ exception text, request ID an toàn và MDC được khôi phục.

Lần verify đầu phát hiện OpenAI audio speech auto-config còn yêu cầu credential khi mới tắt chat/embedding. Đã tắt toàn bộ model và ChatClient auto-config; hai lần kiểm chứng thành công ở trên chạy sau khi sửa. Không dùng API key giả hoặc skip test để vượt lỗi.

## HTTP smoke test từ JAR thật

Chạy JAR tạm với profile dev trên port 18080 và profile prod trên port 18081; tái sử dụng database `documind`. Xác nhận header `X-Request-ID: phase1-http-smoke` được giữ ở dev.

| Endpoint | Dev | Prod |
|---|---|---|
| `/actuator/health` | 200, UP, không components nhạy cảm | Không kiểm tra riêng |
| `/actuator/health/liveness` | 200, UP | Không kiểm tra riêng |
| `/actuator/health/readiness` | 200, UP | 200, UP |
| `/v3/api-docs` | 200, title DocuMind Backend | 401, không truy cập tài liệu |
| `/swagger-ui/index.html` | 200, HTML Swagger UI | 401, không truy cập UI |
| `/api/v1/documents` | 401, AUTHENTICATION_REQUIRED + requestId | Không kiểm tra riêng |

Tiến trình smoke được dừng sau kiểm chứng. Container local_postgres được giữ chạy; không recreate container, không sửa/xóa các database khác. Hai database DocuMind tách biệt với các dự án có sẵn; `.env` được `git check-ignore` xác nhận bỏ qua.

## Giới hạn nghiệm thu

- Phần kỹ thuật Phase 1 đã đạt trên local và đường chạy Testcontainers. Workflow GitHub Actions đã viết, **chưa tuyên bố CI remote xanh** khi chưa push/chạy trên GitHub.
- Corpus/nhãn/rubric chờ **Tuấn Anh duyệt trước tuần 2**; validator pass không thay thế duyệt nội dung. Chưa có báo cáo RAG quality, latency hay token cost.
- Theo xác nhận của chủ dự án, giữ starter và tắt AI ở Phase 1; provider/model/chiều vector phải chốt trước Phase 2. Không tạo vector(N) theo số chiều tự đoán.
- Domain/VPS và rerank chưa chọn; không mua dịch vụ hoặc triển khai Phase 2–14.
- pgvector cài vào lớp ghi của container hiện tại: restart giữ được, recreate từ postgres:15-alpine cần chạy lại installer. Compose tùy chọn dùng image pgvector riêng cho máy mới.

## Cập nhật eval tiếng Việt — 2026-11-01

Theo yêu cầu chủ dự án, corpus chuyển từ tài liệu tiếng Anh/câu hỏi tiếng Việt sang `synthetic-vi-v2`: cả 20 tài liệu, lịch sử, câu hỏi, đáp án và đoạn nguồn đều tiếng Việt có dấu. Giữ các ID kỹ thuật, thời hạn, mã hồ sơ và số tiền gốc. Manifest ghi language=vi và giữ PENDING_REVIEW; chưa chạy lại model RAG vì Phase 1 không bật AI.

- Generator dùng ReportLab với font Noto Sans có giấy phép đi kèm, nhúng Unicode/ToUnicode vào PDF; kiểm tra font có đủ glyph cho toàn bộ nội dung.
- Validator pypdf đọc cả 40 trang, so sánh toàn bộ text với canonical NFC, kiểm tra font nhúng, DOCX/TXT, SHA-256 và offsets ground truth: PASS.
- Sinh lại toàn bộ corpus hai lần: 42 file sinh ra (20 tài liệu, 20 canonical, manifest, questions) có cùng SHA-256: PASS.
- CI được cập nhật cài eval/requirements.txt trước validator; backend Java và các migration không thay đổi trong lần chuyển ngôn ngữ này.
