# SVC-14 ai-gateway

Cổng mô hình ngôn ngữ chỉ đọc của nền tảng (TL-01 "SVC-14 ai-gateway", chương "Trí tuệ nhân tạo"; TL-03 "Schema ai"). Dịch vụ viết bằng Python 3.12 và FastAPI, dùng thư viện `services-py/vtplatform` (NEN-018) cho RFC 9457, `SET LOCAL app.tenant_id`, nhật ký JSON và truyền `traceparent`. DV-14-01 dựng phần khung chung; các ca dùng (RAG DV-14-02, rào chắn DV-14-03, API trợ lý DV-14-04..06) chỉ cần viết lời nhắc và logic riêng.

## Năm bước của một yêu cầu

Bước một là xác thực và kiểm quyền: `app.api.deps.authorize` kiểm chữ ký JWT người dùng qua JWKS của Keycloak (issuer, hạn, audience nếu cấu hình), dựng chủ thể theo cùng cách đọc claim với `AuthzSubjectResolver` của vt-platform-starter, rồi hỏi OPA cùng pod `POST /v1/data/okrkpi/authz/decision` với `request.module = "ai"` và phương thức HTTP. OPA lỗi thì trả 503, không bao giờ cho qua. Quyền theo vai trò lấy từ ma trận sinh ra trong gói `backend/opa`, không đặt danh sách vai trò trong mã.

Bước hai là lắp ngữ cảnh. `CoreClient` gọi API lõi dưới danh tính người dùng bằng token đã đổi theo RFC 8693 (`TokenExchanger`): client `ai-gateway` gửi token người dùng tới endpoint token của Keycloak với `audience = mission-core`, `scope = ai_readonly`; token đổi về phải cùng `sub`, cùng `tenant_id` và chỉ mang vai trò `ai_readonly`, nếu không thì lời gọi bị huỷ. Token gốc của người dùng không bao giờ được chuyển tới lõi. `CoreClient` chỉ có thao tác `get`; móc sự kiện của httpx còn chặn mọi phương thức khác GET, HEAD ở tầng client nên kể cả mã gọi thẳng client bên dưới cũng không ghi được.

Bước ba đến năm do `app.core.pipeline.Gateway` làm: chạy rào chắn đầu vào (danh sách `InputGuard`, DV-14-03 cài quy tắc), gọi mô hình qua `InferenceQueue`, chạy rào chắn đầu ra, rồi ghi `ai.suggestion_log` (và `ai.guardrail_event` khi rào chắn chặn, che hoặc cảnh báo). Nhật ký chỉ lưu `prompt_sha256` của lời nhắc gốc và `prompt_redacted` đã che số định danh, điện thoại, email, token (BR-17-07); kết quả trả về luôn mang `aiGenerated = true` và nhãn "Nội dung do AI tạo".

## Rào chắn (DV-14-03)

`app/guard` có hai rào chắn mà `create_app` gắn vào Gateway. Rào chắn đầu vào chặn câu hỏi tiêm lệnh (`PROMPT_INJECTION`), loại khỏi ngữ cảnh đoạn tài liệu nghi tiêm lệnh và ghi `CHE`, chặn tuyệt đối dữ liệu C3, chặn yêu cầu chấm điểm, đề xuất mức xếp loại hay nhận xét cán bộ (`OUT_OF_SCOPE`, MSG-17-02, BR-17-04), chặn câu hỏi cố ý lấy dữ liệu cá nhân (`C2_REQUEST`), rồi che số định danh, điện thoại, email, ngày sinh trước khi gửi mô hình. Luật viết trên văn bản đã bỏ dấu, bỏ ký tự vô hình và chữ giả dạng (`app/guard/text.py`), nên bắt được cả câu viết không dấu. Hỏi đáp quy định về chấm điểm, xếp loại và giải thích điểm đã có vẫn được phép. Rào chắn đầu ra kiểm JSON theo `output_schema` của ca dùng, buộc trích dẫn `[#n]` hoặc `sources[].ref` thuộc danh sách đoạn đã truy xuất và buộc gợi ý có ngữ cảnh phải có nguồn (BR-17-02), chặn đầu ra chấm điểm hay xếp loại, cắt về `max_objectives` mục tiêu (BR-17-06), che dữ liệu cá nhân lọt ra (`PII_OUT`). Mỗi lần chặn, che hay cảnh báo có một dòng `ai.guardrail_event`; chi tiết chỉ ghi loại dữ liệu, không ghi giá trị. `CoreClient.get_json` từ chối dữ liệu C3 và che các trường mang `x-classification: C2` trong hợp đồng (danh sách `app/guard/c2_fields.json`, sinh bằng `python -m app.guard.sync_c2_fields`). Bộ ca đối kháng ở `data/golden/ai-guardrail/`; metric `ai_guardrail_total{rule_code,action}` và `ai_guardrail_input_seconds`.

