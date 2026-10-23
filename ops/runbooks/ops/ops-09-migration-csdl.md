# OPS-09 · Migration CSDL mở rộng, thu hẹp

> **Truy vết:** TL-04 mục 11.5 · TL-03 "Quản lý thay đổi lược đồ" · SEC-05 · NEN-030 `backend/libs/vt-db-migration` (`MigrationRunner`, `SchemaVersionGuard`, `ConventionLinter`).
> **Chu kỳ:** mỗi bản phát hành · **Chịu trách nhiệm:** đội phát triển, DBA · **Tự động hoá:** Flyway.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). Kiểm thử tự động: `./mvnw -pl libs/vt-db-migration -am verify` trong `backend`, gồm `MigrationRunnerIT` (CSDL lỗi không chặn CSDL khác) và lint quy ước.

## Điều kiện kích hoạt

Bản phát hành có migration mới, hoặc Job migration của một CSDL thất bại trước khi Rollout bắt đầu.

## Tác động

Canary nghĩa là hai phiên bản cùng chạy trên một lược đồ, nên mọi thay đổi qua tối thiểu ba bản: bản N thêm cột mới và ghi cả hai cột, tác vụ nền chuyển dữ liệu cũ; bản N+1 đọc cột mới; bản N+2 xoá cột cũ. Migration chạy bằng `mig_<schema>` (có DDL), ứng dụng chạy bằng `svc_<schema>` (không DDL). `SchemaVersionGuard` không cho pod sẵn sàng khi lược đồ thấp hơn mức cần. Cụm PostgreSQL đặt `lock_timeout = 5s` và `statement_timeout = 30s` mặc định (`deploy/charts/vt-platform/ha-tang/data-pg/values.yaml`), nên migration bị chặn khoá thất bại nhanh thay vì làm tắc cell.

## Kiểm tra nhanh

```bash
kubectl get jobs -n <namespace> --sort-by=.metadata.creationTimestamp | tail    # Job migration của bản phát hành
kubectl logs job/<job-migration> -n <namespace>                                 # schema, CSDL, phiên bản lỗi
```

```sql
SELECT version, description, success, installed_on FROM <schema>.flyway_schema_history
ORDER BY installed_rank DESC LIMIT 5;
SELECT pid, wait_event_type, state, left(query, 80) FROM pg_stat_activity WHERE wait_event_type = 'Lock';
```

## Các bước

**Trước khi gộp migration (đội phát triển):**

1. Lint của `vt-db-migration` phải đạt trong `./mvnw verify`. Không có trong một bản: thêm cột `NOT NULL` không mặc định, tạo chỉ mục không `CONCURRENTLY`, đổi kiểu cột, đổi tên cột hoặc bảng cùng bản với mã dùng nó. TL-04 nêu `squawk`; repo hiện dùng `ConventionLinter`, chưa có `squawk` trong pipeline.
2. Mô tả trong merge request đây là bước nào (mở rộng, chuyển dữ liệu, thu hẹp) và bản nào sẽ thu hẹp.

**Nếu Job migration thất bại khi phát hành:**

1. Đọc log Job: bộ chạy ghi CSDL và schema lỗi; ở cell bridge, các CSDL khác vẫn chạy tiếp, mã thoát 1 chặn bản mới cho đúng tenant đó.
2. Lỗi do `lock_timeout`: tìm phiên giữ khoá bằng truy vấn `pg_stat_activity` trên; chờ hoặc phối hợp với chủ phiên, rồi chạy lại Job (Flyway chỉ áp phiên bản chưa thành công).
3. Lỗi do nội dung migration: không sửa tệp migration đã áp ở CSDL khác; đội phát triển viết migration sửa tiến với phiên bản mới, phát hành lại qua OPS-02.
4. Trong lúc chờ, Rollout giữ bản cũ vì `SchemaVersionGuard`; không tắt guard ở sản xuất.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Xem lịch sử migration | (chưa có) | truy vấn `<schema>.flyway_schema_history` bằng `ro_<schema>` |
| Chạy lại Job migration | (chưa có) | đồng bộ lại Application (`argocd app sync`) để hook tạo Job mới |

## Kiểm sau khi làm

`flyway_schema_history` của mọi CSDL có phiên bản mới với `success = true`; pod của bản mới sẵn sàng; không còn phiên chờ khoá.

## Quay lui

Không quay lui lược đồ: Flyway không chạy `undo` và bộ chạy không bao giờ `clean`. Bản cũ chạy được trên lược đồ mở rộng, nên quay lui ứng dụng (OPS-05) là đủ; phần thu hẹp chỉ gộp khi không còn bản nào cần cột cũ.

## Leo thang

Migration giữ khoá làm tăng lỗi API của cell (cảnh báo SLO bật): huỷ phiên migration (`pg_cancel_backend`) sau khi DBA cấp 2 đồng ý, rồi điều tra.

## Liên quan

`backend/libs/vt-db-migration/README.md`, `wiki/17-thiet-ke-csdl-va-da-khach-thue.md`, [OPS-05](ops-05-quay-lui-tu-dong.md), [pg-cpu](../canh-bao/pg-cpu.md).
