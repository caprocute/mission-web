# CORE-05-03 · ADR-19 `PolicyQueryApi` và API phân giải gói theo kỳ

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-03` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-05-02 |
| Truy vết | QT-08, QT-15 → `GET /api/v1/policies/resolve` (`resolvePolicyPack`, client nội bộ) → MOD-05 `policy.api.PolicyQueryApi` → `policy.policy_binding`, `policy.policy_pack_version` → BR-08-07; hiện thực cổng `config.api.PeriodCalendarPort`, `config.api.PolicySchemaPort` của MOD-08; ADR-19 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cung cấp nguồn duy nhất để mọi bên hỏi "phiên bản Policy Pack nào áp cho tenant, kỳ, luồng, khối, đơn vị này, và tham số của nó là gì": cổng Java `PolicyQueryApi` cho các module trong lõi (bao gồm bảng chuyển trạng thái `task_lifecycle` cho MOD-02) và API nội bộ `resolvePolicyPack` cho SVC-07, SVC-06. Khi xong, MOD-02 và MOD-04 lấy quy tắc qua cổng có bộ đệm, scoring-service nhận băm và tham chiếu gói đã ký để tự kiểm trước khi dùng, và MOD-08 có hiện thực cho hai cổng đảo phụ thuộc.

## 2. Đầu vào bắt buộc đọc

- `TL-01-thiet-ke-kien-truc.md` "Giao tiếp giữa module" (dòng 553), "Máy trạng thái nhiệm vụ" (dòng 599, khoá `task_lifecycle` ở dòng 706), "Hiệu lực theo kỳ" (dòng 2517).
- `TL-02a-quy-trinh.md` phần "Lịch nghiệp vụ của một quý" và "Hai luồng đánh giá dùng chung dữ liệu" (đoạn đầu tài liệu).
- `contracts/openapi/modules/policies.yaml` `/policies/resolve`.
- Thẻ CORE-08-02 (cổng `PeriodCalendarPort`, sự kiện nội bộ "kỳ đã tạo") và CORE-08-10 (cổng `PolicySchemaPort`).
- Bản trích `extract/tl01-thanh-phan.md` mục B5, `extract/tl01-xuyen-suot.md` X-PP-09, X-PP-10.

## 3. Phạm vi công việc

- Công bố `policy.api.PolicyQueryApi`: phân giải phiên bản theo `(tenant, period, flow, subject_group, org_unit)` khớp cụ thể nhất trước; đọc tham số theo khoá; trả bảng `task_lifecycle`; trả quy tắc và bảng quyết định đã biên dịch.
- Thuật toán chọn gói khi chưa có `policy_binding` cụ thể (X-PP-10): lọc `DA_PHAT_HANH` hoặc `NGUNG`, khớp `luong`, `ap_dung_cho`, `tu_ky ≤ kỳ ≤ den_ky`, ưu tiên `tu_ky` lớn nhất rồi phiên bản; không có kết quả trả `POLICY-404-NO-BINDING`.
- Hiện thực `resolvePolicyPack` cho client dịch vụ (`serviceClient`), trả `content_hash`, tham chiếu gói đã ký để bên gọi kiểm toàn vẹn (X-PP-09).
- Bộ đệm Valkey hoặc trong pod theo `(pack_id, version)` và theo khoá phân giải; vô hiệu khi nhận sự kiện phát hành, thu hồi (EVT-13 và sự kiện nội bộ).
- Hiện thực `config.api.PeriodCalendarPort` (tham số lịch kỳ như `plan_approval_deadline_rule`) và `config.api.PolicySchemaPort` (giải `schemaRef`, liệt kê trường tính điểm, trạng thái gói) cho MOD-08; listener sự kiện nội bộ "kỳ đã tạo" của MOD-08 tạo `policy_binding` cho kỳ.
- Chống chồng khoảng hiệu lực cùng định danh và luồng ở tầng ứng dụng nếu CSDL chưa có ràng buộc (theo quyết định ở CORE-05-01).

## 4. Ngoài phạm vi

- Môi trường CEL và đánh giá (NEN-041, CORE-05-04); nạp gói ở scoring-service (nhóm DV-07).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers cho các tình huống phân giải: binding theo đơn vị thắng binding toàn tenant, không có binding thì theo khoảng hiệu lực, không có gói thì 404.
- Đọc `PolicyQueryApi` khi trúng đệm p95 < 1 ms trong kiểm thử vi đo.
- Kiểm thử hợp đồng `resolvePolicyPack` khớp `policies.yaml`; ArchUnit xanh (gói `config` không import `policy`).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
