# Biên bản diễn tập: đưa nhóm tenant thử vào sử dụng và tạm dừng một tenant

> Nguồn: task VH-08; `docs/van-hanh/ke-hoach-mo-rong.md` mục 4 đến 7 và 11.

| Thuộc tính | Giá trị |
|---|---|
| Ngày | 23/10/2026 |
| Người thực hiện | Luồng B2 (AI agent), chưa có người duyệt |
| Môi trường | Máy cloud của luồng: bản giả lập kế hoạch (`docs/van-hanh/dua_vao_su_dung.py`) và kiểm thử tích hợp của `tenant-registry` trên PostgreSQL chạy bằng Docker. **Chưa phải staging**: staging chưa có |
| Nhóm thử | N-THU: Huyện Thử nghiệm Một (pool, CELL-SH, 40 tài khoản), Tỉnh Thử nghiệm Hai (bridge, CELL-G1, 60 tài khoản); tenant và người hư cấu |
| Kết luận | Đạt trên bản giả lập; còn phải chạy lại trên staging |

## 1. Mục tiêu

Buổi diễn tập kiểm bốn điều. Thứ nhất, bảng nhóm và lịch các mốc thoả nguyên tắc của kế hoạch. Thứ hai, dữ liệu khởi tạo của từng tenant đối soát khớp với nguồn, và đối soát phát hiện được dữ liệu sai. Thứ ba, tenant đi đúng vòng đời `KHOI_TAO` → `DANG_CAP_PHAT` → `DANG_CAU_HINH` → `SAN_SANG` → `DANG_VAN_HANH`. Thứ tư, tắt cờ của một tenant và tạm ngừng một tenant khác không ảnh hưởng tenant còn lại, và tenant tạm ngừng mở lại được.

## 2. Phần chạy với mã thật

Các bước onboarding OPS-07 (cấp phát, kích hoạt, bảng chuyển trạng thái) đã được kiểm bằng kiểm thử tích hợp của dịch vụ `services-go/tenant-registry`, chạy trên PostgreSQL 17 bằng Testcontainers. Lệnh: `go test -count=1 ./internal/tenant/ ./internal/api/ ./internal/provision/`.

| Kiểm thử | Nội dung | Kết quả |
|---|---|---|
| `TestProvisionAndActivate` | Tạo tenant, chạy saga cấp phát, kiểm điều kiện kích hoạt, kích hoạt | Đạt |
| `TestPromoteReady` | Chuyển `DANG_CAU_HINH` → `SAN_SANG` khi đủ điều kiện | Đạt |
| `TestSagaEndToEnd`, `TestSagaCompensateFromLastStep` | Saga chạy trọn và bù trừ khi lỗi ở bước cuối | Đạt |
| `TestDataStepsPool`, `TestDataStepsBridge` | Bước CSDL cho tenant pool và bridge | Đạt |
| `TestTransitionTable` | Bảng chuyển trạng thái, gồm `DANG_VAN_HANH` ↔ `TAM_NGUNG` | Đạt |
| Toàn bộ ba gói | | `ok` cả ba gói (api 19,7 s; provision 57,9 s) |

## 3. Phần chạy trên bản giả lập

### 3.1. Kiểm bảng nhóm

Lệnh `dua_vao_su_dung.py kiem-tra` cho kết quả đạt, 0 lỗi, 1 cảnh báo. Cảnh báo là CELL-HCM sẽ vượt 80% sức chứa ở nhóm N5; kế hoạch đã ghi việc phải làm trước nhóm đó. Lệnh `tu-kiem` đạt 9/9 ca: đối soát phát hiện dòng mất, trường bị sửa và dòng trùng; bảng chuyển trạng thái chặn bước nhảy cóc; cửa sổ đóng băng và hạn sẵn sàng tính đúng.

### 3.2. Đưa nhóm vào sử dụng, tắt cờ, tạm ngừng

Lệnh: `dua_vao_su_dung.py dien-tap --nhom N-THU --tat-co huyen-thu-nghiem-mot --tam-ngung tinh-thu-nghiem-hai --ghi docs/van-hanh/theo-doi-ti-le.csv`. Nhật ký:

