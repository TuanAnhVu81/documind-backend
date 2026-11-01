# ADR 0001 — Nền tảng local Phase 1

Ngày: 2026-11-01. Trạng thái: quyết định nền tảng đã chốt; model AI và dữ liệu eval còn mốc duyệt riêng.

## Bối cảnh

Repository hiện có là `documind-backend/`, package `com.documind`; giữ nguyên vị trí, không tạo backend lồng nhau. Chủ dự án yêu cầu chỉ Phase 1 và tái sử dụng PostgreSQL Docker hiện có. POM đã chọn Java 21, Spring Boot 4.1.2, Spring AI 2.0.1, Maven Wrapper 3.10.0; giữ bộ phiên bản này sau khi xác minh build thực tế, không nâng/hạ phiên bản tùy ý.

## Quyết định

- Spring AI 2.0.x hỗ trợ Boot 4.0/4.1; springdoc 3.2.0 tương thích Boot 4.1. Các dependency managed (Flyway/JDBC/JUnit/Testcontainers) lấy từ Boot BOM; Testcontainers resolved là 2.0.5. JaCoCo ghim 0.8.14.
- Chủ dự án xác nhận **giữ OpenAI starter, tắt AI ở Phase 1; chốt provider/model và số chiều trước Phase 2**. Tắt chat, embedding/text/multimodal, audio speech/transcription, image, moderation và ChatClient auto-config; không dùng API key giả để vượt lỗi khởi động. Không có vector(N) trong schema Phase 1.
- `local_postgres` dùng `postgres:15-alpine`, thực tế báo PostgreSQL 15.17/Alpine 3.23.3, chưa có pgvector. Chủ dự án đồng ý bổ sung extension ngay trong container, giữ nguyên database có sẵn. Installer ghim pgvector 0.8.7; thư viện tồn tại qua restart nhưng phải cài lại sau recreate từ image gốc. Không sửa Compose của stack dùng chung ngoài workspace.
- Tạo riêng database dev/test và runtime role với mật khẩu ngẫu nhiên. `.env` local bị Git bỏ qua, không in credential. Flyway V1 chỉ CREATE EXTENSION; dùng migration account có quyền, runtime không superuser. Không tạo bảng users/documents/jobs trong phase này.
- Compose trong repo là phương án **tùy chọn** cho máy mới, DB port 5433 để tránh chiếm 5432. Theo yêu cầu sử dụng container hiện có, nghiệm thu local dùng `docker start local_postgres` + setup + chạy app, thay cho khởi tạo DB mới bằng Compose.
- Chưa có auth thật: default-generated user bị tắt, API nghiệp vụ mặc định đóng; health công khai tối thiểu, Swagger chỉ ở dev/test. Không thêm endpoint nghiệp vụ giả để demo.
- Unit test không cần DB; integration test phải chạy PostgreSQL/pgvector thật. Local dùng DB test riêng; CI dùng Testcontainers 0.8.7-pg15. Không skip integration khi Docker lỗi, không dùng H2 thay PostgreSQL.
- Eval được chuẩn bị từ Phase 1: 20 file tổng hợp, 25 câu gán nhãn, không dữ liệu cá nhân. Manifest PENDING_REVIEW; Tuấn Anh duyệt trước tuần 2. Pipeline version chưa có cho đến Phase 6; không bịa kết quả đánh giá.

## Quyết định chưa chốt

- Provider/model chat, embedding và số chiều: mốc bắt buộc trước Phase 2 theo xác nhận của chủ dự án; chưa gọi provider/chi tiền trong Phase 1.
- Phương án/model rerank: ghi rõ khi chốt AI; không tạo dịch vụ trả phí ngầm.
- Domain/VPS: chưa đặt mua hoặc suy đoán; chưa ảnh hưởng nền tảng local, phải chốt trước Phase 13.
- Chủ dự án duyệt ground truth/rubric bộ eval trước tuần 2. Trạng thái này không tự đổi thành APPROVED khi validator kỹ thuật pass.

## Nguồn tương thích

- [Spring AI Getting Started — compatibility](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [springdoc compatibility matrix](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot)
- [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/)
- [pgvector build/install chính thức](https://github.com/pgvector/pgvector#installation)

Kết quả chạy build/tests và smoke test thực tế được ghi ở `docs/phase-1-verification.md` sau khi chạy; tài liệu nguồn không thay thế bằng chứng kiểm thử.
