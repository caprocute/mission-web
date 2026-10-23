# Chứng thư sắp hết hạn

> **Truy vết:** TL-04 mục 10.5, 9.4 · cert-manager · PKI nội bộ OpenBao · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `CertificateExpiringSoon` (warning: chứng thư cert-manager hết hạn trong dưới 14 ngày).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

cert-manager không gia hạn được một chứng thư như lịch (thường gia hạn khi còn một phần ba thời hạn).

## Tác động

Hết hạn sẽ làm hỏng kết nối TLS hoặc mTLS của dịch vụ, hoặc tên miền tenant. SEV-3, SEV-2 khi dưới 3 ngày.

## Kiểm tra nhanh

```bash
kubectl describe certificate <name> -n <namespace>          # điều kiện Ready, lý do lỗi
kubectl get certificaterequest,order,challenge -n <namespace>
```

## Các bước

1. **Nếu issuer là PKI nội bộ OpenBao:** kiểm OpenBao không bị niêm phong ([openbao](openbao.md)), vai trò PKI còn hiệu lực; CA trung gian sắp hết hạn thì theo `../openbao-pki.md` mục "Gia hạn và thu hồi".
2. **Nếu là chứng chỉ tên miền tenant:** kiểm CNAME còn đúng (`../cap-phat-kich-hoat-tenant.md` mục 4).
3. Sau khi sửa nguyên nhân, xoá Secret cũ hoặc tạo lại CertificateRequest để cert-manager cấp lại.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

Certificate `Ready=True` với hạn mới; cảnh báo tự đóng.

## Quay lui

Không áp dụng.

## Leo thang

Dưới 3 ngày mà chưa cấp lại được: SEV-2, gọi cấp 2 an ninh.

## Liên quan

`../openbao-pki.md`, [OPS-20](../ops/ops-20-xoay-vong-khoa.md).
