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

## 4. Đối soát R2: sổ cái ↔ bảng nguồn

Job R2 (`R2_SO_CAI_NGUON`, DL-12) chạy trong mission-core lúc 02:30 hằng đêm (`mission.reconcile.ledger.cron`) cho ngày hôm trước. Job chỉ bật khi đã khai `mission.reconcile.ledger.base-url`. Mỗi tenant được lấy mẫu 1% số đối tượng có sự kiện pháp lý trong ngày, ít nhất một đối tượng và tối đa 500. Với mỗi đối tượng trong mẫu, job tính lại `payload_hash` từ thân Avro ở `outbox_event`, so với bản ghi cùng mã sự kiện trong sổ cái, rồi so tải trọng với dòng nghiệp vụ hiện hành. Kết quả ghi vào `<schema>.reconcile_run`, mỗi (tenant, ngày) một dòng. Hiện chỉ có nguồn của schema `task` (EVT-04, EVT-05).

Vì outbox chỉ giữ 3 ngày, job phải chạy được trong vòng 2 ngày sau ngày cần đối soát. Ngày đã bị dọn khỏi outbox thì không đối soát lại được.

**`MissionDoiSoatR2SoCaiLechNguon`** (mức critical, đội SOC). Tra các dòng lệch:

```sql
SELECT started_at, buckets_total, buckets_mismatch, detail -> 'mismatches'
  FROM task.reconcile_run WHERE job = 'R2_SO_CAI_NGUON' AND status = 'LOI' ORDER BY started_at DESC LIMIT 10;
```

Mỗi phần tử của `detail.mismatches` mang `aggregateId`, `eventId` (nếu có) và `kind`:

- `LECH_DU_LIEU_NGUON` (trường `field`): dòng nghiệp vụ hiện hành khác tải trọng đã ghi sổ ở trường không được đổi. Đây là dấu hiệu dữ liệu bị sửa ngoài luồng nghiệp vụ. Giữ nguyên dữ liệu, báo SOC, tra nhật ký kiểm toán CSDL theo `aggregateId` và đối chiếu bản ghi sổ cái qua màn hình tra cứu sổ cái của thanh tra.
- `LECH_BAM_PAYLOAD`: thân sự kiện ở outbox khác bản đã ghi sổ (`ledgerHash`, `recomputedHash`). Hoặc outbox bị sửa, hoặc lược đồ người ghi đã đổi mà bộ chuyển tiếp chưa cập nhật bản chép lược đồ. Kiểm tra lược đồ trước, sau đó xử lý như sự cố toàn vẹn.
- `THIEU_SO_CAI`: sự kiện đã ở outbox quá 1 giờ (`mission.reconcile.ledger.grace`) mà sổ cái chưa có. Kiểm tra độ trễ của consumer `ledger-forwarder` và `ledger-ingester`, cùng topic thư chết `<topic>.dlq.ledger-forwarder`.
- `THAN_AVRO_HONG`: thân ở outbox không đọc được theo lược đồ hiện hành.

**Trạng thái `LOI` có `detail.reason = SO_CAI_KHONG_PHAN_HOI`**: job không đọc được sổ cái, chẳng hạn ledger-service ngừng, token dịch vụ bị từ chối hoặc client chưa nằm trong `TENANT_DELEGATING_CLIENTS` của ledger-service. Trường hợp này không tính là lệch. Sửa kết nối xong thì đợi lần chạy đêm sau. Job chỉ đối soát ngày hôm trước, nên ngày bị lỡ không tự chạy lại; chưa có lệnh chạy tay (suy luận: bổ sung khi cần).

**`MissionDoiSoatR2NgungChay`**: không có lần R2 nào kết thúc trong 30 giờ. Kiểm tra cấu hình `mission.reconcile.ledger.base-url` và nhật ký của mission-core lúc 02:30.
