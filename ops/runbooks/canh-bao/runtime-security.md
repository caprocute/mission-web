# Falco mức Critical và sức khoẻ Falco

> **Truy vết:** TL-04 mục 10.5 · SEC an ninh lúc chạy · Falco, Falcosidekick trong `deploy/charts/security-cell` · luật `canh-bao-toi-thieu.yaml`, `deploy/charts/security-cell/rules/an-ninh-nen.yaml`.
> **Cảnh báo:** `FalcoCritical` (critical: có sự kiện Falco mức Critical); `FalcoNodeMissing` (warning: node chưa có Falco sẵn sàng); `FalcosidekickDauRaLoi` (critical: Falcosidekick không gửi được cảnh báo).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Falco phát hiện hành vi nguy hiểm trong container (shell, ghi tệp nhạy cảm, kết nối lạ), hoặc chính hệ thống phát hiện không đầy đủ.

## Tác động

Có thể là sự cố an ninh đang diễn ra. `FalcoCritical`: SEV-1 cho tới khi SOC loại trừ.

## Kiểm tra nhanh

```bash
kubectl logs -n <namespace-security> ds/falco --tail=200 | grep -i critical
kubectl get ds falco -n <namespace-security>
```

## Các bước

Làm theo `../an-ninh-falco.md` mục "Cảnh báo": xác định pod và quy tắc, cô lập theo chỉ đạo của SOC; với `FalcoNodeMissing` và `FalcosidekickDauRaLoi` khôi phục Falco hoặc đầu ra trước, vì trong thời gian đó cảnh báo an ninh có thể bị mất.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

SOC kết luận; Falco sẵn sàng trên mọi node; Falcosidekick gửi được.

## Quay lui

Thay đổi quy tắc Falco quay lui theo `../an-ninh-falco.md` mục "Quay lui".

## Leo thang

`FalcoCritical` chưa loại trừ được trong 15 phút: gọi SOC và IC.

## Liên quan

`../an-ninh-falco.md`, `../an-ninh-soc-quy-tac.md`, [supply-chain](supply-chain.md).
