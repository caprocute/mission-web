# Sổ tay vận hành: Tái lập điểm đã chốt (R4)

> **Mã truy vết:** DL-12 · TL-03 "Đối soát và kiểm tra nhất quán" (dòng "Tái lập điểm") · NFR-10 · SVC-07 · bảng `scoring.score_job` (`KIEM_TRA_TAI_LAP`), `scoring.score_input_v`, `scoring.score_result`, `scoring.reconcile_run`.
> **Thành phần:** `ScoreRecomputeReconciler`, `ScoreRecomputeReconcileJob` trong scoring-service (gói `scoring.infrastructure.reconcile`); tính lại do bộ máy tính của DV-07-06 thực hiện.

## 1. Cách job chạy

Hằng tuần (mặc định 02:00 Chủ nhật, `mission.reconcile.recompute.sample-cron`), job lấy mẫu 0,5% (`rate`) kết quả `OK` của các tác vụ `CHOT_KY` đã xong trong 400 ngày gần nhất, tối đa 2.000 kết quả mỗi tác vụ gốc. Mẫu tất định theo tuần ISO. Với mỗi tác vụ gốc có mẫu, job mở một tác vụ `KIEM_TRA_TAI_LAP` cùng kỳ, cùng phiên bản quy tắc, phạm vi `{"recheckOf": <tác vụ gốc>, "results": [...]}`; bộ máy tính đọc lại `score_input_v` theo băm đầu vào và ghi kết quả mới vào tác vụ kiểm tra. Bên nhận EVT-10 bỏ qua loại tác vụ này nên không sinh ảnh chụp.

Mỗi giờ (`evaluate-cron`), job so `result_hash` của kết quả tính lại với kết quả gốc của cùng hồ sơ và kết thúc dòng `scoring.reconcile_run` (`job = 'R4_TAI_LAP_DIEM'`, một dòng mỗi tenant mỗi tuần).

| Trạng thái | Ý nghĩa |
|---|---|
| `DANG_CHAY` | Đã mở tác vụ kiểm tra, đang chờ bộ máy tính. |
| `KHOP` | Mọi kết quả tính lại trùng băm. |
| `LOI` | `detail.mismatched` kết quả khác băm, `detail.mismatches` ghi tối đa 100 mã kết quả gốc, mã kết quả tính lại, `engineFrom`, `engineTo`; hoặc `detail.reason = 'CHUA_TINH_LAI_XONG'` khi quá hạn (`timeout`, mặc định 2 ngày) mà tác vụ kiểm tra chưa xong. |

Bật job: đặt biến `MISSION_RECONCILE_RECOMPUTE_ENABLED=true` và `MISSION_RECONCILE_RECOMPUTE_TENANTS=<uuid>,<uuid>` (các tenant của cell) qua khoá `env` của values chart scoring-service, chỉ sau khi bộ máy tính đã nhận tác vụ `KIEM_TRA_TAI_LAP`.

## 2. Xử lý khi có `LOI`

1. Xem chi tiết:

   ```sql
   SET app.tenant_id = '<uuid-tenant>';
   SELECT started_at, detail->>'mismatched', detail->'mismatches', detail->>'reason'
     FROM scoring.reconcile_run
    WHERE job = 'R4_TAI_LAP_DIEM' ORDER BY started_at DESC LIMIT 4;
   ```

2. `engineFrom` khác `engineTo`: phiên bản bộ máy tính mới cho kết quả khác với cùng đầu vào và cùng quy tắc, tức thay đổi hành vi không chủ đích. Chặn triển khai phiên bản mới, so vết tính (`score_trace`) của hai kết quả để tìm quy tắc lệch, báo nhóm tính điểm. Không sửa kết quả đã chốt.
3. Cùng phiên bản mà khác băm: tính toán không tất định (thứ tự, làm tròn, thời gian hệ thống) hoặc dữ liệu đã bị sửa; điều tra như sự cố an toàn dữ liệu.
4. `CHUA_TINH_LAI_XONG`: kiểm tra hàng đợi tác vụ tính và consumer của bộ máy tính.

## 3. Ghi chú

- Cảnh báo ở chart scoring-service: `ScoringTaiLapDiemLech` (có `LOI` trong ngày), `ScoringTaiLapDiemNgungChay` (9 ngày không có lần nào kết thúc).
- Kết quả tính lại không ghi đè kết quả gốc và không sinh ảnh chụp; chúng chỉ là bằng chứng tái lập.
