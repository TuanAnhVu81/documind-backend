# DocuMind Backend

DocuMind là dự án hỏi đáp tài liệu có trích dẫn nguồn. Backend được xây dựng bằng Java và Spring Boot, sử dụng PostgreSQL cùng pgvector để lưu dữ liệu và hỗ trợ tìm kiếm tài liệu.

Dự án đang phát triển. Hiện đã có xác thực, quản lý tài liệu và hàng đợi xử lý; pipeline trích xuất, embedding và hỏi đáp bằng AI chưa hoàn thiện. AI và worker xử lý tài liệu đang tắt, không cần API key để chạy local.

## Công nghệ

- Java 21, Spring Boot, Spring Security và Spring AI.
- PostgreSQL 15, pgvector và Flyway.
- Maven Wrapper, JUnit và Testcontainers.

## Chạy local

Yêu cầu JDK 21, Docker Desktop, PowerShell và PostgreSQL 15 có pgvector. Các lệnh dưới đây chạy tại thư mục chứa `pom.xml` và dùng container PostgreSQL có sẵn tên `local_postgres`.

```powershell
docker start local_postgres
docker exec local_postgres pg_isready -U postgres
```

Nếu container chưa có pgvector, cài một lần bằng script của dự án:

```powershell
docker cp deploy/postgres/install-pgvector.sh local_postgres:/tmp/documind-install-pgvector.sh
docker exec --user root local_postgres sh /tmp/documind-install-pgvector.sh
```

Thiết lập database, cấu hình xác thực rồi chạy ứng dụng:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/postgres/setup-local.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File deploy/setup-auth-local.ps1
.\mvnw.cmd -B -ntp spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Các script tạo cấu hình local trong `.env`, không đưa file này lên Git. Nếu database hoặc role DocuMind đã tồn tại, cấu hình `.env` thủ công theo `.env.example` thay vì tạo lại dữ liệu.

Sau khi chạy, mở [Swagger UI](http://localhost:8080/swagger-ui/index.html) hoặc kiểm tra [health](http://localhost:8080/actuator/health).

## API hiện có

- `/api/v1/auth`: đăng ký, đăng nhập, làm mới và đăng xuất; POST auth dùng Origin và CSRF token.
- `/api/v1/documents`: tải lên, xem danh sách/chi tiết và yêu cầu xóa tài liệu. API sử dụng Bearer token; hỗ trợ PDF, DOCX và TXT tối đa 10 MiB.

Tài liệu mới ở trạng thái `PENDING` cho đến khi pipeline xử lý được triển khai.

## Kiểm thử

Chạy unit test và integration test với PostgreSQL tạm qua Testcontainers:

```powershell
.\mvnw.cmd -B -ntp verify "-Ddocumind.testcontainers=true"
```

Để kiểm tra bộ dữ liệu đánh giá, cài Python rồi chạy:

```powershell
python -m pip install -r eval/requirements.txt
python eval/validate.py
python -m unittest discover -s eval -p "test_*.py"
```

## Lưu ý vận hành

- File upload mặc định nằm trong `./uploads`; giữ nguyên thư mục và marker storage khi chuyển hoặc khôi phục dữ liệu.
- Nếu worker được bật sau này và tác vụ không dừng sau khi bị hủy, ứng dụng sẽ đóng có kiểm soát. Môi trường triển khai cần supervisor hoặc container restart policy; local không có supervisor phải khởi động lại thủ công.
- Không tự sửa checksum Flyway hoặc xóa database để xử lý lỗi migration khi chưa xác định ảnh hưởng đến dữ liệu.
