# Sổ tay vận hành: Kiểm tra toàn vẹn và niêm phong sổ cái

> **Mã truy vết:** DL-12 · DV-09-03 · DV-09-04 · TL-03 "Sổ cái bất biến › Niêm phong Merkle và dấu thời gian", "Kiểm tra toàn vẹn" · QT-13 (BR-13-09, BR-13-10, BR-13-11) · ADR-09, ADR-26 · SVC-09 · bảng `ledger.verify_run`, `ledger.cell_seal`, `ledger.merkle_seal`, `ledger.chain_head`.
> **Thành phần:** ledger-service, gói `internal/seal` (niêm phong, neo) và `internal/verify` (kiểm tra); cảnh báo trong `deploy/charts/ledger-service/templates/reconcile-alerts.yaml`. Phá chuỗi băm có sổ tay riêng `dr-14.md` (cảnh báo `LedgerChainBroken`).

## 1. Lịch chạy

| Việc | Tần suất | Ghi kết quả |
|---|---|---|
| Niêm phong Merkle hai cấp, ký gốc cell, dấu thời gian RFC 3161 | Mỗi giờ; 5 phút trong 3 ngày cuối kỳ (`SEAL_PERIOD_ENDS`); đột xuất bằng `ledger-service admin seal --kind DOT_XUAT --window-end <mốc>` | `ledger.cell_seal`, `ledger.merkle_seal`, EVT-19 |
| Kiểm tra niêm phong (`NIEM_PHONG`) | Ngay sau mỗi lần niêm phong, toàn cell | `ledger.verify_run` (`tenant_id` NULL) |
| Kiểm tra đầu chuỗi (`DAU_CHUOI`) | Mỗi 15 phút, mọi tenant | `ledger.verify_run` |
| Kiểm tra toàn bộ (`TOAN_BO`) | 02:00 Chủ nhật, một phần tư số tenant theo tuần | `ledger.verify_run` |
| Kiểm tra lưu trữ (`LUU_TRU`) | 03:00 ngày đầu quý, 5% tệp Parquet | `ledger.verify_run` (chưa có trình đọc Parquet thì `THAT_BAI`) |
| Neo hằng ngày | 00:15, gốc cell cuối ngày hôm trước | `ledger.anchor_receipt` (đích `OBJECT_LOCK`; đích ngoài do SVC-13) |

## 2. Cảnh báo và xử lý

**`LedgerKiemTraNgungChay`** (không có lần kiểm tra nào kết thúc trong 1 giờ):

1. Kiểm tra pod ledger-service đang chạy và kết nối được `pg-ledger`.
2. Xem các lần kiểm tra gần nhất:

   ```sql
   SELECT verify_kind, status, status_detail, started_at, finished_at
     FROM ledger.verify_run ORDER BY started_at DESC LIMIT 20;
   ```

3. Nếu có dòng `THAT_BAI` liên tiếp, đọc nhật ký "kiểm tra toàn vẹn sổ cái thất bại" của ledger-service để tìm nguyên nhân (thường là CSDL quá tải hoặc hết thời gian chờ).

**`LedgerNiemPhongNgungChay`** (2 giờ chưa niêm phong được cửa sổ nào):

1. Nếu cùng lúc có `LedgerTSALoi`, nguyên nhân là TSA; xử lý theo mục dưới.
2. Nếu không, đọc nhật ký "niêm phong sổ cái thất bại". Lỗi "đầu chuỗi … đã đổi trong lúc niêm phong" tự hết ở lượt sau. Lỗi khoá niêm phong nghĩa là không đọc được `SEAL_KEY_FILE` hoặc không kết nối được HSM.
3. Có thể chạy niêm phong tay: `ledger-service admin seal --kind DINH_KY`. Lệnh idempotent theo mốc cuối cửa sổ.

**`LedgerTSALoi`** (TSA không cấp dấu thời gian):

Cửa sổ lỗi không được ghi. Lần niêm phong sau gộp cả khoảng và đặt `tsa_late = true` (theo TL-03). Cần kiểm tra `TSA_URL` và kết nối tới TSA. Không phải can thiệp dữ liệu.

**`LedgerDauThoiGianQuaHan`** (quá 24 giờ chưa có dấu thời gian):

Đây là sự cố pháp lý. Báo SOC và người phụ trách TSA, rồi chuyển sang TSA dự phòng bằng cách đổi `TSA_URL` và khởi động lại pod. Khi có dấu thời gian, kiểm tra cửa sổ gộp:

```sql
SELECT window_start, window_end, tsa_late, tsa_gen_time
  FROM ledger.cell_seal ORDER BY window_end DESC LIMIT 5;
```

## 3. Khi kiểm tra phát hiện phá vỡ

Ledger-service tự khoá ghi chuỗi bị phá (`chain_head.is_locked`) và phát EVT-20 mức Cao. Thông điệp của lần kiểm tra nêu bản ghi và phân vùng. Xử lý theo `dr-14.md`. Sau khi điều tra xong, mở lại chuỗi bằng bản ghi đánh dấu sự cố:

```
ledger-service admin incident-recovery --tenant <uuid> --chain <0..63> --last-valid-seal <uuid> --incident <mã> --actor <uuid> --reason <biên bản>
```
