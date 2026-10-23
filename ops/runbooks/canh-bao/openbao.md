# OpenBao bị niêm phong

> **Truy vết:** TL-04 mục 10.5 · INF-13 OpenBao của cell, HSM · luật `canh-bao-toi-thieu.yaml`.
> **Cảnh báo:** `OpenBaoSealed` (critical: `vault_core_unsealed == 0` trên một node).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Một node OpenBao ở trạng thái niêm phong, không phục vụ được.

## Tác động

Nếu mất quorum: dịch vụ không lấy được bí mật, không giải mã được DEK (Transit), cert-manager không cấp được chứng thư. Một node: SEV-3; cả cụm: SEV-1.

## Kiểm tra nhanh

```bash
kubectl get pods -n <namespace-openbao>
kubectl exec -n <namespace-openbao> <pod> -- bao status
```

## Các bước

Làm theo `../openbao-van-hanh.md` mục "Cảnh báo" (tự mở niêm phong bằng HSM, `../openbao-hsm.md`); không dùng khoá mở niêm phong thủ công khi chưa có quy trình nhiều người theo sổ tay đó.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

`bao status` báo `Sealed false` trên mọi node, cụm có leader; cảnh báo tự đóng.

## Quay lui

Không áp dụng.

## Leo thang

Cả cụm niêm phong: SEV-1, gọi cấp 2 an ninh và quản trị khoá.

## Liên quan

`../openbao-van-hanh.md`, `../openbao-hsm.md`, [certs](certs.md).
