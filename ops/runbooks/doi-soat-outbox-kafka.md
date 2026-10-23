# Sổ tay vận hành: Đối soát outbox ↔ Kafka (R7)

> **Mã truy vết:** DL-12 · TL-03 "Đối soát và kiểm tra nhất quán" (dòng "Outbox") · ADR-03 · NFR-10 · bảng `<schema>.outbox_event`, `<schema>.reconcile_run` của tám schema mission-core.
> **Thành phần:** job `OutboxKafkaReconcileJob` trong mission-core (phút 15 mỗi giờ, `mission.reconcile.outbox.cron`); công cụ `outbox-replay` (sổ tay `phat-lai-outbox.md`) cho trường hợp phải xử lý tay.

## 1. Cách job chạy

Mỗi giờ, job lấy giờ tròn vừa qua làm cửa sổ `[from, to)`. Với từng schema, job đếm `outbox_event` theo (tenant, topic) bằng `created_at`, rồi đếm thông điệp của từng topic trên Kafka theo dấu thời gian trong `[from, to + 5 phút)`; năm phút nới (`mission.reconcile.outbox.grace`) bù độ trễ Debezium. Kafka không tách được theo tenant nếu không đọc thông điệp, nên một topic chỉ bị coi là lệch khi tổng Kafka nhỏ hơn tổng outbox của mọi tenant.

Mỗi tenant có sự kiện trong cửa sổ được một dòng `reconcile_run` ở schema nguồn (`job = 'R7_OUTBOX_KAFKA'`, `entity_type = 'OUTBOX'`), `buckets_total` là số topic của tenant, `buckets_mismatch` là số topic lệch, `detail.topics` ghi số đếm hai phía.

| Trạng thái | Ý nghĩa |
|---|---|
| `KHOP` | Không topic nào của tenant bị lệch. |
| `DA_SUA` | Có lệch và job đã tự phát lại: chép lại các dòng outbox của topic lệch trong cửa sổ với `created_at` mới, giữ nguyên `id`. Debezium phát lại cùng mã sự kiện; inbox bên nhận bỏ bản đã xử lý. `rows_repaired` là số dòng đã chép. |
| `LOI` | Có lệch nhưng không tự phát lại; `detail.reason` cho biết lý do và `detail.replay` là lệnh `outbox-replay` gợi ý. |

Lý do `LOI`: `CDC_DUNG` (Kafka không có thông điệp nào của topic sau cửa sổ trong khi outbox có, tức Debezium đang dừng; khi chạy lại nó tự đẩy phần tồn nếu slot còn), `VUOT_GIOI_HAN` (số dòng cần phát lại vượt `mission.reconcile.outbox.replay-limit`, mặc định 50.000), `TAT_TU_DONG` (`mission.reconcile.outbox.auto-replay=false`).

## 2. Xử lý khi có `LOI`

1. Xem lần chạy lệch ở schema được báo:

   ```sql
   SET app.tenant_id = '<uuid-tenant>';
   SELECT started_at, buckets_mismatch, detail->>'reason', detail->'topics', detail->>'replay'
     FROM <schema>.reconcile_run
    WHERE job = 'R7_OUTBOX_KAFKA' AND status = 'LOI'
    ORDER BY started_at DESC LIMIT 10;
   ```

2. `CDC_DUNG`: kiểm tra connector Debezium và replication slot theo `phat-lai-outbox.md` mục 2. Nếu slot mất (`wal_status = 'lost'`), tạo lại slot rồi chạy `outbox-replay` với lệnh ở `detail.replay` (chạy thử trước, rồi chạy thật). Nếu slot còn, chỉ cần khởi động lại connector; lần đối soát giờ sau sẽ khớp.
3. `VUOT_GIOI_HAN`: lệch lớn, thường do sự cố kéo dài. Chạy `outbox-replay` theo `detail.replay` thay vì nâng giới hạn, để kiểm soát tốc độ phát.
4. Sau khi xử lý, kiểm tra lần đối soát giờ kế tiếp của cùng schema không còn `LOI`.

## 3. Ghi chú

- Outbox chỉ giữ ba ngày (`pg_partman`), nên lệch phải xử lý trong ba ngày; Kafka phải còn giữ thông điệp của cửa sổ thì phép đếm mới đúng.
- Phép đếm Kafka gồm mọi bên ghi vào topic. Thông điệp do `outbox-replay` hay lần phát lại tự động ghi làm số Kafka lớn hơn outbox, không bị coi là lệch.
- Cảnh báo: `MissionDoiSoatLechChuaXuLy` (có `LOI` trong hai giờ), `MissionDoiSoatDaTuSua` (mức info, có phát lại tự động), `MissionDoiSoatR7NgungChay` (ba giờ không có lần nào kết thúc).
