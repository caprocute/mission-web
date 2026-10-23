> Nguồn: task VH-04; TL-04 mục 11.4 "Vòng đời tenant", OPS-07; QT-01, SCR-0102; hợp đồng `contracts/openapi` (config.yaml phần `/config/tenants*`, platform.yaml phần `/registry/*`, jobs.yaml).

# Hồ sơ tenant thử và kịch bản diễn tập OPS-07

Thư mục này phục vụ diễn tập đưa tenant mới vào vận hành (VH-04). Dữ liệu ở đây hoàn toàn là dữ liệu thử: tên đơn vị, mã hợp đồng, người quản trị và tên miền đều do dự án đặt ra, không trỏ tới cơ quan hay người thật. Thư mục không chứa token, mật khẩu hay địa chỉ máy chủ; mọi giá trị đó được truyền qua biến môi trường lúc chạy.

## Thành phần

| Tệp | Nội dung |
|---|---|
| `tenant-pool.json` | Tenant thử mô hình pool (xã nhỏ, 150 tài khoản, cell dùng chung `CELL-SH`, khoá dùng chung của cell) |
| `tenant-bridge.json` | Tenant thử mô hình bridge (tỉnh cỡ vừa, 30.000 tài khoản, cell `CELL-G1`, CSDL riêng và KEK riêng) |
| `dien-tap.sh` | Kịch bản diễn tập gọi API tenant-registry, đo thời gian từng bước, ghi bảng kết quả để chép vào biên bản |
| `khung-gia-lap/dien_tap_khung_test.go.mau` | Khung giả lập chạy tenant-registry thật trên PostgreSQL thật (Docker) với hệ giả cho các hệ ngoài |

Mỗi hồ sơ có ba phần ứng với các bước của wizard SCR-0102. Phần `tao` là thân `createConfigTenant`. Phần `buoc3` là bản vá bước 3 do quản trị nền tảng nhập (mô hình, cell, Policy Pack, UI Pack). Phần `buoc4` là bản vá bước 4 do quản trị tenant nhập (IdP liên kết). Chuỗi `{code}` được kịch bản thay bằng mã đơn vị đang chạy, với dấu gạch dưới đổi thành gạch ngang để thành tên miền hợp lệ. Mã phiên bản Policy Pack và UI Pack trong hồ sơ là mã của dữ liệu mẫu; khi chạy trên staging, thay bằng mã phiên bản đã phát hành của khối tương ứng.

## Chạy trên môi trường có saga cấp phát (staging hoặc kind)

```bash
export REGISTRY_URL=<gốc API tenant-registry>
export TOKEN_QTNT=<token quản trị nền tảng, bậc xác thực 2>        # ví dụ sau vtctl -e staging login --step-up
export TOKEN_QTTENANT=<token quản trị tenant của đúng mã đơn vị>    # cho bước 4; đặt HAU_TO= để giữ mã gốc
data/seed/onboarding-thu/dien-tap.sh data/seed/onboarding-thu/tenant-pool.json
data/seed/onboarding-thu/dien-tap.sh data/seed/onboarding-thu/tenant-bridge.json --huy-thu
```

Kịch bản chạy các bước: kiểm cell đích, tạo hồ sơ, bước 3, bước 4, cấp phát và chờ job, kiểm kết nối IdP, kích hoạt rồi gửi lại cùng khoá để kiểm tính idempotent, tạm ngừng, kích hoạt lại, đọc nhật ký trạng thái. Khi cấp phát trả 503, nghĩa là môi trường chưa cấu hình saga (thiếu biến `PROVISION_*`, như dev compose hiện nay), kịch bản ghi bước đó là "CHƯA CHẠY" và dừng. Giữa bước cấp phát và kích hoạt, người vận hành nạp cây tổ chức theo OPS-07 bước 4; kịch bản không làm thay việc này trên môi trường thật.

Kết quả ghi vào `$OUT_DIR` (mặc định `$HOME/dien-tap-ops-07/<ngày>`): tệp `ket-qua-<mã>.md` là bảng các bước với thời gian, tệp `nhat-ky-<mã>.jsonl` là phản hồi của từng lời gọi (không chứa token). Thư mục kết quả nằm ngoài repo vì nhật ký có mã tenant và địa chỉ thư thử.

## Chạy cục bộ với khung giả lập (cần Docker và Go)

Khung giả lập dựng `api.Server` thật của tenant-registry trên PostgreSQL 17 chạy bằng Testcontainers, nối saga cấp phát thật với bộ hệ giả `provisiontest` (PowerDNS, cert-manager, mission-core, org-service, Prometheus, Keycloak trong bộ nhớ). Khung thêm vài đường `/__khung/*` để cấp token thử, giả lập kết quả kiểm IdP và cây tổ chức, và bật lỗi cố ý ở bước Organization.

```bash
cp data/seed/onboarding-thu/khung-gia-lap/dien_tap_khung_test.go.mau \
   services-go/tenant-registry/internal/api/zz_dien_tap_khung_test.go
(cd services-go/tenant-registry && DIEN_TAP_KHUNG_ADDR=127.0.0.1:35910 \
   go test ./internal/api/ -run '^TestDienTapKhung$' -v -count=1 -timeout 40m) &
# chờ dòng "khung diễn tập sẵn sàng", rồi:
export KHUNG_URL=http://127.0.0.1:35910 REGISTRY_URL=http://127.0.0.1:35910
data/seed/onboarding-thu/dien-tap.sh data/seed/onboarding-thu/tenant-pool.json --huy-thu
data/seed/onboarding-thu/dien-tap.sh data/seed/onboarding-thu/tenant-bridge.json --loi-buoc-4
curl -X POST $KHUNG_URL/__khung/dung
rm services-go/tenant-registry/internal/api/zz_dien_tap_khung_test.go
```

Thời gian đo trên khung giả lập chỉ phản ánh logic của tenant-registry và saga, không phản ánh thời gian thật của Keycloak, cấp chứng chỉ TLS, tạo CSDL hay migration. Số liệu dùng để chứng minh NFR-07 phải lấy từ lần chạy trên staging.

## Liên quan

- `ops/dien-tap/2026-10-23-ops-07.md` (biên bản diễn tập)
- `ops/runbooks/ops/ops-07-dua-tenant-vao-van-hanh.md`, `ops/runbooks/cap-phat-kich-hoat-tenant.md`
- `services-go/vtctl/README.md` (lệnh `tenant status|activate|suspend`)