## Kho RAG (DV-14-02)

`app/rag` lập chỉ mục và truy xuất `ai.doc_chunk` (pgvector `vector(1024)`, HNSW cosine). Văn bản được chia thành đoạn 500–800 từ, chồng lấp khoảng 120 từ, cắt theo Chương, Mục, Điều và khoản; mỗi đoạn mở đầu bằng dòng "Nguồn: tiêu đề; vị trí" để câu trả lời trích được điều, khoản. Có ba nguồn. Thứ nhất, consumer EVT-24 `integ.document.received.v1` (aiokafka, Avro theo `contracts/events/avro/integ/VanBanChiDaoDaNhan.avsc`, inbox `ai.inbox_processed`, thử lại 4 lần rồi đẩy `<topic>.dlq.ai-gateway`) đọc toàn văn qua `GET /api/v1/tasks/source-docs/{id}` bằng token dịch vụ `client_credentials` mang vai trò `ai_readonly`; văn bản C2, C3 không được nhúng mà chỉ ghi `ai.guardrail_event`. Thứ hai, kho văn bản dùng chung `data/seed/ai-corpus` nạp bằng `python -m app.rag.load_corpus` vào tenant dùng chung (UUID toàn số 0) với ACL mở. Thứ ba, tệp PDF hoặc DOCX (tối đa 20 MB) người dùng đính kèm vào câu hỏi, chỉ người tải lên đọc được.

Truy xuất lọc theo tenant (của người hỏi và tenant dùng chung), mức C0/C1, mô hình nhúng và ACL (`acl_unit_ids && [đơn vị, người dùng, ACL mở]`) ngay trong câu WHERE, đặt `hnsw.iterative_scan = relaxed_order` để bộ lọc chặt vẫn đủ k kết quả, rồi kiểm lại ACL ở tầng ứng dụng. `EMBED_MODE=mock` (mặc định) dùng bộ nhúng băm tất định để chạy không cần mô hình; `EMBED_MODE=openai` gọi `EMBED_BASE_URL/v1/embeddings` với `EMBED_MODEL`. Consumer chỉ chạy khi có `KAFKA_BROKERS`.

## API trợ lý (DV-14-04)

`app/api/assist.py` hiện thực bốn thao tác của `platform.yaml`, cùng với `getAiSuggestion` đã có từ DV-14-01. `POST /api/v1/ai/assist/okr` (assistOkrDraft) đọc căn cứ, bản nháp, KR của đơn vị khác và tệp đính kèm qua API lõi bằng token đổi chỉ đọc, truy xuất RAG, rồi để mô hình soạn tối đa 3 mục tiêu (BR-17-06). Kết quả giữ lại mục tiêu có nguồn đối chiếu được (BR-17-02), chuẩn hoá trọng số KR cho cộng đúng 100, và thêm gợi ý liên kết ngang khi KR gần trùng KR của đơn vị khác, cùng gợi ý soát KR của bản nháp. `POST /api/v1/ai/okr/review` (reviewOkrKeyResults) soát KR bằng luật BR-02-05 và BR-02-06 theo bộ từ của Policy Pack (`GET /okr/compose-context`), rồi nhờ mô hình viết lại KR mơ hồ; câu viết lại còn từ mơ hồ hoặc không có số bị loại. `POST /api/v1/ai/suggestions` (requestAiSuggestion) nhận `HOI_DAP_QUY_DINH`, `GIAI_THICH_DIEM` (đọc vết tính `GET /appraisals/profiles/{id}/score-trace` của chính người hỏi) và `TOM_TAT_VAN_BAN`; ca dùng khác trả 422. `GET /api/v1/ai/extractions?sourceDocId=` (getDocExtraction) trả kết quả trích nhiệm vụ mà consumer EVT-24 ghi ngay sau khi lập chỉ mục văn bản.

