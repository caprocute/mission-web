# OPS-08 · Chuyển tenant giữa cell

> **Truy vết:** TL-04 mục 11.4 (nhân bản logic PostgreSQL 17, đối soát dòng và băm, đổi SVC-03 và DNS, giữ nguồn 30 ngày) · TL-01 · ADR-29 · SVC-03 (`requestTenantMove`, `approveTenantMove`, `getTenantMove`, `cancelTenantMove`).
> **Chu kỳ:** theo yêu cầu · **Chịu trách nhiệm:** DBA, đội nền tảng · **Tự động hoá:** một phần.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Tenant phát triển vượt cell pool, sáp nhập đơn vị, hoặc OPS-23 vừa dựng cell mới và cần chuyển tenant vào; quyết định qua CAB thường (OPS-19).

## Tác động

Tenant chỉ đọc 5–15 phút ở bước chốt; mọi bước khác không ảnh hưởng người dùng. Sổ cái được chuyển nguyên chuỗi, chuỗi ở cell đích nối tiếp `entry_hash` cuối của cell nguồn kèm bản ghi "chuyển cell" ký bằng khoá của cả hai cell.

## Kiểm tra nhanh

```bash
vtctl -e <env> cell list                              # cell đích có đăng ký, còn dư năng lực
vtctl -e <env> tenant status <tenant-id>              # cell hiện tại, trạng thái DANG_VAN_HANH
vtctl -e <env> cell move status <tenant-id> <move-id> # khi đã có yêu cầu
```

## Các bước

Quy trình chi tiết, gồm câu lệnh publication và subscription, đối soát và bốn mắt, nằm ở `../chuyen-tenant-giua-cell.md`. Tóm tắt thứ tự:

1. Chuẩn bị (mục 2 của sổ tay chi tiết): cấu trúc ở cell đích, dung lượng, khoá, Keycloak realm đích.
2. Người thứ nhất đề nghị: `vtctl -e <env> cell move request <tenant-id> --target-cell <uuid-cell-đích> --model <POOL|BRIDGE|SILO> --planned-at <RFC 3339> --reason "<số văn bản>"` (chạy thử, rồi `--confirm`).
3. Người thứ hai (khác người đề nghị, vừa xác thực bậc 2) duyệt: `vtctl -e <env> cell move approve <tenant-id> <move-id> --confirm`.
4. Theo dõi `cell move status`: sao chép ban đầu, theo dõi thay đổi, sao chép minh chứng giữa hai realm Ceph, độ trễ nhân bản về 0, chỉ đọc, đồng bộ cuối, đối soát số dòng và băm theo bảng, đổi cell trong SVC-03 và bản ghi DNS, mở ghi ở đích.
5. Kiểm chứng (mục 4 của sổ tay chi tiết), rồi đặt lịch dọn cell nguồn sau 30 ngày (mục 6).

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Đề nghị | `vtctl cell move request` | `requestTenantMove` |
| Duyệt (bốn mắt) | `vtctl cell move approve` | `approveTenantMove` |
| Theo dõi | `vtctl cell move status` | `getTenantMove` |
| Huỷ (trước khi đổi danh bạ) | `vtctl cell move cancel` | `cancelTenantMove` |

## Kiểm sau khi làm

Đối soát số dòng và băm khớp mọi bảng; `vtctl tenant status` báo cell đích; người dùng đăng nhập và ghi được; `ledger-verify` hợp lệ ở cell đích trên chuỗi nối tiếp.

## Quay lui

Trước khi đổi danh bạ: `vtctl cell move cancel`, tenant tiếp tục ở cell nguồn. Sau khi đổi: theo `../chuyen-tenant-giua-cell.md` mục 5; dữ liệu nguồn còn giữ 30 ngày nhưng ghi mới ở cell đích phải được chuyển ngược, nên chỉ quay lui khi IC quyết định.

## Leo thang

Đối soát lệch hoặc thời gian chỉ đọc vượt 15 phút: dừng ở trạng thái chỉ đọc, gọi DBA cấp 2 và IC; không mở ghi ở cả hai cell.

## Liên quan

`../chuyen-tenant-giua-cell.md`, [OPS-23](ops-23-them-cell-moi.md), [dr-14](../canh-bao/dr-14.md).
