# Sổ tay vận hành: Theo dõi và xử lý saga chốt kỳ (`saga-chot-ky`)

> **Mã truy vết:** DV-06-06 · QT-08 · SCR-0801 · SVC-06 điều phối MOD-08, SVC-07, SVC-11, SVC-10, SVC-09 · bảng `flowable.saga_instance` (`saga_type = CHOT_KY`), `flowable.saga_step` · EVT-09, EVT-10, EVT-17, EVT-18, EVT-19, EVT-21, EVT-26 · BR-08-03..BR-08-08, BR-08-10 · NFR-06.
> **Cảnh báo:** dòng nhật ký `MSG-08-04` và bộ đếm `okrkpi_saga_period_close_over_budget_total{step=...}` của workflow-service.

## 1. Saga chạy những gì

Cán bộ quản trị khởi chạy khoá kỳ ở SCR-0801; MOD-08 gọi `POST /api/v1/workflow/sagas/period-close` với khoá chống lặp theo kỳ. SVC-06 tạo một saga `CHOT_KY` (mã tương quan là lệnh khoá kỳ, khoá độc quyền là kỳ) rồi chạy sáu bước theo thứ tự TL-02a. Cột "Số bước" là `saga_step.step_no`, trùng `config.period_lock_step.step_no` của MOD-08.

| Số bước | Mã bước | Bên làm | Ngân sách | Thử lại | Bù trừ |
|---|---|---|---|---|---|
| 1 | `KIEM_TIEN_DIEU_KIEN` | MOD-08 | 5 phút | 3 lần cho lỗi kỹ thuật; không đạt thì dừng ngay | Không |
| 2 | `DAT_DANG_KHOA` | MOD-08 | 1 phút (cộng thời gian xả marker) | 5 lần | `DAT_LAI_MO`: kỳ về `MO`, phát EVT-26 |
| 3 | `TINH_DIEM_CHOT_KY` | SVC-07 | 2 giờ cho 1,2 triệu hồ sơ | 3 lần (lô lỗi SVC-07 tự thử 3 lần) | `DANH_DAU_LO_THAY_THE` |
| 4 | `SINH_BIEU_MAU` | SVC-11 | cùng bước 5 trong 4 giờ | 3 lần | `THU_HOI_TEP` |
| 5 | `KY_SO_LO` | SVC-10 | cùng bước 4 trong 4 giờ | 13 lần, lùi 1, 2, 4… tới 30 phút, tổng khoảng 4 giờ | `HUY_YEU_CAU_KY`; bước này xong là điểm không quay lại |
| 6 | `HOAN_TAT` | MOD-08 rồi SVC-09 | 10 phút | 5 lần | Không |

Mỗi bước từ 2 đến 6 giao một tác vụ dài cho bên làm với mã tác vụ do saga cấp, rồi lượt đối chiếu (mặc định 5 giây một lần) thăm dò trạng thái tác vụ đó. Bước ký số lô hoàn tất là saga qua điểm không quay lại (BR-08-09): kỳ sau đó chỉ mở lại bằng quy trình mở lại hậu kiểm bốn mắt, không bằng bù trừ.

## 2. Theo dõi

- Tiến độ tổng: `GET /api/v1/jobs/{sagaId}` (loại `PERIOD_LOCK`); SCR-0801 đọc tiến độ từng bước từ MOD-08.
- Vết từng bước: sự kiện `sys.audit.v1` với `purpose = CHOT_KY`, `objectType = SAGA_BUOC`, `objectId = <sagaId>:<số bước>:<mã bước>`; hành động `SAGA_BAT_DAU_BUOC`, `SAGA_CHO_TRA_LOI`, `SAGA_NHAN_TRA_LOI`, `SAGA_BUOC_LOI`, `SAGA_CHO_XU_LY`, `SAGA_HOAN_TAT`.
- Trạng thái trong CSDL (chạy với vai trò chỉ đọc của schema `flowable`, đặt ngữ cảnh tenant trước):

```sql
SELECT s.step_no, s.step_code, s.status, s.attempts, s.last_error, s.started_at, s.finished_at
FROM flowable.saga_step s
JOIN flowable.saga_instance i ON i.id = s.saga_instance_id
WHERE i.saga_type = 'CHOT_KY' AND i.correlation_id = :lock_order_id
ORDER BY s.step_no;
```

## 3. Xử lý theo tình huống