Mọi POST bắt buộc có `Idempotency-Key` (`ai.idempotency_key`, giữ 48 giờ): cùng khoá cùng nội dung thì trả lại phản hồi lần đầu, khác nội dung thì 422 `COMMON-422-IDEMPOTENCY-MISMATCH`. Thân yêu cầu được kiểm theo `app/assist/schemas.json`, tệp sinh từ hợp đồng bằng `uv run python -m app.assist.sync_schemas`; kiểm thử sẽ báo lệch nếu quên chạy lại sau khi hợp đồng đổi. Mỗi gợi ý con có một dòng `ai.suggestion_log` riêng để DV-14-06 ghi quyết định. Mô hình giả (`LLM_MODE=mock`) đọc lược đồ JSON mà ca dùng nêu trong lời nhắc và trả một thể hiện hợp lệ, có trích nguồn `[#1]` khi có tài liệu. Muốn thử với mô hình nhỏ dev thì chạy `AI_DEV_LLM_URL=http://127.0.0.1:35340 uv run pytest -s -k dev_model tests/test_assist_integration.py`.

## Quyết định, nguồn và tóm tắt tuần (DV-14-06)

`POST /api/v1/ai/suggestions/{id}/decision` (decideAiSuggestion) ghi `user_action` (`CHAP_NHAN`, `SUA`, `BO_QUA`), các ô đã sửa, người chấp nhận và lý do (cột bổ sung `action_reason`, đã che dữ liệu cá nhân). Chỉ người nhận gợi ý được ghi, người khác nhận 403. Với nhiệm vụ trích từ văn bản, người giao của tenant được ghi, bỏ thì phải nêu lý do (`AI-422-REASON-REQUIRED`) và phản hồi là `TaskSuggestion`. Gợi ý đã có quyết định thì trả 409 `AI-409-ALREADY-DECIDED`. `GET /api/v1/ai/suggestions/{id}/sources` (listAiSuggestionSources) trả nguồn đã dùng; đoạn RAG mà người xem không còn đọc được thì trả dạng che. `GET /api/v1/ai/suggestions` (listAiSuggestions) liệt kê gợi ý của chính người gọi theo `kind`, `useCase`, phân trang con trỏ.

Job tóm tắt tuần `python -m app.assist.weekly <tenant>...` chạy lúc 23:00 UTC Chủ nhật, tức 06:00 sáng thứ Hai giờ Việt Nam. Job lấy danh sách lãnh đạo qua `GET /config/role-bindings?role=leader&status=HIEU_LUC`, đổi token dịch vụ lấy token chỉ đọc của từng lãnh đạo (RFC 8693 `requested_subject`), rồi truy vấn analytics-api trong phạm vi của người đó. Sau đó job sinh 3 đến 5 ý, mỗi ý có nguồn kèm thời điểm dữ liệu; mô hình lỗi thì job tóm tắt theo mẫu từ chính số liệu. Mỗi lãnh đạo có một bản cho mỗi tuần ISO. Metric `ai_suggestion_decision_total{use_case, action}` đếm quyết định; view `ai.v_suggestion_acceptance` cho tỷ lệ chấp nhận theo tenant, ca dùng và ngày, tính bằng (CHAP_NHAN + SUA) / số gợi ý đã có quyết định.

## OCR minh chứng (DV-14-05)

`POST /api/v1/ai/evidence/extract` đọc tệp minh chứng người dùng vừa tải lên, trả số ký hiệu, ngày, trích yếu và nhiệm vụ khớp nhất. `POST /api/v1/ai/ocr` là API nội bộ cho evidence-service và search-indexer (kiểm `azp` theo `OCR_CLIENTS`). Tệp E2EE hoặc C3 bị từ chối 403; ảnh mờ trả 422 `AI-422-UNREADABLE`. Cấu hình: `OCR_MODE` (`mock` hoặc `viettel`), `OCR_BASE_URL`, `OCR_API_KEY`, `OBJECT_BASE_URL`.

## Giọng nói thành văn bản (DV-14-07)

`POST /api/v1/ai/speech-to-text` nhận đoạn ghi âm multipart (WebM, Ogg, WAV, MP4; tối đa 60 giây và 2 MB), kiểm kiểu theo nội dung thật, giải mã trong bộ nhớ bằng PyAV rồi gửi bộ nhận dạng. Không lưu âm thanh hay văn bản. Cấu hình: `ASR_MODE` (`mock` hoặc `openai`), `ASR_BASE_URL`, `ASR_MODEL`, `ASR_API_KEY`, `ASR_PER_USER_PER_MINUTE` (mặc định 10), `ASR_MAX_CONCURRENCY`, `ASR_MAX_WAITING`, `AI_DISABLED`.

## Hàng đợi suy luận và 202

