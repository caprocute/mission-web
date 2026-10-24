# CORE-05-12 · MOD-05 Hồ sơ tập khách hàng có phiên bản và khung tham số kế thừa có giới hạn (DC-02)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-05-12` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (nghiệp vụ lõi) |
| Luồng đề xuất | B2 |
| Cỡ việc | L |
| Phụ thuộc | NEN-044, CORE-05-02, CORE-05-04 |
| Truy vết | ADR-33 → API `policies.yaml` (`customer-profiles`, `param-constraints`) → MOD-05 → bảng `policy.customer_profile`, `policy.customer_profile_version`, `policy.param_constraint`, cột `policy.policy_binding.customer_profile_version_id` → EVT-13 khi phát hành |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Quản trị nghiệp vụ tạo, kiểm thử, phát hành một hồ sơ tập khách hàng trỏ tới đúng phiên bản các thành phần cấu hình, và cấp dưới chỉ chỉnh được tham số trong khung mà hồ sơ cho phép. Đây là nền để hồ sơ TWĐ (DL-16) và các hồ sơ khác chạy trên cùng một lõi.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/bo-sung/BS-03-ho-so-tap-khach-hang-va-diem-cam-loi.md` (ADR-33): "ADR-33", "Mô hình hồ sơ tập khách hàng", "DC-02".
- Báo cáo phân tích `/mnt/project-files/phan-tich-ueb/phan-tich-ueb-hap-thu-vao-mission.md` mục "Đề xuất cụ thể".
- TL-01 chương "Policy Pack" (dòng 2380) mục "Cấu trúc gói", "Phát hành, ký số và vòng đời", "Hiệu lực theo kỳ".
- Mã đã gộp của CORE-05-01..05-04 (`policy_pack`, `policy_rule.overridable`, `PolicyQueryApi`).

## 3. Phạm vi công việc

- Migration Flyway mới trong schema `policy` cho ba bảng và một cột của BS-03, cột chung, RLS, chú thích phân loại cột; CHECK "đã phát hành phải có chữ ký, người duyệt khác người soạn" như `policy_pack_version`.
- Vòng đời hồ sơ dùng lại máy trạng thái của gói; phát hành hồ sơ yêu cầu mọi thành phần được trỏ tới đã `DA_PHAT_HANH` và bộ ca vàng của gói đạt 100%.
- Kiểm khung khi phát hành gói cấp dưới: vi phạm trả RFC 9457 nêu tham số, giá trị, khung. Khi không có ràng buộc, giữ hành vi `overridable` hiện tại.
- Mở rộng phân giải trong `PolicyQueryApi` (đã gộp ở CORE-05-03) để trả kèm phiên bản hồ sơ và cờ điểm cắm theo tenant, khối, kỳ, có bộ đệm.
- Kiểm thử ArchUnit chặn hằng số định danh hồ sơ trong `vn.viettel.okrkpi`.

- Không viết hằng số, nhánh mã hay tên riêng của hồ sơ TWĐ trong mã lõi; mọi giá trị của khách hàng đi qua cấu hình (YC-TK-11, ADR-33). Mặc định của điểm cắm phải giữ đúng hành vi hiện tại.

## 4. Ngoài phạm vi

- Saga phát hành đa cell (CORE-05-10) chỉ cần nhận thêm loại tài liệu; giao diện quản trị hồ sơ (task WEB riêng).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/policy/**`
- `backend/mission-core/src/main/resources/db/migration/policy/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh.
- Kiểm thử: hồ sơ có thành phần chưa phát hành bị chặn; gói cấp tỉnh đặt hệ số nhóm 1,6 khi khung [1,0; 1,5] bị từ chối, 1,2 được nhận; cùng một kỳ ghim được hai hồ sơ khác nhau cho hai tenant.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
