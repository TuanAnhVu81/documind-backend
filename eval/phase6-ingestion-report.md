# Kiểm chứng ingestion Phase 6

Ngày kiểm thử: 2026-11-05. Corpus: `synthetic-vi-v2`, 20 file trong `eval/fixtures`. Pipeline: `phase6-v1`. Embedding 1.536 chiều được giả lập có đầu ra cố định; không gọi model OpenAI thật và không đánh giá chất lượng ngữ nghĩa.

Kết quả từ `PipelineIT.ingestsTwentyEvalFixturesWithFakeEmbeddings`: **20 READY, 0 FAILED** sau retry tự động. Test đưa 20 fixture qua worker và queue, giả lập lỗi tạm thời ở một lần gọi embedding; một job hoàn tất ở attempt thứ hai sau backoff thực tế. Các job còn lại thành công, tất cả có chunk. Đây là kiểm thử với một lỗi giả lập, không đo tỉ lệ thành công hoặc chi phí của provider thật. Dữ liệu, file lưu trữ và PostgreSQL đều tạm trong test; không ghi vào database local.

Test SDK riêng dùng HTTP server trên loopback, giả lập lỗi 429 và `Retry-After`; không gửi request ra ngoài. Trạng thái duyệt trong `eval/manifest.json` giữ nguyên `PENDING_REVIEW`.

Lệnh chạy đầy đủ: `./mvnw -B -ntp clean verify -Ddocumind.testcontainers=true` (Windows: `mvnw.cmd`).

Kết quả sau review: 12 unit test và 74 integration test đạt, không lỗi hoặc bỏ qua. Parser DOCX giới hạn 1.000 entry và tổng 32 MiB dữ liệu giải nén; file hợp lệ nhưng vượt giới hạn này sẽ bị từ chối với `DOCX_RESOURCE_LIMIT`.
