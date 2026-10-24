# CORE-08-12 · ADR-25 Quay lui UI Pack có kiểm tương thích Policy Pack

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-12` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | S |
| Phụ thuộc | CORE-08-11, CORE-05-03 |
| Truy vết | QT-18 → SCR-1801 → `POST /api/v1/ui/packs/{id}/versions/{v}/activate` (`activateUiPackVersion`) → MOD-08 → `config.ui_pack`, `config.ui_pack_version` (`THAY_THE` → `DA_PHAT_HANH`) → BR-18-10, MSG-18-04, lỗi `UI-409-ROLLBACK-INCOMPATIBLE` → EVT-23; ADR-25 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Cho phép quay lui UI Pack về một phiên bản `THAY_THE` trước đó trong vài phút mà không biên dịch lại, chỉ khi phiên bản đó còn tương thích với Policy Pack đang gắn cho tenant; đồng thời công bố cho MOD-05 cách hỏi "UI Pack hiện hành có tương thích dải Policy Pack mới không" để MOD-05 chặn gắn gói không tương thích (ADR-25, TL-01 "Quản trị phiên bản UI Pack và Policy Pack"). Khi xong, BR-18-10 (phát hành ≤ 1 ngày, quay lui được) được kiểm chứng bằng kiểm thử.

## 2. Đầu vào bắt buộc đọc

- `TL-01-thiet-ke-kien-truc.md` G1 "Quản trị phiên bản UI Pack và Policy Pack" (dòng 1799); ADR-25 (dòng 3632).
- `TL-02b-quy-trinh.md` QT-18 "Trạng thái đối tượng" (dòng 1991), "Ngoại lệ và xử lý" (dòng 2025: quay lui bị chặn khi biểu mẫu tham chiếu Policy Pack đã ngừng), MSG-18-04 (dòng 2102).
- `contracts/openapi/modules/config.yaml` `activateUiPackVersion`.
- Bản trích `extract/tl02b-may-chu.md` QT-18 (BE-Q18-05), `extract/tl01-xuyen-suot.md` X-G1-12.

## 3. Phạm vi công việc

- `activateUiPackVersion`: chỉ nhận phiên bản `THAY_THE` của cùng UI Pack, bắt buộc `If-Match` và Idempotency-Key, quyền theo ma trận dòng SCR-1801 (step-up nếu ma trận yêu cầu).
- Kiểm tương thích qua `config.api.PolicySchemaPort` (CORE-08-10): mọi biểu mẫu của phiên bản đích phải tham chiếu Policy Pack chưa `NGUNG` và nằm trong dải `requires.policyPack`; không đạt trả 409 `UI-409-ROLLBACK-INCOMPATIBLE` với `detail` theo MSG-18-04.
- Khi đạt: phiên bản hiện hành chuyển `THAY_THE`, phiên bản đích chuyển `DA_PHAT_HANH`, cập nhật `ui_pack.current_version_id` và con trỏ `current.json` trỏ về gói bất biến đã có (không biên dịch lại, không ký lại), phát EVT-23 qua outbox.
- Công bố `config.api.UiPackCompatibilityApi.isCompatible(tenant, policyPackRef)` để MOD-05 gọi trước khi gắn Policy Pack mới cho kỳ (TL-01: "MOD-05 chặn việc gắn Policy Pack mới cho kỳ nếu UI Pack hiện hành của tenant chưa tương thích"); lời gọi phía MOD-05 nằm ở CORE-05-10.
- Kiểm thử: quay lui hợp lệ đổi `current` và ETag; quay lui về bản dùng biểu mẫu tham chiếu gói `NGUNG` bị 409; quay lui phiên bản `NHAP` bị 409.

## 4. Ngoài phạm vi

- Lời gọi kiểm tương thích ở MOD-05 khi gắn kỳ (CORE-05-10).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh, gồm kiểm thử tích hợp Testcontainers cho ba kịch bản ở mục 3 và kiểm thử EVT-23 được phát đúng một lần.
- Thời gian từ gọi API đến con trỏ `current.json` đổi < 1 phút trong kiểm thử.
- Kiểm thử hợp đồng `activateUiPackVersion` khớp `config.yaml`.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
