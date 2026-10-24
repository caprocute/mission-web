# Biên bản diễn tập lần 2: đưa nhóm tenant thử vào sử dụng và tạm ngừng một tenant

> Nguồn: task VH-08; `docs/van-hanh/ke-hoach-mo-rong.md` mục 4 đến 7 và 11; biên bản lần 1 `ops/dien-tap/2026-10-23-mo-rong-nhom.md` mục 5; TL-04 mục 11.4 (OPS-07, trạng thái `TAM_NGUNG`).

| Thuộc tính | Giá trị |
|---|---|
| Ngày | 24/10/2026 |
| Người thực hiện | Luồng B2 (AI agent), chưa có người duyệt |
| Môi trường | Máy cloud của luồng. Bản giả lập kế hoạch (`docs/van-hanh/dua_vao_su_dung.py`); kiểm thử tích hợp của `tenant-registry` trên PostgreSQL chạy bằng Testcontainers; kiểm thử của `vtctl` và của bộ lọc định tuyến tenant trong `vt-platform-starter`. **Chưa phải staging**: staging vẫn chưa có |
| Nhóm thử | N-THU: Huyện Thử nghiệm Một (pool, CELL-SH, 40 tài khoản), Tỉnh Thử nghiệm Hai (bridge, CELL-G1, 60 tài khoản); tenant và người hư cấu |
| Kết luận | Bản giả lập đạt lần thứ hai. Mã thật đã chuyển được tenant sang `TAM_NGUNG`, và các dịch vụ Java từ chối yêu cầu của tenant đó. Mã thật chưa chặn đăng nhập ở Keycloak. Vẫn phải chạy lại trên staging |

## 1. Mục tiêu

Lần 1 (23/10/2026) đạt trên bản giả lập. Biên bản lần 1 để lại một việc: kiểm rằng sau lệnh tạm ngừng, người dùng của tenant không đăng nhập được, lệnh ghi bị từ chối, còn dữ liệu được giữ nguyên. Lần 2 có hai mục tiêu. Thứ nhất, chạy lại toàn bộ bản giả lập trên mã hiện tại của nhánh chính, để bảo đảm kế hoạch vẫn khớp sau các thay đổi từ hôm trước. Thứ hai, lần theo chuỗi tạm ngừng tenant bằng mã thật, từ lệnh vận hành tới chỗ yêu cầu của người dùng bị chặn, và ghi rõ mắt xích nào đã có, mắt xích nào còn thiếu. Kế hoạch không có bước nào dùng ứng dụng di động, nên buổi diễn tập cũng không có.

## 2. Bản giả lập

| Lệnh | Kết quả |
|---|---|
| `python3 docs/van-hanh/dua_vao_su_dung.py kiem-tra` | Đạt, 0 lỗi, 1 cảnh báo. Cảnh báo vẫn là CELL-HCM vượt 80% sức chứa ở nhóm N5, giống lần 1 |
| `python3 docs/van-hanh/dua_vao_su_dung.py tu-kiem` | Đạt 9/9 ca |
| `python3 docs/van-hanh/dua_vao_su_dung.py dien-tap --nhom N-THU --tat-co huyen-thu-nghiem-mot --tam-ngung tinh-thu-nghiem-hai` | Đạt, nhật ký dưới đây |

```text
[03] huyen-thu-nghiem-mot: nạp 6 đơn vị, 40 cán bộ; loại 0 dòng; đối soát khớp (băm nhân sự 52fa114445eb767f…)
[05] tinh-thu-nghiem-hai: nạp 7 đơn vị, 60 cán bộ; loại 0 dòng; đối soát khớp (băm nhân sự 3ae5f6da99ee0156…)
[07] Sự cố giả định SEV-3 ở huyen-thu-nghiem-mot: tắt cờ tính năng mới (mức D-1), còn ['mo-dang-nhap']; tenant khác không bị ảnh hưởng
[08] tinh-thu-nghiem-hai: TAM_NGUNG, đăng nhập chặn, ghi chặn; dữ liệu giữ nguyên
[10] Bảng chuyển trạng thái chặn bước TAM_NGUNG → SAN_SANG như thiết kế
[11] tinh-thu-nghiem-hai: mở lại, DANG_VAN_HANH, đăng nhập mở
     Vòng đời tinh-thu-nghiem-hai: KHOI_TAO → DANG_CAP_PHAT → DANG_CAU_HINH → SAN_SANG → DANG_VAN_HANH → TAM_NGUNG → DANG_VAN_HANH
[12] Kết thúc diễn tập: đạt
```

Băm nhân sự của hai tenant trùng với lần 1. Điều này cho thấy dữ liệu khởi tạo giả lập không đổi, và đối soát cho kết quả lặp lại được. Lần này không ghi đè bảng `theo-doi-ti-le.csv`, vì số liệu của lần 1 đã đủ làm mẫu.

## 3. Chuỗi tạm ngừng tenant bằng mã thật

TL-04 mục 11.4 yêu cầu trạng thái `TAM_NGUNG` chặn đăng nhập ở Keycloak và chặn ghi ở APISIX, đồng thời giữ nguyên dữ liệu. Buổi diễn tập lần theo từng mắt xích của chuỗi này.

