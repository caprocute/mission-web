# CPU primary PostgreSQL cao

> **Truy vết:** TL-04 mục 10.5 · TL-05 NG-03 · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `CNPGPrimaryCPUHigh` (warning: CPU container postgres của primary trên 75% giới hạn, kéo dài 15 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Primary của một cụm PostgreSQL gần bão hoà CPU.

## Tác động

Truy vấn chậm, khoá chờ, request API chậm theo ([slo-latency](slo-latency.md)). SEV-3; SEV-2 nếu SLO đã vi phạm.

## Kiểm tra nhanh

```sql
SELECT left(query, 100) AS cau, calls, round(total_exec_time) AS ms, round(mean_exec_time::numeric, 1) AS tb
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10;
SELECT pid, state, wait_event_type, now() - query_start AS lau, left(query, 80)
FROM pg_stat_activity WHERE state <> 'idle' ORDER BY lau DESC LIMIT 10;
```

## Các bước

1. **Nếu một truy vấn mới chiếm phần lớn** (sau phát hành): báo đội phát triển, cân nhắc quay lui OPS-05.
2. **Nếu là tác vụ nền** (tính điểm hàng loạt, xuất báo cáo): kiểm có đang khoá kỳ không; bật suy giảm mức 2 để tạm dừng tác vụ không gấp (`../degradation.md`).
3. **Nếu migration đang chạy:** [OPS-09](../ops/ops-09-migration-csdl.md).
4. **Nếu tải đọc cao kéo dài nhiều kỳ:** ghi vào NG-03 ([nang-bac](nang-bac.md)).

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Tạm dừng tác vụ nền không gấp | `vtctl -c <cell> degrade pin 2 --for <thời hạn> --confirm` | `../degradation.md` |

## Kiểm sau khi làm

CPU primary dưới 75% trong 30 phút; cảnh báo tự đóng.

## Quay lui

Bỏ mức suy giảm đã ghim.

## Leo thang

CPU trên 90% kèm SLO vi phạm: gọi DBA cấp 2.

## Liên quan

[pgbouncer](pgbouncer.md), [slo-latency](slo-latency.md), [nang-bac](nang-bac.md).
