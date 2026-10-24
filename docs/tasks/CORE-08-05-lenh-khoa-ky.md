# CORE-08-05 · QT-08/SCR-0801 Lệnh khoá kỳ: lập, duyệt và khởi chạy saga

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh CORE-08-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 |
| Luồng đề xuất | B2 |
| Cỡ việc | M |
| Phụ thuộc | CORE-08-02, DV-06-05 |
| Truy vết | QT-08 → SCR-0801 → `POST /api/v1/periods/{id}/lock-orders` (`createPeriodLockOrder`), `POST /api/v1/periods/{id}/lock-orders/{oid}/approve` (`approvePeriodLockOrder`), `POST /api/v1/periods/{id}/lock` (`startPeriodLock`), `GET /api/v1/periods/{id}/lock` (`getPeriodLockProgress`) → MOD-08, SVC-06 saga `chot-ky` → `config.period_lock_order`, `config.period_lock_step` → BR-08-01, BR-08-02, BR-08-08 → EVT-21 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực phần MOD-08 của khoá kỳ: AC-02 lập lệnh khoá kỳ, AC-04 khác người lập duyệt có MFA, rồi AC-02 (khác người duyệt) khởi chạy saga `chot-ky` ở SVC-06 bằng lệnh idempotent trả 202 và theo dõi được tiến độ. Khi xong, chuỗi lập → duyệt → khởi chạy chạy đầu cuối với saga thật hoặc saga kiểm thử, mọi vi phạm tách vai trò và MFA bị chặn đúng mã lỗi.

## 2. Đầu vào bắt buộc đọc

- `TL-02a-quy-trinh.md` mục "QT-08. Khoá kỳ (saga chốt kỳ)": "Mô tả các bước" (dòng 2118), "Quy tắc nghiệp vụ" (BR-08-01, BR-08-02, BR-08-08, dòng 2163), "Ngoại lệ và xử lý" (dòng 2180), SCR-0801 (dòng 2194), MSG-08-01, MSG-08-02 (dòng 2240).
- `TL-03-thiet-ke-csdl.md` mục "Bảng config.period_lock_order (bổ sung)" (dòng 1612) và "Bảng config.period_lock_step (bổ sung)" (dòng 1629).
- `contracts/openapi/modules/config.yaml` các đường dẫn `/periods/{id}/lock-orders`, `/periods/{id}/lock-orders/{oid}/approve`, `/periods/{id}/lock`; `contracts/openapi/modules/platform.yaml` `/workflow/sagas/period-close` (`startPeriodCloseSaga`); `contracts/openapi/modules/jobs.yaml`.
- `wiki/15-phan-quyen.md` (tách vai trò, step-up) và `wiki/15a-ma-tran-phan-quyen.md` dòng SCR-0801.

## 3. Phạm vi công việc

- `createPeriodLockOrder`: AC-02 lập lệnh với kỳ, căn cứ, thời điểm dự kiến; chặn khi đã có lệnh chưa huỷ cho kỳ (`PERIOD-409-ORDER-EXISTS`); thời điểm dự kiến phải sau hôm nay ít nhất 1 ngày làm việc (dùng `WorkingCalendarApi`); phát EVT-21 cho AC-04 qua outbox.
- `approvePeriodLockOrder`: OPA kiểm người duyệt có vai trò AC-04 và khác người lập (`PERIOD-403-SAME-PERSON`), token có ACR step-up còn hạn ≤ 15 phút (BR-08-02); ghi `approved_by/at`, phát EVT-21 với nội dung MSG-08-01.
- `startPeriodLock`: bắt buộc Idempotency-Key dạng `CK-{năm}{kỳ}`, người khởi chạy khác người duyệt, MFA ≤ 15 phút; gọi SVC-06 khởi động saga `chot-ky` (qua cổng HTTP nội bộ hoặc sự kiện theo DV-06-05), lưu `saga_id`, trả 202 + `Location` tới `/jobs/{id}`; saga đang chạy cho kỳ trả `PERIOD-409-SAGA-RUNNING` (BR-08-08).
- `getPeriodLockProgress`: đọc `config.period_lock_step` (bản chiếu từ `flowable.saga_step`) và số liệu lô để trả tiến độ, tốc độ, dự kiến hoàn tất.
- Chốt máy trạng thái `period_lock_order` (6 giá trị theo TL-03) bằng enum và kiểm thử; ghi quyết định vào `wiki/14` nếu TL-03 chưa liệt kê đủ tên.
- Mọi bước ghi sổ cái qua outbox (BR-08-10) theo chuẩn của thư viện nền.

## 4. Ngoài phạm vi

- Các bước saga 0–5, bù trừ, tạm dừng, thử lại, huỷ (nhóm DV-06 và các task saga khoá kỳ).
- Chặn ghi khi kỳ khoá (CORE-08-03).

## 5. Tệp được phép sửa

- `backend/mission-core/src/main/java/vn/viettel/okrkpi/config/**`
- `backend/mission-core/src/test/java/vn/viettel/okrkpi/config/**`
- `deploy/policies/**` (chỉ quy tắc OPA cho lệnh khoá kỳ nếu NEN-040 chưa phủ)
- `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `cd backend && mvn -q -pl mission-core -am verify` xanh; kiểm thử tích hợp Testcontainers (PostgreSQL, Kafka, Keycloak) cho: tự duyệt bị chặn, thiếu step-up bị chặn, gửi lại `startPeriodLock` cùng khoá trả cùng job, khởi chạy lần hai khi saga đang chạy trả 409.
- `opa test` xanh nếu có sửa chính sách.
- Kiểm thử hợp đồng bốn thao tác khớp `config.yaml`, `openapi-diff` không phá tương thích.
- Báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