| Mắt xích | Mã | Kiểm thử đã chạy | Kết quả |
|---|---|---|---|
| Lệnh vận hành `vtctl tenant suspend` gửi đúng yêu cầu: lý do bắt buộc, `If-Match`, xác thực nhiều lớp (`aal2`), ghi nhật ký kiểm toán; báo lỗi rõ khi máy chủ từ chối | `services-go/vtctl` | `go test -run TestTamNgungTenant ./internal/cli/` | Đạt |
| API `suspendConfigTenant` chuyển `DANG_VAN_HANH` → `TAM_NGUNG`. API từ chối khi thiếu lý do (422), khi chưa xác thực nhiều lớp (401 `COMMON-401-STEP-UP`), khi gọi bằng quyền quản trị tenant (403) và khi lệch phiên bản (409) | `services-go/tenant-registry` | `go test -run TestProvisionAndActivate ./internal/api/` (PostgreSQL 17, Testcontainers) | Đạt |
| Danh bạ trả trạng thái `TAM_NGUNG` cho các dịch vụ hỏi định tuyến tenant | `tenant-registry` | `go test -run TestResolveSuspendedTenantReportsStatus ./internal/api/` | Đạt |
| Dịch vụ Java từ chối yêu cầu của tenant không phục vụ được: trả 503 chuẩn RFC 9457, kèm `Retry-After` | `backend/libs/vt-platform-starter`, `TenantCellRoutingFilter` | `mvn test -Dtest=TenantCellRoutingFilterTest` (3/3 đạt) | Đạt một phần, xem phát hiện 2 |
| Keycloak chặn đăng nhập của tenant `TAM_NGUNG` | Không tìm thấy mã | Không có kiểm thử | **Chưa có**, xem phát hiện 1 |
| Dữ liệu tenant được giữ nguyên khi tạm ngừng | `tenant-registry`, hàm `Suspend` chỉ đổi trạng thái và ghi nhật ký kèm lý do | Trong `TestProvisionAndActivate` | Đạt |

## 4. Phát hiện và việc tiếp theo

1. **Chưa có mã chặn đăng nhập ở Keycloak khi tenant bị tạm ngừng.** Khi tạm ngừng, `tenant-registry` phát sự kiện thay đổi trạng thái tenant qua outbox. Tuy nhiên, trong repo không có bên nhận nào tắt Organization hoặc chặn đăng nhập của tenant trong Keycloak. Hệ quả là sau lệnh tạm ngừng, người dùng vẫn đăng nhập được, rồi mới bị dịch vụ từ chối khi gọi API. Như vậy dữ liệu vẫn được bảo vệ, nhưng trải nghiệm người dùng và việc kiểm soát truy cập chưa đúng TL-04. Việc này thuộc phần định danh và tiếp nhận tenant (saga Keycloak Organization của DV-03-04), không thuộc phạm vi tệp của VH-08. Biên bản đề nghị luồng điều phối giao bổ sung. Trước khi có mã, mức quay lui D-3 làm thêm một bước tay: quản trị nền tảng tắt Organization của tenant trên trang quản trị Keycloak, và bật lại khi mở tenant.
2. **Chặn ở dịch vụ rộng hơn thiết kế, và kiểm thử chưa phủ đúng trạng thái `TAM_NGUNG`.** TL-04 chỉ yêu cầu chặn ghi ở APISIX. Mã hiện tại không chặn ở APISIX. Thay vào đó, bộ lọc định tuyến ở mỗi dịch vụ Java trả 503 cho mọi yêu cầu của tenant ở `DANG_CHUYEN_CELL`, `TAM_NGUNG` hoặc `DA_CHAM_DUT`, kể cả yêu cầu đọc (`TenantCellRoute.isUnavailableStatus`). Cách này an toàn hơn, vì không lệnh ghi nào lọt qua được. Tuy nhiên, người dùng cũng không xem được dữ liệu của mình trong lúc tenant bị tạm ngừng. Ba ca kiểm thử của bộ lọc chỉ kiểm trạng thái `DANG_CHUYEN_CELL`; trạng thái `TAM_NGUNG` dùng chung nhánh mã nhưng chưa có ca riêng. Kế hoạch mục 7 giữ đúng mô tả của TL-04. Khi diễn tập trên staging, cần ghi lại hành vi thật, rồi để chủ sản phẩm chốt có cho đọc trong lúc tạm ngừng hay không.
3. **Diễn tập trên staging.** Phần này vẫn như biên bản lần 1, mục 5, việc 3. Khi có staging, chạy các bước B-01..B-08 với hai tenant thử, dùng `docs/van-hanh/dien-tap/` làm tệp nguồn, rồi kiểm ba điều sau lệnh tạm ngừng: không đăng nhập được, lệnh ghi bị từ chối, dữ liệu còn nguyên sau khi mở lại.

## 5. Đối chiếu với tiêu chí của task

| Tiêu chí | Kết quả |
|---|---|
| Kế hoạch có đủ nhóm, tiêu chí vào, ra, quay lui; đối chiếu TL-01, TL-04 không có mâu thuẫn | Đạt. Bảng đối chiếu ở `docs/dua-vao-su-dung/README.md` |
| Diễn tập đưa nhóm thử vào sử dụng và tạm dừng một tenant | Đạt trên bản giả lập (hai lần). Phần tạm ngừng đã chạy với mã thật tới mức dịch vụ; chưa chạy trên staging |
| Dữ liệu khởi tạo của nhóm thử đối soát khớp nguồn giả lập | Đạt; băm lặp lại giữa hai lần |
| Bảng nhóm có mẫu điền sẵn bằng tenant giả lập, tách khỏi quy trình | Đạt: `docs/van-hanh/nhom-tenant.yaml` |

## 6. Ký xác nhận

Chưa có. Đây là biên bản diễn tập của AI agent, không có chữ ký của đại diện tenant hay của người phụ trách vận hành.
