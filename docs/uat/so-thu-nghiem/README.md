# UAT một kỳ đánh giá: tenant Sở Thử nghiệm

> Nguồn: task QA-16 (phần chuẩn bị); `ke-hoach.md`.

Thư mục này là bộ tài liệu chuẩn bị kiểm thử chấp nhận nghiệp vụ (UAT) cho một tenant thí điểm chạy trọn một kỳ đánh giá. Tenant hiện là tenant hư cấu "Sở Thử nghiệm" của dữ liệu thử dev; bộ tài liệu **chưa được chạy với người dùng thật**. Đọc `ke-hoach.md` trước, sau đó phát `kich-ban.md` và checklist của từng vai trò cho người đại diện.

| Tệp | Nội dung |
|---|---|
| `ke-hoach.md` | Phạm vi, vai trò, lịch D1 đến D10, điều kiện bắt đầu, tiêu chí chấp nhận TC-01 đến TC-08, báo lỗi theo SEV-1 đến SEV-4, rủi ro |
| `tham-so.yaml` | Mọi giá trị phụ thuộc tenant: tenant, cell, bộ quy định, kỳ, mốc, người đại diện AC-01 đến AC-10 |
| `kich-ban.md` | Mười tám kịch bản theo thứ tự của kỳ, kèm phụ lục ánh xạ sang hành trình kiểm thử tự động |
| `checklist-nghiem-thu.md` | Checklist theo vai trò AC-01 đến AC-10 và checklist cuối kỳ |
| `bien-ban-mau.md` | Mẫu biên bản dùng chung cho diễn tập và nghiệm thu |
| `du-lieu-mau/` | Dữ liệu hư cấu và kết quả xếp loại mong đợi tính tay |
| `kiem_ket_qua_mong_doi.py` | Đối chiếu kết quả tính tay với bộ tính tham chiếu của bộ quy định |

## Kiểm trước buổi UAT

```bash
python3 -I docs/uat/so-thu-nghiem/kiem_ket_qua_mong_doi.py      # phải in "Khớp 5/5 ca"
cd frontend && E2E_MODE=he-thong npm run e2e:he-thong            # trên môi trường đích của tenant
```

Hành trình tự động ghi ngược kịch bản UAT ở trường `kichBanUat` của `frontend/e2e/hanh-trinh/danh-muc.json`.

## Chạy lại với tenant thí điểm thật

1. Sao thư mục này thành `docs/uat/<mã tenant>/`, chỉ sửa `tham-so.yaml` (tenant, cell, kỳ, mốc, người đại diện). Không ghi mật khẩu, khoá OTP hay địa chỉ máy chủ vào tệp; chỉ ghi tên biến môi trường.
2. Thay `du-lieu-mau/` bằng kế hoạch và nhiệm vụ thật của tenant; cán bộ tổ chức của tenant lập bảng kết quả mong đợi theo đúng mẫu `ket-qua-mong-doi.yaml`, rồi chạy lại lệnh đối chiếu.
3. Nếu tenant dùng bộ quy định khác, sửa đường dẫn gói trong `tham-so.yaml` và trong lệnh đối chiếu.
4. Kịch bản, checklist và mẫu biên bản giữ nguyên; biên bản đã điền lưu ở `docs/uat/<mã tenant>/bien-ban.md`, bản ký số lưu ngoài repo.