**Tác vụ `THAT_BAI` với `PERIOD-422-PRECONDITION`.** Tiền điều kiện không đạt (MSG-08-02): chi tiết lỗi liệt kê từng điều kiện. Saga đã kết thúc, kỳ chưa bị khoá. Đơn vị xử lý tồn đọng (đề nghị gia hạn, lệch tổ chức mức Cao, lỗi đồng bộ), rồi cán bộ lập lệnh khoá mới; lệnh cũ không chạy lại được vì mã tương quan đã dùng.

**Cảnh báo `MSG-08-04` (quá ngân sách).** Không dừng saga. Kiểm tra bên làm của bước trong bảng mục 1: bước 3 xem `scoring-lag` và hàng đợi `score_job`; bước 4, 5 xem hàng đợi sinh tệp và HSM; bước 6 xem `ledger-lag`. Báo cán bộ quản trị nếu dự kiến trễ quá cửa sổ chốt kỳ.

**Saga `LOI_CAN_XU_LY`.** Một bước đã hết lần thử. Đọc `last_error` của bước hiện tại:

- `Chưa nối <bên> ... (chờ <task>)`: bên tham gia chưa có API nội bộ, môi trường đang chạy cổng `CHUA_NOI` (xem mục 4). Không thử lại cho tới khi task đó được triển khai.
- Lỗi kỹ thuật của bên làm (mạng, HSM, quá thời hạn chờ): khắc phục rồi gọi thử lại bước (`THU_LAI`, bộ đếm lần thử về 0). Bước trước không chạy lại.
- Cần huỷ toàn bộ (trước khi bước ký số lô xong): người thứ hai (AC-09, khác người đề nghị) duyệt huỷ trên SCR-0801 (`requestPeriodLockCancel`, `approvePeriodLockCancel`); SVC-06 kiểm lại hai người khác nhau rồi bù trừ ngược từ bước hiện tại, kể cả lệnh đang chờ (huỷ yêu cầu ký, thu hồi tệp, đánh dấu lô điểm bị thay thế), về bước khoá (kỳ về `MO`, EVT-26 đúng một lần). Không có lệnh xoá dữ liệu đã niêm phong hay tệp Object Lock. Sau khi ký xong, yêu cầu huỷ trả 409 `PERIOD-409-POINT-OF-NO-RETURN`; dùng quy trình mở lại hậu kiểm.

**Bước tính điểm `LOI_CAN_XU_LY` với "Còn N hồ sơ tính điểm lỗi".** Saga giữ bước, không tính lại cả kỳ. Xem hàng đợi lỗi ở SCR-0801, sửa nguyên nhân rồi chạy lại hồ sơ lỗi (`retryFailedPeriodLock`, kèm `Idempotency-Key`; gọi trùng khoá không tạo lô mới), hoặc để AC-04 chấp nhận để lại hồ sơ lỗi kèm lý do; saga đi tiếp khi hàng đợi rỗng hoặc đã chấp nhận.

**Bước tính điểm `LOI_CAN_XU_LY` với "Kiểm tra tính lại phát hiện N hồ sơ…" (BR-08-11, chỉ số `okrkpi_saga_period_close_determinism_mismatch_total`).** Mẫu 0,1% hồ sơ tính lại cho kết quả khác kết quả đã lưu: nghi lỗi tất định hoặc dữ liệu bị sửa. Không cho saga đi tiếp; báo đội tính điểm và đội ATTT đối chiếu `score_trace`, sổ cái. Sau khi xác định nguyên nhân, thử lại cả bước (tính lại toàn kỳ) hoặc huỷ.

**Pod workflow-service khởi động lại giữa chừng.** Không cần làm gì: lượt chưa commit được giao lại khi khoá lượt hết hạn (mặc định 5 phút), lượt đã commit thì bước đã ghi; tác vụ đã giao ở bên làm giữ nguyên mã nên không bị giao trùng.

## 4. Cấu hình cổng bên tham gia

Khi bên tham gia chưa có API nội bộ, `okrkpi.saga.period-close.participants.<mod-08|svc-07|svc-11|svc-10|svc-09>` quyết định hành vi: `CHUA_NOI` (mặc định ở mọi môi trường) làm lệnh tới bên đó thất bại với thông báo rõ task còn thiếu; `GIA_LAP` coi lệnh là xong ngay, chỉ đặt ở hồ sơ `dev` để chạy thử SCR-0801. Không bao giờ đặt `GIA_LAP` ở môi trường có dữ liệu thật: saga sẽ báo hoàn tất mà kỳ không được khoá, điểm không được tính. Danh sách task còn thiếu và đề xuất hợp đồng ở wiki trang 14 mục "saga chốt kỳ (DV-06-06)".
