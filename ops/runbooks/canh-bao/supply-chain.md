# Kyverno từ chối ảnh không ký

> **Truy vết:** TL-04 mục 10.5 · SEC-23, SEC-49 · Kyverno `verifyImages`, cosign · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `KyvernoImageRejected` (warning: chính sách `verifyImages` trả kết quả thất bại trong 10 phút).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Có pod dùng ảnh không có chữ ký hoặc chứng thực hợp lệ bị Kyverno chặn.

## Tác động

Pod mới không chạy; có thể là nhầm cấu hình hoặc dấu hiệu chuỗi cung ứng bị can thiệp. SEV-3; SEV-1 nếu nghi tấn công.

## Kiểm tra nhanh

```bash
kubectl get policyreport -A | grep -i fail
kubectl get events -A --field-selector reason=PolicyViolation | tail
```

## Các bước

1. Xác định ảnh và nơi khai báo nó (chart, digest trong `deploy/environments/<môi trường>/digests.yaml`).
2. **Nếu ảnh không đi qua pipeline** (thẻ thay vì digest, kho ngoài Harbor): sửa khai báo trong kho GitOps.
3. **Nếu ảnh có digest từ pipeline mà vẫn bị từ chối:** kiểm khoá công khai cosign trong chính sách và bước ký của pipeline; báo SOC.
4. Không tắt chính sách hoặc thêm ngoại lệ để cho chạy.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

Pod chạy với ảnh đã ký; không còn kết quả thất bại mới.

## Quay lui

Revert khai báo ảnh.

## Leo thang

Ảnh lạ không rõ nguồn: SEV-1, gọi SOC.

## Liên quan

[runtime-security](runtime-security.md), [OPS-26](../ops/ops-26-va-thu-vien-cve.md).
