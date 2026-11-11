# DocuMind Backend

Backend Java/Spring Boot cho ứng dụng hỏi đáp trên tài liệu với trích dẫn. Người dùng tải PDF, DOCX hoặc TXT; worker trích xuất và chia đoạn, tạo embedding bằng Gemini, lưu trong PostgreSQL/pgvector. Khi đặt câu hỏi, hệ thống truy xuất các đoạn thuộc tài khoản, gửi ngữ cảnh cho Gemini và truyền kết quả bằng SSE.

```text
React → API Spring Boot → PostgreSQL + pgvector
                           ↕
                    worker / Gemini API
```

Java 21 · Spring Boot · Spring Security · Spring AI · PostgreSQL/pgvector · Flyway · Docker. Hybrid search và rerank mặc định tắt; chưa có báo cáo đánh giá chất lượng đầy đủ để công bố đạt gate phát hành. Xem [trạng thái eval](eval/phase11-status.md).

## Chạy local

Cần JDK 21, Docker Desktop và PostgreSQL có pgvector. Các lệnh chạy từ thư mục chứa `pom.xml`.

**Dùng PostgreSQL sẵn có:** tạo database và cấu hình `.env` theo `.env.example`. Với container local đã thiết lập trước, có thể dùng `deploy/postgres/setup-local.ps1` và `deploy/setup-auth-local.ps1`; đọc script trước khi chạy, không tạo lại database đang chứa dữ liệu. Sau đó:

```powershell
docker start local_postgres
.\mvnw.cmd -B -ntp spring-boot:run "-Dspring-boot.run.profiles=dev"
```

**Compose riêng:** sao chép `.env.example` thành `.env`, điền `POSTGRES_PASSWORD` và `JWT_SECRET` (base64 của ít nhất 32 byte ngẫu nhiên). Không đưa `.env` lên Git. Trước khi build image, chạy kiểm thử để tạo JAR đã xác minh; Dockerfile đóng gói chính JAR này. Compose tạo database và volume riêng, không dùng hay xóa container `local_postgres`:

```powershell
.\mvnw.cmd -B -ntp clean verify '-Ddocumind.testcontainers=true'
docker compose --profile standalone-app up --build -d
docker compose --profile standalone-app ps
```

Backend: `http://localhost:8080`; Swagger dev: `http://localhost:8080/swagger-ui/index.html`; readiness: `http://localhost:8080/actuator/health/readiness`. Nếu cổng bị chiếm, đặt `DOCUMIND_APP_PORT` và `DOCUMIND_POSTGRES_PORT` trong `.env` trước khi chạy. Khi đổi cổng app, cập nhật cả `AUTH_ALLOWED_ORIGINS` với origin mới của Swagger và giữ origin FE, ví dụ `http://localhost:5173,http://localhost:18082`. Dừng bằng `docker compose --profile standalone-app down`; không thêm `-v` nếu cần giữ database và tài liệu.

Nếu chỉ cần database, dùng `docker compose --profile standalone-db up -d db`; lệnh này không khởi chạy app hay yêu cầu `JWT_SECRET`.

AI, pipeline và worker mặc định tắt. Để thử Gemini với tài liệu demo riêng, kiểm tra quota và chính sách billing trước, đặt `GEMINI_API_KEY` trong `.env`. Với IntelliJ/Maven, bật các khóa properties `documind.gemini.enabled`, `documind.chat.enabled`, `documind.ingestion.pipeline-enabled` và `documind.ingestion.worker-enabled` bằng `true`. Với Compose, dùng các biến `DOCUMIND_GEMINI_ENABLED`, `DOCUMIND_CHAT_ENABLED`, `DOCUMIND_INGESTION_PIPELINE_ENABLED` và `DOCUMIND_INGESTION_WORKER_ENABLED` trong `.env`. Khởi động lại ứng dụng; với Compose chạy lại `docker compose --profile standalone-app up -d` để áp dụng thay đổi. Không đặt key hoặc dữ liệu nhạy cảm trong source, image hay log.

## API và bảo mật

- `/api/v1/auth`: đăng ký, đăng nhập, refresh và logout. Lấy CSRF token từ `/api/v1/auth/csrf` trước các POST auth; gửi Origin, cookie và header CSRF theo Swagger.
- `/api/v1/documents`: upload, danh sách, chi tiết, xóa tài liệu.
- `/api/v1/conversations`: hội thoại, lịch sử và gửi câu hỏi qua SSE.
- `/api/v1/usage`: thống kê theo tài khoản; số chưa ghi nhận được trả về là `null`, không phải 0.

API nghiệp vụ dùng access JWT. Refresh token là cookie HttpOnly; secret JWT phải độc lập và đủ mạnh. Swagger dev không phải cấu hình public production. Image Compose là cấu hình **local**, storage trên volume; không dùng filesystem tạm của Render làm nơi lưu tài liệu lâu dài.

## Kiểm thử và đánh giá

```powershell
.\mvnw.cmd -B -ntp clean verify '-Ddocumind.testcontainers=true'
python -m pip install -r eval/requirements.txt
python eval/validate.py
python -m unittest discover -s eval -p 'test_*.py'
```

Testcontainers cần Docker. Báo cáo JaCoCo nằm tại `target/site/jacoco/index.html`; báo cáo unit/integration ở `target/surefire-reports` và `target/failsafe-reports`. CI không gọi Gemini thật. Hướng dẫn bộ dữ liệu demo và thu/chấm kết quả xem [eval/README.md](eval/README.md); validator thành công không chứng minh chất lượng RAG.

## Xử lý sự cố

- **Không kết nối được database:** kiểm tra Docker, cổng, pgvector và `DB_URL`; trong Compose hostname là `db`, không phải `localhost`.
- **Flyway lỗi:** dừng triển khai và kiểm tra phiên bản/checksum cùng backup; không xóa database hoặc tự sửa checksum để bỏ qua lỗi.
- **Upload không xử lý:** kiểm tra các cờ worker/pipeline/Gemini, API key và quota; không in key vào log.
- **Storage marker lỗi:** giữ nguyên volume và database để kiểm tra sự khớp storage; không tự khởi tạo lại trên dữ liệu cũ.
- **Ứng dụng tự đóng sau lỗi worker:** kiểm tra log theo request ID; container dùng restart policy. Không đưa tài liệu cá nhân thật vào demo công khai.
