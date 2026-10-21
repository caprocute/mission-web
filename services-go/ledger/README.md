# SVC-09 Ledger Service (`services-go/ledger`)

Dịch vụ Sổ cái bất biến (`SVC-09`) viết bằng Go 1.25 theo **ADR-04**, **ADR-07**, **TL-01 §14.1** và **TL-03 §2.8, §4, §12.2**.

## Cấu trúc mô-đun

- `cmd/ledger-service/main.go`: điểm vào dịch vụ, hỗ trợ `copy-migrations`, `AUTO_MIGRATE` (dev), quản lý phân vùng định kỳ và máy chủ HTTP (`/healthz`, `/readyz`, `/metrics`).
- `db/migration/ledger/`:
  - `V1__ledger_core_tables.sql`: khởi tạo schema `ledger`, 8 bảng (`ledger_entry`, `chain_head`, `cell_seal`, `merkle_seal`, `anchor_receipt`, `verify_run`, `archive_segment`, `outbox_event`), hàm và trigger chống sửa/xoá (`fn_ledger_immutable` với mã lỗi `42501`), chú thích phân loại dữ liệu C0/C1/C2/C3.
  - `V2__ledger_partitions_rls_and_roles.sql`: cấu hình RLS (`ENABLE` + `FORCE ROW LEVEL SECURITY`), hàm `SECURITY DEFINER` quản lý phân vùng hai tầng `LIST (tenant_id) -> RANGE (recorded_month)` và phân quyền tối thiểu cho `mig_ledger`, `svc_ledger`, `ro_ledger`, `cdc_ledger`.
- `internal/partition/`: quản lý phân vùng hai tầng và tác vụ nền tạo trước phân vùng tháng kế tiếp.
- `internal/canon/`: bộ chuẩn hoá JSON theo RFC 8785 (JCS) + Unicode NFC (DV-09-02).
- `internal/ingest/`: bộ tiêu thụ lô sự kiện `sys.ledger.append.v1` và nối chuỗi băm SHA-256 64 phân vùng (DV-09-02).

## Chạy kiểm thử

```bash
cd services-go/ledger
go test -v -race ./...
govulncheck ./...
```
