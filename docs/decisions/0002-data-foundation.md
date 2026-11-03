# ADR 0002 — Nền tảng dữ liệu Phase 2

Chủ dự án chọn OpenAI `text-embedding-3-small`, 1.536 chiều mặc định; model tiếp tục tắt, chưa gọi API, chưa xác nhận chất lượng tiếng Việt. V2 tạo `vector(1536)` và chỉ mục HNSW cosine theo TECH SPEC. Chỉ mục này chưa được benchmark trên tập 10.000 chunk; đánh giá latency/recall thuộc Phase 7. Đổi model, chiều hoặc pipeline sau khi có dữ liệu cần migration có kế hoạch, tạo lại embeddings và chỉ chuyển đọc sau khi đã kiểm chứng; không trộn vector khác model trong cùng không gian tìm kiếm.

Flyway quản lý schema và `ddl-auto=validate`; runtime dùng role có quyền DML, không cần quyền tạo bảng. V2 cấp DML cho chủ database (phù hợp setup local), người vận hành phải cấp quyền riêng nếu runtime role khác chủ database. Không cấp quyền tạo extension hay quyền superuser cho runtime. Không sửa migration đã áp dụng; thay đổi tiếp theo dùng migration mới.

Repository JPA chứa CRUD nền, còn chunk vector và claim job dùng `JdbcClient`. ID chủ sở hữu phải lấy từ danh tính đã xác thực, không lấy từ request. `JobClaimStore.claimDue` yêu cầu transaction do caller mở, chỉ khóa/claim job trong transaction ngắn; xử lý file và gọi provider bên ngoài transaction. Đường hoàn tất, hủy và recovery phải kiểm tra lease/fencing và trạng thái document trong transaction của các phase sau; không coi thao tác claim riêng lẻ là worker hoàn chỉnh.

`question_events` là sự kiện đếm bền vững, được trigger thêm cùng transaction khi tạo generation, kể cả không gọi provider; khóa generation ngăn đếm trùng và sự kiện còn lại sau khi xóa hội thoại. `ChunkVectorStore.replace` đòi caller mở transaction ngắn, khóa document trước và xác minh lease/fencing trong transaction đó trước khi ghi. Việc kiểm tra và thực thi worker hoàn chỉnh thuộc Phase 5–6.

Theo yêu cầu chủ dự án, V2 đã được chỉnh trực tiếp khi còn đang phát triển. Database test local đã từng áp dụng bản V2 cũ nên không tương thích checksum mới. Chạy Testcontainers sạch; không dùng Flyway `repair` đơn thuần vì nó không cập nhật schema. Trước khi dùng lại DB đó phải có migration chuyển đổi hoặc xác nhận tạo lại DB test riêng; không tự xóa dữ liệu. Từ khi V2 được áp dụng ở môi trường cần giữ dữ liệu, thay đổi schema dùng migration mới.

`document_cleanup_tasks` là hàng đợi bền vững trong DB. `deletion_journal_outbox` lưu yêu cầu độc lập với document và tồn tại sau khi cleanup; bản thân bảng này **không** bảo vệ sự kiện phát sinh sau snapshot khi restore DB. Cần chọn nơi lưu nhật ký tách biệt, relay/replay có đối soát và chặn mở truy cập cho đến khi áp dụng đầy đủ yêu cầu xóa; chưa có quyết định hạ tầng ngoài DB và chưa triển khai relay/restore trong Phase 2. Không khẳng định khôi phục an toàn khi phần này chưa có.

Chat model, reranker và hạ tầng nhật ký xóa ngoài DB chưa được chọn. Eval vẫn cần chủ dự án duyệt; không phát sinh request AI hoặc chi phí từ Phase 2.
