# Phase 11 — kết quả kiểm tra và các phần chưa đo

Ngày: 2026-11-09. Bộ dữ liệu nghiệm thu: `synthetic-vi-v2`, câu hỏi `questions-vi-v3`, đã được Tuấn Anh xác nhận khóa. Model cấu hình: `gemini-embedding-2` 1.536 chiều và `gemini-3.5-flash-lite`; pipeline `phase7-gemini-v1`. Bộ validator xác minh checksum và 25 câu; việc validator đạt không chứng minh chất lượng retrieval hoặc đáp án.

## Search nội bộ với dữ liệu tổng hợp

Chạy PostgreSQL/pgvector Testcontainers `pgvector/pgvector:0.8.7-pg15` trên Windows, bộ xử lý Intel64 Family 6 Model 165. Tạo 10.000 vector 1.536 chiều chia hai tài khoản; một query embedding cố định; `topK=5`, `minScore=-1`, query full-text `x`, 10 lượt warm-up và 100 lượt tuần tự. Đo trực tiếp tầng search, không tính provider, HTTP hoặc rate limit. Hybrid lấy tối đa `min(100, topK × 4)` candidate mỗi nhánh, full-text PostgreSQL `simple`, RRF offset 60. Dữ liệu tổng hợp không đại diện chất lượng tiếng Việt và query `x` không đại diện tải thực tế.

| Cấu hình | p95 |
| --- | ---: |
| Vector exact | 49,02 ms |
| Vector + full-text + RRF | 104,97 ms |

Lệnh tái lập:

```powershell
.\mvnw.cmd -B -ntp verify '-Ddocumind.testcontainers=true' '-Ddocumind.benchmark=true' '-Dit.test=PipelineIT#benchmarkExactAndHnswWithTenThousandVectors' '-Dtest=ReciprocalRankFusionTest,GeminiRerankerTest'
```

Kết quả là một lượt đo nội bộ tổng hợp, không chứng minh gate phát hành hoặc Recall@K thực tế. Không so sánh số này với retrieval end-to-end; chưa có số đo provider.

## Cấu hình và giới hạn

- `RETRIEVAL_HYBRID_ENABLED=false` và `RETRIEVAL_RERANK_ENABLED=false` mặc định; chưa có đánh giá chứng minh lợi ích. Rerank dùng Gemini theo lựa chọn của chủ dự án và ghi usage `CHAT_RERANK`; đầu vào giới hạn 65.536 byte, candidate vượt ngân sách giữ nguyên ở cuối danh sách.
- `RETRIEVAL_REWRITE_ENABLED=true` mặc định; chỉ tắt để chạy ablation, không được coi là cấu hình phát hành đáp ứng hỏi tiếp.
- `simple` không có stemming tiếng Việt chuyên dụng. Full-text có thể không tìm thấy câu hỏi dài hoặc biến thể từ; cần đánh giá trên dữ liệu được duyệt và tập phát triển riêng. Keyword candidate vẫn phải qua ngưỡng cosine, bảo đảm ngưỡng hiện tại không bị bỏ qua.
- **Chưa chạy provider thật** vì project có billing bật và chưa có xác nhận rằng lượt chạy không phát sinh phí. Chưa có số liệu READY, source hit@3, NO_EVIDENCE, citation ID sai, token cost hay retrieval end-to-end. Không kết luận đạt gate.
- `capture_results.py` có thể thu SSE từ backend local sau preflight, nhưng chưa tự tạo corpus/histories và chưa lấy được retrieval candidates hoặc timing từng công đoạn. `score_results.py` tổng hợp kết quả có người chấm. Report ablation, đối chiếu vị trí sau ingestion và instrumentation retrieval còn chưa hoàn thành. Chưa nghiệm thu Phase 11.
