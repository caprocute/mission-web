# CORE-02-06 · QT-04/BR-04-02..07 Kiểm tra kế hoạch và tỷ trọng nhóm theo Policy Pack

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-02-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B3 |
| Cỡ việc | M |
| Phụ thuộc | CORE-02-05, CORE-05-03; cần NEN-041 (CEL dùng chung) (bổ sung) |
| Truy vết | QT-04 → SCR-0402 → API `POST /api/v1/plans/{id}/versions/{v}/check`, `PUT /plans/{id}/versions/{v}` → MOD-02, MOD-05 (`PolicyQueryApi`), MOD-01 (`OkrApi`) → bảng `task.plan_item`, `task.catalog_item`, `policy.policy_rule` → BR-04-02..BR-04-07; MSG-04-04, MSG-04-05, MSG-04-06 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực bộ kiểm tra kế hoạch quý ở máy chủ, chạy tức thì khi soạn (kể cả với dòng chưa lưu) và chạy lại khi gửi duyệt: một chủ trì mỗi dòng, KPI mơ hồ, đủ sản phẩm, số lượng, đơn vị tính và hạn, điểm việc ngoài danh mục, tỷ trọng nhóm thường xuyên và then chốt theo Policy Pack, KR chưa có nhiệm vụ. Kết quả theo hai mức chặn và cảnh báo để màn hình SCR-0402 hiển thị ngay cạnh dòng lỗi.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-02a-quy-trinh.md` QT-04 "Mô tả các bước" bước 4 (kiểm tra tức thì, L1185), "Quy tắc nghiệp vụ" BR-04-02..BR-04-07 và công thức điểm nhóm (L1211–1230), SCR-0402 (L1291–1341).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` phần MOD-05 Policy Pack (tham số `group_weights`).
- `contracts/openapi/modules/tasks.yaml`: `checkPlanVersion` (lược đồ kết quả mức `CHAN`, `CANH_BAO`), `savePlanVersion`, `submitPlanVersion` (danh sách `rules`).
- Bộ ca CEL dùng chung của NEN-041; quy tắc từ mơ hồ dùng chung với CORE-01-04.

## 3. Phạm vi công việc

- Viết `PlanChecker` trong `task.application` trả danh sách `{rule, level, itemNo, message}` khớp lược đồ kết quả của `checkPlanVersion`.
- Hiện thực quy tắc: BR-04-02 (mỗi dòng đúng một chủ trì, phối hợp không trùng chủ trì, chặn, MSG-04-05); BR-04-03 (dòng chứa từ mơ hồ mà thiếu sản phẩm, đơn vị tính hoặc hạn là KPI mơ hồ, chặn, MSG-04-04); BR-04-04 (đủ sản phẩm, số lượng, đơn vị tính, hạn trong kỳ, chặn); BR-04-05 (việc ngoài danh mục chưa có điểm, cảnh báo khi lập); BR-04-06 (tổng điểm nhóm vượt tỷ trọng `group_weights` thì chặn, thiếu thì cảnh báo, MSG-04-06); BR-04-07 (KR đã duyệt chưa có dòng nào, cảnh báo, qua `OkrApi`).
- Tính điểm nhóm đúng công thức TL-02a: Σ(điểm danh mục × KPI nhiệm vụ) / Σ điểm danh mục × điểm tối đa nhóm; tham số tỷ trọng (ví dụ 30/70) đọc từ Policy Pack theo tenant, khối, kỳ qua `PolicyQueryApi`, không viết cứng.
- Hiện thực `checkPlanVersion` nhận cả dòng chưa lưu, không ghi CSDL; `savePlanVersion` lưu được dù có lỗi chặn (bản nháp) nhưng trả kết quả kiểm tra kèm theo; `submitPlanVersion` (CORE-02-08) gọi lại `PlanChecker` và chặn khi còn lỗi.
- Biểu diễn BR-04-03, BR-04-04 bằng CEL qua `vt-cel` để web chạy cùng bộ ca bằng cel-js; ca kiểm thử đặt trong `data/golden/plan-rules/`.

## 4. Ngoài phạm vi

- BR-04-08, BR-04-09 (CORE-02-09); BR-04-10..12 (CORE-02-10); chấm điểm việc ngoài danh mục khi duyệt (CORE-02-09).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/task/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/task/**`
- `data/golden/plan-rules/**`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` chạy xanh.
- Mỗi quy tắc BR-04-02..07 có ca đạt và ca vi phạm; ca tỷ trọng 30/70 với nhóm then chốt chiếm 75% bị chặn kèm MSG-04-06; ca thiếu chủ trì bị chặn kèm MSG-04-05.
- Bộ ca trong `data/golden/plan-rules/` cho kết quả giống nhau giữa cel-java và cel-js.
- `checkPlanVersion` với 300 dòng trả trong p95 ≤ 300 ms (suy luận, để kiểm tra tức thì không làm chậm soạn).
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
