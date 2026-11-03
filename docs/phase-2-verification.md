# Kiểm chứng nền tảng dữ liệu Phase 2

Ngày 2026-11-03, Java 21, PostgreSQL 15/pgvector 0.8.7. AI tắt; không gọi provider, không có dữ liệu cá nhân trong fixtures.

Sau review, V2 đã được sửa trực tiếp theo yêu cầu chủ dự án. Lệnh `./mvnw.cmd -B -ntp clean verify '-Ddocumind.testcontainers=true'` với V2 mới thành công: **3 unit test và 15 integration test**, không skip, trên DB Testcontainers sạch. Các ca mới kiểm tra JPA defaults, count generation sau khi xóa conversation, từ chối request key trùng, detail DELETING, lưu/thay chunk, metadata/vị trí và hai transaction claim độc lập. Testcontainers dùng DB tạm; không sửa database test local có V2 cũ.

Các kết quả bên dưới là lịch sử chạy với **bản V2 trước review**, không phải xác nhận cho checksum V2 hiện tại:

- `./mvnw.cmd -B -ntp clean verify '-Ddocumind.testcontainers=true'`: thành công, 3 unit tests và 9 integration tests, không skip. Testcontainers tạo DB sạch và chạy Flyway từ đầu.
- `./mvnw.cmd -B -ntp verify`: thành công, 3 unit tests và 9 integration tests, không skip. Chạy trên database test local `documind_phase1_test`, với tài khoản migration và runtime tách biệt. V2 được áp dụng lên database test, không chạy trên database dev trong lượt kiểm thử.
- Sau khi bổ sung ca cách ly hội thoại: `./mvnw.cmd -B -ntp verify '-Ddocumind.testcontainers=true'` thành công, 3 unit tests và 10 integration tests, không skip.
- Flyway validate/migrate lại không áp dụng thêm migration; kiểm tra pgvector, `vector(1536)`, FK chủ sở hữu, trạng thái, unique generation, JPA, phân quyền truy xuất vector, claim lease, usage sau khi xóa job và outbox tồn tại sau cleanup metadata. Dữ liệu thử được rollback.
- Bài test claim lần đầu phát hiện driver PostgreSQL không nhận trực tiếp `Instant`; sau khi chuyển sang UTC `OffsetDateTime`, cả hai lần verify trên đều thành công.

Không có benchmark HNSW hay phép đo chất lượng embedding; eval chưa được duyệt, chưa chạy model thật. `deletion_journal_outbox` không thay thế nhật ký độc lập sau restore; cần quyết định nơi lưu/relay và kiểm chứng restore trước vận hành. Remote CI chưa được chạy trong lượt này.
