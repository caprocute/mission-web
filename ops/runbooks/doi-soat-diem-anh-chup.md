# Sổ tay vận hành: Đối soát điểm và ảnh chụp điểm (R3)

> **Mã truy vết:** DL-12 · TL-03 "Đối soát và kiểm tra nhất quán" (dòng "Điểm và ảnh chụp") · NFR-10 · SVC-07 → MOD-04 · bảng `scoring.score_result`, `appraisal.score_snapshot`, `appraisal.reconcile_run` · EVT-10.
> **Thành phần:** `ScoreSnapshotReconciler`, `ScoreSnapshotReconcileJob` và cổng chặn ký `ScoreReconcileGate` trong mission-core (gói `appraisal.infrastructure.reconcile`).

## 1. Cách job chạy

Mỗi ảnh chụp điểm của kỳ (kể cả ảnh chụp đã bị thay thế) phải trỏ tới một kết quả tính của SVC-07 còn tồn tại, trạng thái `OK`, cùng hồ sơ, cùng `input_hash` và `policy_content_hash`. Job đọc ảnh chụp theo lô 1.000 dòng ở `appraisal`, tra các kết quả tương ứng ở `scoring` qua một kết nối chỉ đọc riêng, cộng hai phía vào băm tổng hợp 256 nhóm theo mã hồ sơ và ghi một dòng `appraisal.reconcile_run` (`job = 'R3_DIEM_ANH_CHUP'`, `entity_type = 'SCORE_SNAPSHOT'`, `detail.periodId`).

Saga chốt kỳ gọi job sau bước tính điểm; ngoài ra lịch mặc định 30 phút một lần (`mission.reconcile.score.cron`) đối soát lại mọi kỳ có ảnh chụp mới từ lần chạy trước và mọi kỳ đang `LOI`. Job chỉ bật khi có cấu hình nguồn chỉ đọc `mission.reconcile.score.source.url`, `username`, `password` (mật khẩu lấy từ kho bí mật, không ghi vào values) và `schema` (mặc định `scoring`). Tài khoản nguồn là vai trò chỉ đọc `ro_scoring` (`NOBYPASSRLS`) mà bước khởi tạo CSDL đã tạo cho mọi schema; job đặt `app.tenant_id` trong giao dịch và lọc `tenant_id` tường minh.

| Trạng thái | Ý nghĩa |
|---|---|
| `KHOP` | Mọi ảnh chụp có kết quả tương ứng khớp. |
| `DA_SUA` | Lần trước `LOI`, lần này khớp (đã sửa nguồn); `rows_repaired` là số hồ sơ được gỡ chặn. |
| `LOI` | Có ảnh chụp lệch; `detail.kinds` đếm theo loại, `detail.profiles` là các hồ sơ có ảnh chụp hiện hành lệch và đang bị chặn ký, `detail.truncated = true` khi quá 1.000 hồ sơ thì cả kỳ bị chặn. `detail.reason = 'KHONG_DOC_DUOC'` là không đọc được phía scoring: không chặn ký, lần lịch sau chạy lại. |

Loại lệch: `THIEU_KET_QUA` (ảnh chụp trỏ tới kết quả không tồn tại), `KET_QUA_LOI` (kết quả có trạng thái `LOI`), `LECH_BAM` (khác hồ sơ, `input_hash` hoặc `policy_content_hash`). Ảnh chụp đã bị thay thế mà lệch chỉ được đếm ở `detail.superseded`, không chặn ký.

## 2. Hành động xử lý: chặn ký số

Khi lần đối soát kết thúc gần nhất của kỳ là `LOI`, cổng tài liệu ký nội bộ của SVC-10 trả "chưa sẵn sàng" cho kết luận thẩm định của hồ sơ bị liệt kê và cho cả lô quyết định có chứa hồ sơ đó. Người dùng thấy yêu cầu ký chưa thể thực hiện; không có thông báo kỹ thuật nào trên giao diện.

## 3. Xử lý khi có `LOI`

1. Xem lần chạy lệch:

   ```sql
   SET app.tenant_id = '<uuid-tenant>';
   SELECT started_at, detail->'kinds', detail->>'blocked', detail->'profiles'
     FROM appraisal.reconcile_run
    WHERE job = 'R3_DIEM_ANH_CHUP' AND status = 'LOI'
    ORDER BY started_at DESC LIMIT 5;
   ```

2. `THIEU_KET_QUA` hoặc `LECH_BAM` nhiều hồ sơ cùng lúc thường do kết quả bị tính lại mà EVT-10 chưa tới hoặc rơi vào thư chết: kiểm tra thư chết của consumer `mission-core-appraisal.profile-setup` và phát lại theo `phat-lai-outbox.md`; khi ảnh chụp mới tới, lần đối soát sau khớp.
3. Lệch lẻ tẻ mà sự kiện không lỗi là dấu hiệu sửa dữ liệu trực tiếp trong CSDL: chuyển SOC điều tra, đối chiếu nhật ký kiểm toán và sổ cái; không sửa tay `score_snapshot` (bảng chỉ ghi thêm), mà yêu cầu tính lại hậu kiểm để sinh ảnh chụp mới.
4. Sau khi xử lý, chạy lại cho kỳ (job lịch tự chọn kỳ đang `LOI`) và kiểm tra trạng thái chuyển `DA_SUA`.

## 4. Ghi chú

- Ảnh chụp không lưu `result_hash`; so `result_hash` khi tính lại là việc của R4 (`doi-soat-tai-lap-diem.md`).
- Kết quả không có ảnh chụp không bị coi là lệch, vì bên nhận EVT-10 bỏ qua có chủ đích kết quả cũ hơn, hồ sơ đã khoá, luồng Nhà nước chưa đủ điểm thành phần và tác vụ kiểm tra tái lập.
- Cảnh báo: `MissionDoiSoatR3ChanKySo` (mức critical, có hồ sơ bị chặn ký trong giờ qua), `MissionDoiSoatLechChuaXuLy`.
