# CORE-05-08 · QT-15 So sánh phiên bản và đánh giá xem trước

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-08` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | S |
| Phụ thuộc | CORE-05-04 |
| Truy vết | QT-15 → SCR-1501, SCR-1503 → `GET /api/v1/policies/packs/{id}/versions/{v}/diff` (`diffPolicyPackVersions`), `POST /api/v1/policies/packs/{id}/versions/{v}/evaluate` (`evaluatePolicyVersion`) → MOD-05 → `policy.policy_rule`, `policy.decision_table`, `policy.golden_test_case` → MSG-15-07 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Giúp người soạn và người duyệt thấy rõ thay đổi: so sánh hai phiên bản (quy tắc, tham số, bảng quyết định, tham số luồng duyệt) và tính thử một ca của bộ vàng trên máy chủ để trình duyệt so với cel-js. Khi xong, nút "So sánh" ở SCR-1501, báo cáo khác biệt khi phê duyệt ở SCR-1503 và chức năng xem trước dùng API thật.

## 2. Đầu vào bắt buộc đọc

- `TL-02b-quy-trinh.md` QT-15 "Mô tả các bước" (dòng 1315), "SCR-1501" (dòng 1382), "SCR-1503" (dòng 1495), MSG-15-07.
- `contracts/openapi/modules/policies.yaml` `/diff` (tham số `base` semver) và `/evaluate` (schema `PolicyEvaluateRequest`).
- Bản trích `extract/tl02b-may-chu.md` QT-15 (BE-Q15-07).

## 3. Phạm vi công việc

- `diffPolicyPackVersions`: so theo khoá quy tắc, khoá bảng, khoá tham số, khoá ràng buộc luồng duyệt; trả thêm, xoá, sửa với giá trị trước và sau; dòng DMN so theo thứ tự và nội dung ô; `base` không tồn tại trả 404.
- `evaluatePolicyVersion` (Idempotency-Key, không ghi dữ liệu nghiệp vụ): tính một ca vàng theo `caseCode` hoặc đầu vào giả lập, trả điểm, mức, vết tính các biểu thức trung gian và dòng DMN khớp, kèm câu giải thích theo mẫu.
- Điểm lệch cần lưu ý: hợp đồng ghi đánh giá chạy "trên máy chủ (SVC-07)"; task này chạy trong MOD-05 bằng cùng `vt-cel` và Flowable DMN mà SVC-07 dùng (suy luận, tránh gọi đồng bộ sang dịch vụ khác). Nếu người phụ trách muốn gọi SVC-07, đổi ở tầng `infrastructure` mà không đổi API.
- Bộ đệm chương trình đã biên dịch theo `(pack_id, version)` dùng chung với CORE-05-07.

## 4. Ngoài phạm vi

- So sánh tác động trên toàn bộ hồ sơ kỳ gần nhất (scoring-service).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử diff giữa hai phiên bản mẫu có đủ thêm, xoá, sửa; evaluate một ca trả cùng điểm với bộ chạy CORE-05-07.
- Evaluate một ca p95 ≤ 200 ms khi đệm nóng.
- Kiểm thử hợp đồng hai thao tác khớp `policies.yaml`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
