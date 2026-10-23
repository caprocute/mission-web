# OPS-14 · Sổ tay vận hành

> **Truy vết:** TL-04 mục 11.1, 11.7 (mỗi cảnh báo của mục 10.5 có một sổ tay, cấu trúc thống nhất, thao tác thường xuyên tự động hoá bằng `vtctl`) · INF-15 (`runbook_url`) · INF-26 (`vtctl`) · VH-01, VH-02.
> **Chu kỳ:** liên tục · **Chịu trách nhiệm:** mọi đội · **Tự động hoá:** kiểm thử sổ tay.
> **Chạy thử:** `python3 -I ops/runbooks/kiem-lien-ket.py` chạy trong VH-02, xem mục "Kiểm sau khi làm".

## Điều kiện kích hoạt

Thêm hoặc đổi một luật cảnh báo có `runbook_url`; thêm quy trình OPS; sau sự cố mà sổ tay thiếu hoặc sai (hạng mục của phân tích sau sự cố).

## Tác động

Người trực cấp 1 chỉ làm theo sổ tay; sổ tay thiếu hoặc liên kết gãy làm chậm phản hồi và đẩy sự cố lên cấp 2 không cần thiết.

## Kiểm tra nhanh

```bash
python3 -I ops/runbooks/kiem-lien-ket.py              # mọi runbook_url trỏ tới tệp và mục có thật
python3 -I ops/runbooks/kiem-lien-ket.py --liet-ke    # bảng sổ tay → cảnh báo → tệp luật
```

## Các bước

1. Chép `ops/runbooks/_mau.md` thành tệp mới: quy trình OPS ở `ops/runbooks/ops/ops-<số>-<tên-ngắn>.md`; sổ tay theo cảnh báo ở `ops/runbooks/canh-bao/<tên>.md` với `<tên>` đúng phần cuối của `runbook_url` (`https://runbook.vt.local/okr/<tên>` hoặc `{{ $rb }}/<tên>`). Sổ tay chi tiết của một thành phần (ví dụ `saga-chot-ky.md`) đặt ở `ops/runbooks/`.
2. Điền đủ các mục theo thứ tự của mẫu; ghi dòng "Chạy thử" với ngày, người, môi trường, hoặc lý do chưa chạy.
3. Chỉ ghi lệnh có thật: lệnh `vtctl` theo `services-go/vtctl/README.md`; lệnh `kubectl`, `argocd`, `helm` với cờ đã dùng thử. Không ghi địa chỉ, tài khoản, mật khẩu, token.
4. Thêm dòng vào bảng của `ops/runbooks/README.md`; chạy `kiem-lien-ket.py`.
5. Khi `runbook_url` có `#<mục>`, tiêu đề tương ứng phải sinh đúng neo đó (bỏ dấu, chữ thường, khoảng trắng thành gạch nối), ví dụ "## Độ trễ nhận job" cho `#do-tre-nhan-job`.

## Lệnh vtctl và lệnh tương đương

Không có lệnh vận hành; công cụ kiểm là `ops/runbooks/kiem-lien-ket.py`.

## Kiểm sau khi làm

`kiem-lien-ket.py` in "0 liên kết gãy" và trả mã 0; markdownlint sạch (`npx --yes markdownlint-cli@0.45.0 --disable MD013 -- ops/runbooks`).

## Quay lui

Revert merge request sổ tay.

## Leo thang

Không áp dụng.

## Liên quan

[Danh mục sổ tay](../README.md), `_mau.md`, [OPS-13](ops-13-truc-van-hanh-muc-su-co.md).
