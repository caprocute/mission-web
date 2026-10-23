# Sổ tay vận hành: Đối soát PostgreSQL ↔ ClickHouse (R5)

> **Mã truy vết:** DL-12 · TL-03 "Đối soát và kiểm tra nhất quán" (dòng "Kho phân tích") · ADR-14 · NFR-10 · SVC-05 → SVC-15 · bảng nguồn `task.task`, `task.task_progress`, `product.evidence`, `okr.kr_checkin`, `okr.objective`, `appraisal.appraisal_profile`, `appraisal.score_snapshot` ↔ bảng sự kiện `analytics_<cell>.fact_*`; kết quả ở `<schema>.reconcile_run`.
> **Thành phần:** `AnalyticsReconciler`, `AnalyticsReconcileJob`, `ClickHouseAggregates` trong mission-core (gói `shared.infrastructure.reconcile`); đường nạp là connector Debezium phân tích (`analytics/cdc`) và materialized view (`analytics/clickhouse`).

## 1. Cách job chạy

Mỗi giờ (phút 35, `mission.reconcile.analytics.cron`), job tính ở hai phía cho mỗi (tenant, `period_bucket`) của từng cặp bảng: số dòng còn sống và tổng kiểm là tổng 32 bit đầu của MD5 mã dòng dạng chữ thường. Phía ClickHouse dùng `FINAL` và bỏ dòng `_is_deleted = 1`, gọi qua giao diện HTTP bằng GET nên chỉ đọc. Kết quả ghi một dòng `reconcile_run` ở schema nguồn cho mỗi tenant (`job = 'R5_KHO_PHAN_TICH'`, `entity_type = 'BANG_SU_KIEN'`, `buckets_total` là số nhóm đã so).

CDC trễ vài phút là bình thường, nên lần đầu một nhóm lệch chỉ được ghi vào `detail.suspects` cùng số liệu hai phía (`detail.groups`) và lần chạy vẫn `KHOP`. Nếu giờ sau nhóm đó vẫn lệch, lần chạy là `LOI`, `detail.confirmed` liệt kê nhóm lệch, `detail.repair` ghi các phân vùng (bảng sự kiện, tenant, `period_bucket`) cần nạp lại. Không đọc được ClickHouse thì `LOI` với `detail.reason = 'KHONG_DOC_DUOC_CLICKHOUSE'`.

Bật job bằng cấu hình `mission.reconcile.analytics.url` (cổng HTTP 8123 của ClickHouse trong cell), `database` (`analytics_<cell>`), `username`, `password` (mật khẩu lấy từ kho bí mật). Tài khoản cần quyền `SELECT` trên các bảng `fact_*` và không gắn row policy theo tenant, vì job so mọi tenant của cell trong một truy vấn.

## 2. Xử lý khi có `LOI`

1. Xem lần chạy lệch:

   ```sql
   SET app.tenant_id = '<uuid-tenant>';
   SELECT started_at, detail->'confirmed', detail->'groups', detail->'repair', detail->>'reason'
     FROM <schema>.reconcile_run
    WHERE job = 'R5_KHO_PHAN_TICH' AND status = 'LOI'
    ORDER BY started_at DESC LIMIT 5;
   ```

2. `KHONG_DOC_DUOC_CLICKHOUSE`: kiểm tra ClickHouse của cell và tài khoản đối soát; lần chạy giờ sau tự so lại.
3. Kho ít dòng hơn nguồn: kiểm tra connector phân tích, `cdc_error` trong ClickHouse (dòng lỗi chuyển đổi) và độ trễ consumer của bảng Kafka engine `src_*`. Nếu connector đã chạy lại mà nhóm vẫn lệch, nạp lại phân vùng: xoá phân vùng `period_bucket` của bảng sự kiện cho tenant đó (`ALTER TABLE analytics_<cell>.fact_x DELETE WHERE tenant_id = ... AND period_bucket = ...`), rồi yêu cầu snapshot lại bảng nguồn cho đúng tenant và kỳ. Connector hiện chưa bật kênh tín hiệu Debezium (`signal.enabled.channels`) nên snapshot lại cần nhóm phân tích bật kênh này hoặc chạy lại connector với `snapshot.mode` phù hợp; đây là việc của DV-15-02.
4. Kho nhiều dòng hơn nguồn: thường là bản ghi xoá chưa tới hoặc lỗi `mission_cdc_deleted`; kiểm tra thông điệp xoá (`__op = 'd'`) của bảng đó trên topic `cdc.*`.
5. Sau khi xử lý, kiểm tra lần chạy giờ kế tiếp không còn `LOI`.

## 3. Ghi chú

- Nhóm theo (tenant, kỳ), không theo đơn vị: bảng sự kiện không cùng có cột đơn vị (đơn vị của nhiệm vụ nằm ở bảng cầu `fact_task_assignee`).
- Cảnh báo: `MissionDoiSoatLechChuaXuLy` (có `LOI` trong hai giờ), `MissionDoiSoatR5NgungChay` (ba giờ không có lần nào kết thúc).
