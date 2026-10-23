# OPS-07 · Đưa tenant mới vào vận hành trong một ngày

> **Truy vết:** TL-04 mục 11.4 · NFR-07 · QT-01 · SCR-0102 · SVC-03 `services-go/tenant-registry` (saga `PROVISION`, DV-03-04, DV-03-05) · EVT `sys.tenant.provisioned.v1`, EVT-21.
> **Chu kỳ:** theo yêu cầu · **Chịu trách nhiệm:** đội triển khai khách hàng · **Tự động hoá:** saga cấp phát của tenant-registry (TL-04 gọi là pipeline onboarding).
> **Chạy thử:** diễn tập lần 1 ngày 23/10/2026 trên khung giả lập cục bộ (tenant-registry và saga thật, PostgreSQL thật, hệ ngoài giả lập), biên bản `ops/dien-tap/2026-10-23-ops-07.md`; chưa chạy trên kind hay staging (YC-PT-19). Kịch bản diễn tập: `data/seed/onboarding-thu/dien-tap.sh`. Kiểm thử tự động: `go test ./internal/provision/...` trong `services-go/tenant-registry` với bộ giả lập `provisiontest`.

## Điều kiện kích hoạt

Hồ sơ tenant đã duyệt (tên, mã đơn vị, mô hình pool, bridge hoặc silo, cell đích, tên miền, IdP, tích hợp) và tenant ở `KHOI_TAO`.

## Tác động

Saga chạy các bước CSDL, khoá mã hoá, Organization và IdP, tên miền, chứng chỉ TLS, Policy Pack và UI Pack, lịch nghỉ, hạn mức, kiểm thử khói (bảng đầy đủ ở `cap-phat-kich-hoat-tenant.md` mục 1), thường 30–60 phút; phần còn lại của ngày dành cho kiểm tra nghiệp vụ với khách hàng. Tenant silo cần thêm cell theo OPS-23 trước. Trạng thái đi `KHOI_TAO → DANG_CAP_PHAT → DANG_CAU_HINH → SAN_SANG → DANG_VAN_HANH`.

## Kiểm tra nhanh

```bash
vtctl -e <env> tenant status <tenant-id>          # trạng thái, cell, job PROVISION gần nhất
```

Truy vấn chỉ đọc trên CSDL registry (`cap-phat-kich-hoat-tenant.md` mục 2): job `PROVISION` mới nhất, bước lỗi, nhật ký trạng thái.

## Các bước

1. Quản trị nền tảng chạy wizard tiếp nhận tenant (SCR-0102) tới bước 3 và bấm "Tiếp tục"; saga bắt đầu. IdP liên kết (bước 4 của wizard) do quản trị tenant khai trước khi cấp phát để saga tạo IdP cùng Organization, nên chuẩn bị sẵn tài khoản quản trị tenant (lệch ghi nhận khi diễn tập VH-04).
2. Theo dõi `vtctl tenant status` tới khi tenant sang `DANG_CAU_HINH` và có thư cho đầu mối (EVT-21).
3. **Nếu saga kẹt ở `DANG_CAP_PHAT` hoặc một bước lỗi:** làm theo `cap-phat-kich-hoat-tenant.md` mục 3 (kẹt), mục 4 (chứng chỉ TLS chưa cấp), mục 7 (CSDL và khoá). Saga lỗi đã tự bù trừ về `KHOI_TAO`; sửa nguyên nhân rồi chạy lại từ wizard, mọi bước đều idempotent.
4. Quản trị tenant nạp cây tổ chức (SCIM hoặc tệp nhập) và kiểm đối soát; đội triển khai chạy kiểm tra nghiệp vụ với khách hàng (có thể dùng bộ UAT `docs/uat/`).
5. Kích hoạt khi khách hàng nghiệm thu: `vtctl -e <env> tenant activate <tenant-id> --reason "<số biên bản nghiệm thu>"` (chạy thử, rồi thêm `--confirm`). Kích hoạt bị chặn thì xem `cap-phat-kich-hoat-tenant.md` mục 5.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Xem trạng thái | `vtctl tenant status <tenant-id>` | `GET /api/v1/registry/tenants/{id}` (`getRegistryTenant`) |
| Kích hoạt | `vtctl tenant activate <tenant-id> --reason ... --confirm` | `activateConfigTenant` |
| Tạm ngừng | `vtctl tenant suspend <tenant-id> --reason ... --confirm` | `suspendConfigTenant` |
| Chấm dứt (sau khi đã tạm ngừng và xuất trả dữ liệu) | `vtctl tenant terminate <tenant-id> --reason ... --handover-ref <số biên bản> --confirm` | `terminateConfigTenant` |

## Kiểm sau khi làm

Tenant ở `DANG_VAN_HANH`; người dùng thử của tenant đăng nhập qua tên miền tenant với chứng chỉ hợp lệ; Policy Pack và UI Pack mặc định của khối đã gắn; tổng thời gian từ khi duyệt hồ sơ tới khi kích hoạt không quá một ngày làm việc.

## Quay lui

Trước kích hoạt: saga lỗi tự bù trừ; huỷ hồ sơ theo `cap-phat-kich-hoat-tenant.md` mục 6. Sau kích hoạt: chỉ tạm ngừng (`TAM_NGUNG`, giữ dữ liệu); chấm dứt (`DA_CHAM_DUT`) đi theo quy trình xuất bàn giao và huỷ KEK, không phải quay lui. Chấm dứt chỉ chạy từ `TAM_NGUNG`, cần lý do và số biên bản bàn giao dữ liệu (`vtctl tenant terminate`); đây là trạng thái cuối, tenant-registry đóng khoảng gắn cell để cổng ngừng định tuyến và phát `registry.tenant.changed.v1`, còn huỷ KEK `kek-tenant-<id>` làm theo OPS-20 sau thời gian lưu giữ của hợp đồng. Hồ sơ còn ở `KHOI_TAO` thì huỷ bằng `cancelConfigTenant`.

## Leo thang

Saga lỗi lặp lại ở cùng bước hai lần sau khi đã sửa nguyên nhân: gọi cấp 2 nền tảng (CSDL, OpenBao, Keycloak tuỳ bước).

## Liên quan

`../cap-phat-kich-hoat-tenant.md`, [OPS-08](ops-08-chuyen-tenant-giua-cell.md), [OPS-23](ops-23-them-cell-moi.md), `services-go/vtctl/README.md`.
