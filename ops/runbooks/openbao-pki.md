# Sổ tay PKI nội bộ: CA gốc ngoại tuyến và CA trung gian của cell

> Nguồn: TL-04 mục 9.4 "PKI nội bộ (SEC-59)", SEC-20 (thuật toán được phép); INF-13. CA gốc và nghi thức ký nằm
> ngoài phạm vi tự động hoá: sổ tay này là quy trình, không có mã chạy tự động.

Chuỗi tin cậy nội bộ có ba tầng. CA gốc sinh một lần trong HSM ngoại tuyến (không nối mạng), ECDSA P-384, hạn 20 năm, chỉ dùng để ký CA trung gian. Mỗi cell có hai CA trung gian ký từ CA gốc: `vt-noi-bo-goc` (khoá nằm trong Secret `cert-manager/vt-noi-bo-goc-ca`, chỉ cấp chứng thư TLS cho chính OpenBao, vì OpenBao không thể tự cấp chứng thư cho mình trước khi chạy) và `pki_int_cell_<cell>` (khoá sinh và nằm trong OpenBao, cấp chứng thư cho mọi dịch vụ nội bộ qua ClusterIssuer `openbao-pki-cell`). Ứng dụng, PostgreSQL, Kafka, Valkey, OpenSearch, ClickHouse tin CA gốc, nên đổi CA trung gian không phải phát lại gói tin cậy.

## Nghi thức tạo CA gốc (một lần cho toàn hệ thống)

Thành phần bắt buộc: hai người của đội quản trị khoá, một người của khách hàng, biên bản có chữ ký. Trên máy ngoại tuyến có HSM: khởi tạo khe, sinh khoá ECDSA P-384 không trích xuất được, tự ký chứng thư CA gốc (basicConstraints CA:TRUE, pathlen:1, keyUsage keyCertSign, cRLSign), ghi dấu vân tay SHA-256 vào biên bản. Chứng thư công khai được đưa vào Git ở các tệp gói tin cậy; khoá không bao giờ rời HSM. Bản sao lưu miền bảo mật HSM theo cơ chế 3 trong 5 thẻ như TL-04 mục 8.7.

## Ký CA trung gian của cell

1. Chạy `CELL=<mã> deploy/openbao/cau-hinh.sh`; script sinh CSR tại `PKI_CSR_OUT` (mặc định `pki-<cell>.csr`) với khoá EC P-384 nằm trong OpenBao.
2. Mang CSR (tệp công khai) sang máy ngoại tuyến, kiểm tra CN là `VT Cell <cell> Intermediate CA`, ký với pathlen:0, hạn 5 năm (43800 giờ), ghi biên bản.
3. Ghép chứng thư trung gian và chứng thư gốc thành một tệp PEM, chạy lại `CELL=<mã> PKI_SIGNED_CERT=<tệp> deploy/openbao/cau-hinh.sh`.
4. Kiểm tra: tạo một `Certificate` thử với `issuerRef: { kind: ClusterIssuer, name: openbao-pki-cell }`, chứng thư phải Ready và có issuer là CA trung gian của cell.

CA khởi đầu `vt-noi-bo-goc` làm tương tự bằng CSR sinh trên máy quản trị (khoá EC P-256), sau khi ký thì nạp cặp khoá vào Secret `vt-noi-bo-goc-ca` (kiểu `kubernetes.io/tls`, thêm `ca.crt`) bằng kênh quản trị qua PAM, không qua Git.

## Gia hạn và thu hồi

Chứng thư dịch vụ hạn 90 ngày, cert-manager gia hạn ở ngày 60 (SEC-12); cảnh báo CertificateExpiringSoon khi còn dưới 14 ngày mà chưa gia hạn được (thường do vai trò `cert-manager` trong OpenBao hoặc NetworkPolicy). CA trung gian của cell gia hạn trước khi hết hạn một năm bằng cách lặp lại mục trên với `bao write <pki>/intermediate/generate/internal` mới rồi `set-signed`; chứng thư cũ còn hiệu lực tới khi hết hạn nên không gián đoạn. Lộ khoá CA trung gian: thu hồi trên CA gốc, ký CA trung gian mới, buộc cert-manager cấp lại mọi chứng thư (`cmctl renew --all -A`).
