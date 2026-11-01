# Kiểm chứng Phase 1

Ngày chạy: **2026-11-01**, Asia/Bangkok. Môi trường: Windows, Java 21.0.8, Maven Wrapper 3.10.0, Docker Desktop. Không gọi LLM/embedding provider và không cần API key.

## Kết quả thực tế

| Kiểm tra/lệnh | Kết quả |
|---|---|
| `./mvnw.cmd -B -ntp clean verify` sau sửa cấu hình AI | BUILD SUCCESS: 2 unit + 3 integration test, 0 lỗi, 0 skip; chạy DB test riêng trên local_postgres |
| `./mvnw.cmd -B -ntp verify -Ddocumind.testcontainers=true` trên phiên bản cuối | BUILD SUCCESS: **3 unit + 3 integration test**, 0 lỗi, 0 skip; property force Testcontainers=true được xác nhận trong báo cáo Failsafe |
| `python eval/generate_fixtures.py` | Tạo 20 file và 25 câu hỏi gán nhãn, PENDING_REVIEW |
| `python eval/validate.py` | PASS: số lượng/định dạng, SHA-256, lịch sử và vị trí nguồn |
| Parser độc lập `pypdf` có sẵn trong môi trường | Đọc 8 PDF / 40 trang, text từng trang khớp canonical; không thêm dependency này vào app hoặc CI validator |
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
