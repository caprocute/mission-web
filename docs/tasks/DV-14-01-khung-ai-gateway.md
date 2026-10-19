# DV-14-01 · SVC-14/AC-12 Khung ai-gateway FastAPI, migration schema `ai`, client vLLM, đổi token sang `ai_readonly`

@@HDR@@

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 3 |
| Luồng đề xuất | B7 |
| Cỡ việc | M |
| Phụ thuộc | NEN-018; NEN-040 (gói chính sách OPA) và DV-02-01 (realm có client `ai_readonly`) nên gộp trước (bổ sung) |
| Truy vết | QT-17 (BR-17-01, BR-17-03) → API `/api/v1/ai/*` (khung chung) → SVC-14 → bảng `ai.doc_chunk`, `ai.suggestion_log`, `ai.guardrail_event` → AC-12 `ai_readonly` |
| Trạng thái | Chưa bắt đầu (môi trường dev cần GPU hoặc một mô hình nhỏ chạy CPU qua giao diện tương thích vLLM) |

## 1. Mục tiêu

Dựng ai-gateway (Python 3.12, FastAPI) trên thư viện Python dùng chung của NEN-018 với năm bước chung của mọi yêu cầu: xác thực JWT và kiểm quyền OPA, đổi token người dùng sang token `ai_readonly` mang danh tính người dùng để đọc dữ liệu lõi, gọi vLLM, ghi nhật ký. Khi xong, các ca dùng AI (DV-14-02..06) chỉ cần viết logic riêng, còn an toàn truy cập (AI chỉ đọc những gì người dùng được đọc, không có tài khoản ghi schema nghiệp vụ) đã có sẵn và được kiểm thử.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Trí tuệ nhân tạo" (L3082–3121: "Nguyên tắc", "Kiến trúc cổng mô hình", "Bốn tầng ứng dụng AI"), mục "SVC-14 ai-gateway" (L1184–1192), ADR-28 (L3662).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` "Schema ai (SVC-14)" (L3958–4014) với từ điển `doc_chunk`, `suggestion_log`, `guardrail_event`.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02b-quy-trinh.md` QT-17 (L1822), "Quy tắc nghiệp vụ" (L1864, BR-17-01..09).
- `contracts/openapi/modules/platform.yaml` tag AI (các operation `/ai/*`, vai trò `ai_readonly`).
- `TL-04-trien-khai-van-hanh.md` values SVC-14 (Uvicorn 2 worker, vLLM theo GPU) và PERF-26; bản trích `extract/tl02b-may-chu.md` QT-17 (BE-Q17-01).

## 3. Phạm vi công việc

- Tạo `services-py/ai-gateway/` (FastAPI, Uvicorn, `pyproject.toml`, ruff, mypy, pytest), cấu trúc `app/{api,core,guard,rag,llm,store}`, probe, OpenTelemetry qua NEN-018, Dockerfile không root.
- Viết migration (Alembic hoặc SQL thuần theo quy ước NEN-030) tạo `doc_chunk` (`embedding vector(1024)`, HNSW `vector_cosine_ops` `m=16`, `ef_construction=64`, `acl_unit_ids uuid[]` có GIN, UQ (`tenant_id`,`source_id`,`chunk_no`,`embed_model`), CHECK `classification` IN (`C0`,`C1`)), `suggestion_log` (phân vùng ngày; `use_case`, `model`, `prompt_template_version`, `prompt_sha256`, `prompt_redacted`, `chunk_ids`, `output_text`, CHECK `user_action` IN (`CHAP_NHAN`,`SUA`,`BO_QUA`), `edited_fields[]`, `accepted_by`, `confidence`, `sources_json`), `guardrail_event` (`rule_code`, CHECK `action` IN (`CHAN`,`CHE`,`CANH_BAO`)); RLS theo tenant; CSDL ở cụm `pg-sec`.
- Viết lớp xác thực: kiểm JWT qua JWKS Keycloak, gọi OPA với vai trò và phạm vi; token exchange (RFC 8693) sang client `ai_readonly` chỉ có scope đọc để gọi API lõi dưới danh tính người dùng; chặn mọi phương thức ghi tới API lõi ở tầng client (kiểm thử).
- Viết client vLLM (API tương thích OpenAI của vLLM) có thời hạn, giới hạn đồng thời, hàng đợi suy luận có độ dài đo được; khi hàng đợi đầy trả 202 kèm job theo mẫu chung (MSG-17-03); metric độ dài hàng đợi cho HPA.
- Viết `SuggestionLogger` ghi `suggestion_log` với `prompt_redacted` đã che C2 (không lưu C2, BR-17-07) và `prompt_sha256`.
- Thêm vào profile dev một máy chủ mô hình nhỏ chạy CPU tương thích API vLLM (ghi tên mô hình và giấy phép trong báo cáo), cờ cấu hình để trỏ sang vLLM thật.

## 4. Ngoài phạm vi

- Lập chỉ mục RAG (DV-14-02), rào chắn (DV-14-03), các API ca dùng (DV-14-04..06).
- Triển khai vLLM trên node GPU (INF tương ứng).

## 5. Tệp được phép sửa

- `services-py/ai-gateway/**`
- `infra/dev/` (máy chủ mô hình nhỏ và PostgreSQL có pgvector trong profile backend)

## 6. Tiêu chí hoàn thành

- `cd services-py/ai-gateway && ruff check . && mypy app && pytest` xanh, gồm kiểm thử tích hợp Testcontainers PostgreSQL 17 + pgvector áp migration, kiểm RLS giữa hai tenant.
- Kiểm thử chứng minh: token không có vai trò được phép bị 403; mọi lời gọi tới API lõi dùng token `ai_readonly`; client lõi từ chối POST, PUT, PATCH, DELETE.
- `pip-audit` không có lỗ hổng mức cao; tài khoản CSDL của service không có quyền trên schema nghiệp vụ nào (kiểm bằng truy vấn quyền).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