```text
[01] Bắt đầu diễn tập nhóm N-THU (Nhóm thử của buổi diễn tập (chỉ trên staging)), 2 tenant
[02] Tiêu chí vào: bảng nhóm đạt (0 lỗi)
[03] huyen-thu-nghiem-mot: nạp 6 đơn vị, 40 cán bộ; loại 0 dòng; đối soát khớp (băm nhân sự 52fa114445eb767f…)
[04] huyen-thu-nghiem-mot: DANG_VAN_HANH, cờ bật ['giao-dien-moi', 'mo-dang-nhap', 'nhac-viec-qua-thong-bao'], đăng nhập mở
[05] tinh-thu-nghiem-hai: nạp 7 đơn vị, 60 cán bộ; loại 0 dòng; đối soát khớp (băm nhân sự 3ae5f6da99ee0156…)
[06] tinh-thu-nghiem-hai: DANG_VAN_HANH, cờ bật ['giao-dien-moi', 'mo-dang-nhap', 'nhac-viec-qua-thong-bao'], đăng nhập mở
[07] Sự cố giả định SEV-3 ở huyen-thu-nghiem-mot: tắt cờ tính năng mới (mức D-1), còn ['mo-dang-nhap']; tenant khác không bị ảnh hưởng
[08] tinh-thu-nghiem-hai: TAM_NGUNG, đăng nhập chặn, ghi chặn; dữ liệu giữ nguyên
[09] Tenant còn lại của nhóm vẫn dùng bình thường
[10] Bảng chuyển trạng thái chặn bước TAM_NGUNG → SAN_SANG như thiết kế
[11] tinh-thu-nghiem-hai: mở lại, DANG_VAN_HANH, đăng nhập mở
[12] Đã ghi bảng theo dõi tỉ lệ sử dụng vào theo-doi-ti-le.csv
[13] Kết thúc diễn tập: đạt
```

### 3.3. Ca dữ liệu sai

Buổi diễn tập chạy lại với một bản sao dữ liệu của Tỉnh Thử nghiệm Hai, có thêm một dòng cán bộ thuộc đơn vị không tồn tại. Kết quả: bước nạp loại 1 dòng, đối soát báo "nhân sự: nguồn 61 dòng, đã nạp 60 dòng", tenant dừng ở `DANG_CAU_HINH` và chưa đạt tiêu chí vào V-03. Kết quả này đúng như kế hoạch mục 5, bước B-04.

## 4. Đối chiếu với tiêu chí của task

| Tiêu chí | Kết quả |
|---|---|
| Đưa nhóm thử hai tenant vào sử dụng với dữ liệu khởi tạo giả lập | Đạt trên bản giả lập |
| Dữ liệu khởi tạo đối soát khớp nguồn giả lập | Đạt: số dòng và băm khớp cho cả hai tenant |
| Tắt cờ và tạm dừng một tenant | Đạt trên bản giả lập; tenant khác không bị ảnh hưởng |
| Thực hiện trên staging | **Chưa làm**: chưa có staging |

## 5. Phát hiện và việc tiếp theo

1. **Chưa có API chuyển tenant sang tạm ngừng.** `tenant-registry` có trạng thái `TAM_NGUNG` trong bảng chuyển trạng thái. Chiều mở lại đã có: lệnh kích hoạt nhận tenant đang `TAM_NGUNG` và chuyển về `DANG_VAN_HANH` sau khi kiểm lại điều kiện. Nhưng chiều ngược lại, từ `DANG_VAN_HANH` sang `TAM_NGUNG`, chưa có đường API nào; hiện chỉ có tạo, sửa, huỷ khởi tạo, cấp phát, kích hoạt và chuyển cell. Vì vậy bước tạm ngừng của mức D-3 chưa làm được trên hệ thật. Đề nghị luồng phụ trách DV-03 bổ sung, kèm chặn đăng nhập ở Keycloak và chặn ghi ở APISIX.
2. **Cờ theo tenant có hai nơi.** TL-04 mục 11.2 đặt cờ OPS-04 trong flagd và kho GitOps theo tenant. Còn mission-core có bảng `config.feature_flag` (CORE-08-07) để bật theo đơn vị trong tenant. Kế hoạch dùng cách của TL-04 cho việc bật và tắt theo tenant. Khi nối hệ thật, cần chốt danh mục cờ thật thay cho ba tên mô tả trong công cụ.
3. **Diễn tập trên staging.** Khi có staging, chạy lại theo đúng các bước B-01..B-08 với hai tenant thử. Dùng thư mục `docs/van-hanh/dien-tap/` làm tệp nguồn nạp qua màn hình nhập Excel. Ghi thời gian thật của từng bước vào một biên bản mới.

## 6. Ký xác nhận

Chưa có. Đây là biên bản diễn tập trên bản giả lập, không có chữ ký của đại diện tenant.