`InferenceQueue` giới hạn số suy luận đồng thời (`LLM_MAX_CONCURRENCY`) và số yêu cầu chờ (`LLM_MAX_WAITING`), xuất metric `ai_inference_queue_length` cho HPA cùng `ai_inference_in_flight` và `ai_inference_seconds`. Khi hàng đợi đầy, hoặc suy luận chưa xong sau `LLM_SYNC_WAIT_SECONDS`, `Gateway` trả `Accepted`: API trả 202 kèm job theo mẫu chung (`status = CHO`, `resultLocation` và `Location` là `/api/v1/ai/suggestions/{id}`, MSG-17-03) và tiếp tục xử lý nền; kết quả được ghi với đúng mã đó nên `GET /api/v1/ai/suggestions/{id}` (getAiSuggestion) trả gợi ý khi xong. Số việc hoãn mỗi tiến trình tối đa `AI_DEFERRED_MAX`; vượt thì 429.

## Mô hình

`LLM_MODE=mock` (mặc định) dùng bản giả lập tất định trong mã, theo quyết định chạy hệ ngoài bằng giả lập (YC-PT-13). `LLM_MODE=openai` gọi `POST /v1/chat/completions` của vLLM hoặc máy chủ tương thích tại `LLM_BASE_URL`. Hồ sơ `backend` của `infra/dev` có dịch vụ `llm-mock` chạy llama.cpp server trên CPU với mô hình Qwen2.5-0.5B-Instruct GGUF (giấy phép Apache 2.0), đặt bí danh `viettel-llm-vi`; đặt `LLM_BASE_URL=http://127.0.0.1:35340` để thử.

## Dữ liệu

Migration `db/migration/ai/V1__ai_schema.sql` (cùng `V2__ai_rag_inbox.sql` cho inbox, chỉ mục và chính sách đọc kho dùng chung, `V3__ai_assist.sql` cho khoá chống lặp và trạng thái trích nhiệm vụ, `V4__ai_decision.sql` cho lý do quyết định và view tỷ lệ chấp nhận) chạy bằng `mig_ai` trên CSDL ở cụm `pg-sec`; extension `vector` (pgvector ≥ 0.8) phải được quản trị cài trước (`infra/dev/postgres/migrate-roles.sh` đã làm ở dev). Ba bảng của TL-03 đều có RLS FORCE theo `app.tenant_id`; `doc_chunk` có chỉ mục HNSW `vector_cosine_ops` (`m = 16`, `ef_construction = 64`), GIN trên `acl_unit_ids` và CHECK chỉ nhận C0, C1; `suggestion_log` và `guardrail_event` phân vùng theo ngày bằng `ai.fn_ensure_day_partitions`. `svc_ai` chỉ có quyền trong schema `ai` và không xoá được nhật ký gợi ý.

## Cấu hình

`DATABASE_URL` (svc_ai), `OIDC_ISSUER`, `OIDC_JWKS_URL`, `OIDC_TOKEN_URL`, `OIDC_AUDIENCE`, `AI_EXCHANGE_CLIENT_ID`, `AI_EXCHANGE_CLIENT_SECRET`, `AI_EXCHANGE_AUDIENCE`, `AI_EXCHANGE_SCOPE`, `AI_EXCHANGE_ROLE`, `OPA_URL`, `CORE_BASE_URL`, `LLM_MODE`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`, `LLM_TIMEOUT_SECONDS`, `LLM_MAX_CONCURRENCY`, `LLM_MAX_WAITING`, `LLM_SYNC_WAIT_SECONDS`, `AI_DEFERRED_MAX`, `CELL_CODE`. Bí mật chỉ nạp qua biến môi trường.

## Kiểm thử

```bash
cd services-py/ai-gateway
uv sync --python 3.12 --extra dev
uv run ruff check . && uv run mypy app && uv run pytest   # tích hợp cần Docker: pgvector/pgvector:pg17, openpolicyagent/opa:1.4.0
uv run pip-audit
AI_PERF_CHUNKS=30000 uv run pytest -s -k latency tests/test_rag_integration.py   # đo truy xuất
docker build -f ai-gateway/Dockerfile -t ai-gateway ..       # chạy từ services-py
```

Kiểm thử tích hợp áp migration lên PostgreSQL 17 + pgvector, kiểm RLS giữa hai tenant, kiểm `svc_ai` không có quyền trên schema nghiệp vụ nào, và chạy API qua OPA thật nạp gói `backend/opa` (vai trò ngoài ma trận bị 403, `ai_readonly` không đọc được nhật ký, người khác và tenant khác nhận 404), so khớp phản hồi với lược đồ `AiSuggestion` của hợp đồng.
