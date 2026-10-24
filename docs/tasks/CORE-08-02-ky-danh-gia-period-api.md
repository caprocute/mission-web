# CORE-08-02 · QT-08/SCR-0801 Kỳ đánh giá và `PeriodApi`

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-08-01; hiện thực cổng lịch phía MOD-05 cần CORE-05-03 (bổ sung so với khung; trước đó dùng bản giả lập trong kiểm thử) |
| Truy vết | QT-08 → SCR-0801 → `GET/POST /api/v1/periods` (`listPeriods`, `createPeriod`), `GET /api/v1/periods/{id}` (`getPeriod`) → MOD-08 `config.api.PeriodApi` → `config.period`, `config.period_status_log`, `policy.policy_binding` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực domain kỳ đánh giá của MOD-08: tạo kỳ có lịch nghiệp vụ theo Policy Pack và ghim phiên bản gói cho kỳ, máy trạng thái kỳ theo baseline 9.1, và cổng công khai `PeriodApi` để các module khác hỏi kỳ hiện hành và kiểm kỳ còn ghi được. Khi xong, quản trị tenant tạo kỳ qua API đúng hợp đồng, kỳ "Đã tạo" tự chuyển `MO` vào ngày bắt đầu, và MOD-02 gọi được `PeriodApi.current()`.

## 2. Đầu vào bắt buộc đọc

- `TL-02a-quy-trinh.md` mục "QT-08. Khoá kỳ (saga chốt kỳ)" (dòng 2089), các tiểu mục "Trạng thái đối tượng" (dòng 2146) và "Quy tắc nghiệp vụ" (dòng 2163); "SCR-0801. Quản lý kỳ và tiến trình khoá kỳ" (dòng 2194).
- `TL-01-thiet-ke-kien-truc.md` mục "Giao tiếp giữa module" (dòng 553, chữ ký `PeriodApi` ở dòng 562).
- Baseline mục 9.1 (trạng thái `config.period`), TL-02a mục "Lịch nghiệp vụ của một quý" (tham số `plan_approval_deadline_rule`).
- `contracts/openapi/modules/config.yaml` các đường dẫn `/periods`, `/periods/{id}`.
- Bản trích `extract/tl02a-may-chu.md` mục QT-08 (BE-08-1), `extract/tl01-thanh-phan.md` mục B8.

## 3. Phạm vi công việc

- Viết aggregate `Period` với các trạng thái `MO`, `DANG_KHOA`, `DA_KHOA`, `MO_LAI_HAU_KIEM` và năm chuyển hợp lệ; mỗi chuyển ghi một dòng `config.period_status_log` và một bản ghi outbox trong cùng giao dịch.
- Hiện thực `createPeriod` (Idempotency-Key bắt buộc): kiểm không chồng lấn cùng `kind` và `flow` (trả 409 `PERIOD-409-OVERLAP`), tính `period_bucket`, `plan_deadline`, `submission_deadline`, `milestones` theo tham số lịch của Policy Pack, rồi để MOD-05 ghi `policy.policy_binding` cho kỳ.
- Điểm lệch cần lưu ý: TL-02a ghi "tạo kỳ và ghim gói vào `policy.policy_binding`", nhưng TL-01 đặt `config` là module gốc, không được gọi `policy` (ma trận config ← policy). Cách làm chọn (suy luận): MOD-08 khai báo cổng `config.api.PeriodCalendarPort` (đọc tham số lịch) do MOD-05 hiện thực theo nguyên tắc đảo phụ thuộc, và phát sự kiện ứng dụng nội bộ Spring Modulith "kỳ đã tạo" để MOD-05 tạo `policy_binding` trong listener có Event Publication Registry. Không đặt mã EVT mới cho sự kiện nội bộ này; ghi quyết định vào `wiki/14`.
- Hiện thực `listPeriods` (phân trang con trỏ, `limit ≤ 200`) và `getPeriod` (trả `ETag` từ `row_version`).
- Công bố `config.api.PeriodApi` với `current(tenant, cadence)` và `assertWritable(tenant, period)` đúng chữ ký TL-01; `assertWritable` ở task này chỉ đọc CSDL, bộ đệm làm ở CORE-08-03.
- Thêm tác vụ định kỳ chuyển kỳ "Đã tạo" sang `MO` vào ngày bắt đầu (suy luận từ TL-02a dòng 2156; nguồn chưa có API); dùng ShedLock hoặc cơ chế lịch của thư viện nền, chạy idempotent.
- Cảnh báo khi ngày khoá dự kiến muộn hơn tham số báo cáo trước ngày 10 tháng đầu quý sau (BR-08-12), trả trong trường cảnh báo của phản hồi `createPeriod` nếu hợp đồng có, nếu không thì chỉ ghi nhật ký và ghi chú trong báo cáo.
- Quyền theo ma trận `wiki/15a` dòng SCR-0801 qua OPA; không đặt danh sách vai trò trong mã.

## 4. Ngoài phạm vi

- Chặn ghi khi kỳ khoá và bộ đệm trạng thái kỳ (CORE-08-03).
- Lịch ngày nghỉ (CORE-08-04), lệnh khoá kỳ và saga (CORE-08-05, DV-06-05).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/main/resources/db/migration/config/**` (chỉ thêm migration mới nếu thiếu chỉ mục)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh: kiểm thử đơn vị máy trạng thái, kiểm thử tích hợp Testcontainers cho tạo kỳ, chồng lấn, phân trang, idempotency (gửi lại cùng khoá trả cùng kết quả).
- Kiểm thử hợp đồng: phản hồi `createPeriod`, `listPeriods`, `getPeriod` khớp `config.yaml`; `openapi-diff` không báo phá tương thích.
- ArchUnit và `ApplicationModules.verify()` xanh; gói `config` không import gói nào của `policy`.
- Độ phủ dòng lớp `domain` và `application` ≥ 80%.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
