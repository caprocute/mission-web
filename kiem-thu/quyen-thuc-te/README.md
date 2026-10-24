# Kết quả đo quyền thực tế trên server dev (24/10/2026)

Sinh bằng `infra/dev/scripts/kiem-quyen-thuc-te.py` (commit aa7873ca), chỉ đọc: đăng nhập từng tài khoản thử rồi gọi
GET mọi tuyến không tham số của hợp đồng qua APISIX, đối chiếu với ma trận phân quyền `docs/phan-quyen/ma-tran.json`.

| Tệp | Lượt | Ghi chú |
|---|---|---|
| `thuong.json` | đăng nhập thường (acr aal1) | chỉ 14/64 tài khoản đo được; 50 tài khoản còn lại phần lớn lời gọi bị APISIX trả 429 |
| `aal2.json` | đăng nhập bước lên (acr aal2) | 0/64 tài khoản đo được: hầu hết lời gọi bị 429, kết quả "không lệch" không có giá trị |

Cần chạy lại sau khi nới giới hạn tần suất của APISIX trên dev hoặc giãn nhịp gọi. Tệp không chứa token, mật khẩu
hay địa chỉ máy chủ.

## Lượt đo 2 (24/10/2026, main 2a6aaebc)

| Tệp | Nội dung |
|---|---|
| `thuong-2.json` | đăng nhập thường, script đã giãn nhịp 8 yêu cầu/giây và thử lại khi gặp 429; đo đủ 64/64 tài khoản, 0 lời gọi 429 |
| `thuong-2-stdout.txt` | đầu ra nguyên văn của lượt đo |

Lệch mức màn hình chỉ còn `/org/sync-runs` (4 tài khoản có vai trò kiểm tra); các mục lệch còn lại ở mức bảng API theo module.
