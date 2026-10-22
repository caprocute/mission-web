# Sổ tay HSM cho OpenBao: khoá mở, PIN và đổi HSM

> Nguồn: TL-04 mục 9.4, mục 8.7 (khoá trong HSM, miền bảo mật, DR-15); INF-13.

OpenBao tự mở khoá (auto-unseal) bằng một khoá trong HSM qua PKCS#11. Từ OpenBao 2.7 seal PKCS#11 là plugin KMS ngoài (`openbao-plugin-kms-pkcs11`), chạy trong ảnh `openbao-pkcs11` dựng từ `deploy/openbao/image/Containerfile` cùng thư viện PKCS#11 của nhà sản xuất HSM. Mọi tham số HSM nằm ở `seal.pkcs11` trong values theo môi trường; đổi HSM chỉ đổi values và ảnh, không đổi chart.

## Chuẩn bị khoá mở

Trên HSM (do quản trị khoá thao tác, có biên bản): tạo khe hoặc phân vùng riêng cho OpenBao của cell, đặt nhãn token khớp `seal.pkcs11.tokenLabel`; sinh khoá AES-256 không trích xuất được, nhãn khớp `seal.pkcs11.keyLabel`, quyền encrypt và decrypt. Với HSM chỉ hỗ trợ RSA cho bọc khoá, sinh RSA 3072 và đặt `mechanism: rsa-pkcs-oaep`, `rsaOaepHash: sha256`. Trên kind dùng SoftHSM2 với RSA-OAEP SHA-1 vì SoftHSM 2.6 lỗi với AES-GCM khi AAD rỗng và chỉ hỗ trợ OAEP SHA-1; cấu hình đó không dùng ngoài kind.

## PIN

PIN của người dùng khe HSM nằm trong Secret `secure-data/openbao-hsm-pin` (khoá `pin`), tạo bằng kênh quản trị qua PAM, không bao giờ vào Git, values hay nhật ký. Pod đọc PIN qua tệp (`pin = "file:///openbao/hsm/pin"`). Đổi PIN: đổi trên HSM, cập nhật Secret, xoá lần lượt từng pod OpenBao.

## Tổng kiểm plugin

`seal.plugin.sha256` là sha256 của tệp `/usr/local/libexec/openbao/openbao-plugin-kms-pkcs11` trong ảnh. Jenkins tính khi dựng ảnh và ghi cạnh digest ảnh; OpenBao từ chối chạy plugin sai tổng kiểm, nên ảnh bị tráo plugin sẽ không mở khoá được.

## Đổi sang HSM khác

Khi mất HSM Hoà Lạc (DR-15), OpenBao dùng HSM phía Nam trong cùng miền bảo mật: đổi `seal.pkcs11.lib` hoặc `slot`, `tokenLabel` theo cấu hình nhiều khe của nhà sản xuất, xoá từng pod. Chuyển hẳn sang một khoá mở mới (ví dụ thay HSM khác hãng) phải làm di trú seal: thêm khối seal mới với `disabled = false` và đánh dấu khối cũ `disabled = true`, khởi động lại theo hướng dẫn di trú seal của OpenBao, rồi xoá khối cũ.
