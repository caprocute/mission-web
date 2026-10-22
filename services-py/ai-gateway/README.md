# SVC-14 ai-gateway

Cổng mô hình ngôn ngữ chỉ đọc của nền tảng (TL-01 "SVC-14 ai-gateway", chương "Trí tuệ nhân tạo"; TL-03 "Schema ai"). Dịch vụ viết bằng Python 3.12 và FastAPI, dùng thư viện `services-py/vtplatform` (NEN-018) cho RFC 9457, `SET LOCAL app.tenant_id`, nhật ký JSON và truyền `traceparent`. DV-14-01 dựng phần khung chung; các ca dùng (RAG DV-14-02, rào chắn DV-14-03, API trợ lý DV-14-04..06) chỉ cần viết lời nhắc và logic riêng.

## Năm bước của một yêu cầu

Bước một là xác thực và kiểm quyền: `app.api.deps.authorize` kiểm chữ ký JWT người dùng qua JWKS của Keycloak (issuer, hạn, audience nếu cấu hình), dựng chủ thể theo cùng cách đọc claim với `AuthzSubjectResolver` của vt-platform-starter, rồi hỏi OPA cùng pod `POST /v1/data/okrkpi/authz/decision` với `request.module = "ai"` và phương thức HTTP. OPA lỗi thì trả 503, không bao giờ cho qua. Quyền theo vai trò lấy từ ma trận sinh ra trong gói `backend/opa`, không đặt danh sách vai trò trong mã.

Bước hai là lắp ngữ cảnh. `CoreClient` gọi API lõi dưới danh tính người dùng bằng token đã đổi theo RFC 8693 (`TokenExchanger`): client `ai-gateway` gửi token người dùng tới endpoint token của Keycloak với `audience = mission-core`, `scope = ai_readonly`; token đổi về phải cùng `sub`, cùng `tenant_id` và chỉ mang vai trò `ai_readonly`, nếu không thì lời gọi bị huỷ. Token gốc của người dùng không bao giờ được chuyển tới lõi. `CoreClient` chỉ có thao tác `get`; móc sự kiện của httpx còn chặn mọi phương thức khác GET, HEAD ở tầng client nên kể cả mã gọi thẳng client bên dưới cũng không ghi được.

Bước ba đến năm do `app.core.pipeline.Gateway` làm: chạy rào chắn đầu vào (danh sách `InputGuard`, DV-14-03 cài quy tắc), gọi mô hình qua `InferenceQueue`, chạy rào chắn đầu ra, rồi ghi `ai.suggestion_log` (và `ai.guardrail_event` khi rào chắn chặn, che hoặc cảnh báo). Nhật ký chỉ lưu `prompt_sha256` của lời nhắc gốc và `prompt_redacted` đã che số định danh, điện thoại, email, token (BR-17-07); kết quả trả về luôn mang `aiGenerated = true` và nhãn "Nội dung do AI tạo".

## Hàng đợi suy luận và 202

`InferenceQueue` giới hạn số suy luận đồng thời (`LLM_MAX_CONCURRENCY`) và số yêu cầu chờ (`LLM_MAX_WAITING`), xuất metric `ai_inference_queue_length` cho HPA cùng `ai_inference_in_flight` và `ai_inference_seconds`. Khi hàng đợi đầy, hoặc suy luận chưa xong sau `LLM_SYNC_WAIT_SECONDS`, `Gateway` trả `Accepted`: API trả 202 kèm job theo mẫu chung (`status = CHO`, `resultLocation` và `Location` là `/api/v1/ai/suggestions/{id}`, MSG-17-03) và tiếp tục xử lý nền; kết quả được ghi với đúng mã đó nên `GET /api/v1/ai/suggestions/{id}` (getAiSuggestion) trả gợi ý khi xong. Số việc hoãn mỗi tiến trình tối đa `AI_DEFERRED_MAX`; vượt thì 429.

## Mô hình

`LLM_MODE=mock` (mặc định) dùng bản giả lập tất định trong mã, theo quyết định chạy hệ ngoài bằng giả lập (YC-PT-13). `LLM_MODE=openai` gọi `POST /v1/chat/completions` của vLLM hoặc máy chủ tương thích tại `LLM_BASE_URL`. Hồ sơ `backend` của `infra/dev` có dịch vụ `llm-mock` chạy llama.cpp server trên CPU với mô hình Qwen2.5-0.5B-Instruct GGUF (giấy phép Apache 2.0), đặt bí danh `viettel-llm-vi`; đặt `LLM_BASE_URL=http://127.0.0.1:35340` để thử.

## Dữ liệu

Migration `db/migration/ai/V1__ai_schema.sql` chạy bằng `mig_ai` trên CSDL ở cụm `pg-sec`; extension `vector` (pgvector ≥ 0.8) phải được quản trị cài trước (`infra/dev/postgres/migrate-roles.sh` đã làm ở dev). Ba bảng của TL-03 đều có RLS FORCE theo `app.tenant_id`; `doc_chunk` có chỉ mục HNSW `vector_cosine_ops` (`m = 16`, `ef_construction = 64`), GIN trên `acl_unit_ids` và CHECK chỉ nhận C0, C1; `suggestion_log` và `guardrail_event` phân vùng theo ngày bằng `ai.fn_ensure_day_partitions`. `svc_ai` chỉ có quyền trong schema `ai` và không xoá được nhật ký gợi ý.

## Cấu hình

`DATABASE_URL` (svc_ai), `OIDC_ISSUER`, `OIDC_JWKS_URL`, `OIDC_TOKEN_URL`, `OIDC_AUDIENCE`, `AI_EXCHANGE_CLIENT_ID`, `AI_EXCHANGE_CLIENT_SECRET`, `AI_EXCHANGE_AUDIENCE`, `AI_EXCHANGE_SCOPE`, `AI_EXCHANGE_ROLE`, `OPA_URL`, `CORE_BASE_URL`, `LLM_MODE`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`, `LLM_TIMEOUT_SECONDS`, `LLM_MAX_CONCURRENCY`, `LLM_MAX_WAITING`, `LLM_SYNC_WAIT_SECONDS`, `AI_DEFERRED_MAX`, `CELL_CODE`. Bí mật chỉ nạp qua biến môi trường.

## Kiểm thử

```bash
cd services-py/ai-gateway
uv sync --python 3.12 --extra dev
uv run ruff check . && uv run mypy app && uv run pytest   # tích hợp cần Docker: pgvector/pgvector:pg17, openpolicyagent/opa:1.4.0
uv run pip-audit
docker build -f ai-gateway/Dockerfile -t ai-gateway ..       # chạy từ services-py
```

Kiểm thử tích hợp áp migration lên PostgreSQL 17 + pgvector, kiểm RLS giữa hai tenant, kiểm `svc_ai` không có quyền trên schema nghiệp vụ nào, và chạy API qua OPA thật nạp gói `backend/opa` (vai trò ngoài ma trận bị 403, `ai_readonly` không đọc được nhật ký, người khác và tenant khác nhận 404), so khớp phản hồi với lược đồ `AiSuggestion` của hợp đồng.
