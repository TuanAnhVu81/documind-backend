# Báo cáo truy xuất vector Phase 7

Ngày: 2026-11-06. Embedding dự kiến: `gemini-embedding-2`, 1.536 chiều, pipeline `phase7-gemini-v1`. CI dùng embedding giả; chưa gọi Gemini thật. Bộ eval `synthetic-vi-v2` vẫn `PENDING_REVIEW`; chưa đo baseline ngữ nghĩa, source hit hay hiệu chỉnh ngưỡng. `top-k=5`, `min-score=-1` chỉ là cấu hình kỹ thuật để kiểm thử, không phải ngưỡng phát hành.

Benchmark PostgreSQL/pgvector Testcontainers `pgvector/pgvector:0.8.7-pg15`, Docker 29.6.1, Windows, Intel Core i7-10750H. Dữ liệu là 10.000 vector tổng hợp 1.536 chiều, chia đều hai người dùng, một truy vấn vector cố định, top 5; chạy 10 lượt warm-up và 100 lượt tuần tự. Không bao gồm thời gian provider. Lệnh:

```powershell
.\mvnw.cmd -B -ntp verify '-Ddocumind.testcontainers=true' '-Ddocumind.benchmark=true' '-Dit.test=PipelineIT#benchmarkExactAndHnswWithTenThousandVectors'
```

| Truy vấn | p95 | Kết quả |
|---|---:|---|
| Exact search của `ChunkVectorStore`, lọc owner, READY, model và pipeline | 38,76 ms | 5 kết quả |
| HNSW thử nghiệm, lọc owner, ép planner dùng HNSW | 1,82 ms | 5 kết quả; trùng 5/5 exact ở truy vấn mẫu |

Hai phép đo không cùng đầy đủ bộ lọc: truy vấn HNSW chỉ lọc owner và không phải implementation phát hành. Kết quả không chứng minh chất lượng semantic hay recall trên dữ liệu thật. HNSW có thể lọc sau tìm kiếm gần đúng và trả thiếu kết quả với tenant chọn lọc cao. Hiện chọn exact search sau lọc để bảo toàn kết quả; chỉ cân nhắc HNSW/iterative scan sau benchmark tương đương gồm đầy đủ READY, owner, model, scope và recall trên dữ liệu đại diện. Không công bố gate phát hành từ benchmark giả này.

Khi chuyển dữ liệu đã có sang model mới, trước tiên kiểm kê `embedding_model`/`pipeline_version`, sao lưu và xác nhận phương án re-embedding với chủ dữ liệu. Tài liệu có vector model khác bị loại khỏi ALL_READY và SELECTED trả `DOCUMENT_NOT_READY`; không xóa hay sửa DB cũ tự động. Tạo lại embedding qua pipeline Gemini có fencing và kiểm tra toàn bộ trước khi đưa tài liệu trở lại READY. Công cụ chuyển đổi riêng và việc chạy thực tế chưa thực hiện; schema vẫn `vector(1536)`.
