# Outbox tồn đọng

> **Truy vết:** TL-04 mục 10.5 · TL-01 mẫu outbox trong cùng giao dịch · NEN-022 Debezium, `outbox-replay` · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `OutboxBacklog` (critical: hơn 10.000 sự kiện chờ hoặc sự kiện chờ lâu nhất quá 60 giây, kéo dài 2 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Sự kiện đã ghi vào bảng outbox của dịch vụ nhưng chưa được đẩy lên Kafka.

## Tác động

Mọi consumer phía sau chậm theo (điểm, thông báo, sổ cái). SEV-2.

## Kiểm tra nhanh

```bash
kubectl get kafkaconnector -A --context cell-<cell>-<site>          # trạng thái connector Debezium
kubectl describe kafkaconnector <connector> -n <namespace>
```

```sql
SELECT slot_name, active, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS tre
FROM pg_replication_slots;
```

## Các bước

1. **Nếu connector dừng hoặc lỗi:** khởi động lại connector qua Strimzi (đặt lại `spec.state: running` trong kho GitOps, hoặc xoá pod Connect để Strimzi dựng lại).
2. **Nếu replication slot không hoạt động:** kiểm kết nối CSDL của Connect, quyền `cdc_<schema>`; xem `../phat-lai-outbox.md` mục 2.
3. **Nếu Kafka từ chối ghi:** [kafka-lag](kafka-lag.md), gọi cấp 2 Kafka.
4. **Nếu đã mất một đoạn sự kiện** (slot bị xoá): phát lại từ outbox theo `../phat-lai-outbox.md` và chạy đối soát R7 `../doi-soat-outbox-kafka.md`.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Phát lại từ outbox | `vtctl -c <cell> outbox replay --schema <schema> --tenant <id> --from <t> --to <t> --confirm` | `../phat-lai-outbox.md` |

## Kiểm sau khi làm

Số sự kiện chờ về mức nền, sự kiện chờ lâu nhất dưới 60 giây; đối soát R7 kế tiếp không có `LOI`.

## Quay lui

Phát lại idempotent ở consumer (inbox); xem `../phat-lai-outbox.md` mục 6.

## Leo thang

Slot không hoạt động quá 15 phút (WAL tích tụ đe doạ đĩa CSDL): SEV-1, gọi DBA cấp 2.

## Liên quan

`../phat-lai-outbox.md`, `../doi-soat-outbox-kafka.md`, [kafka-lag](kafka-lag.md).
