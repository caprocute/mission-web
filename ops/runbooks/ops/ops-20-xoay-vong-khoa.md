# OPS-20 · Xoay vòng khoá

> **Truy vết:** TL-04 mục 11.9, 9.4 (SEC-11, SEC-12) · ADR-17, ADR-30 · `backend/libs/key-rotation` · CronJob xoay khoá Transit trong `deploy/charts/vt-platform/ha-tang/openbao` (khối `rotation`) · cert-manager.
> **Chu kỳ:** theo lịch mục 9.4 · **Chịu trách nhiệm:** quản trị khoá · **Tự động hoá:** OpenBao, cert-manager.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). `deploy/environments/kind/kiem-thu-bi-mat.sh` kiểm CronJob xoay khoá và chỉ số `vt_key_age_days` trên kind.

## Điều kiện kích hoạt

Lịch tự động (DEK đầu tháng, bọc lại DEK hằng ngày, xoay khoá Transit theo lịch của values OpenBao), hoặc cảnh báo khi một khoá quá hạn xoay vòng 7 ngày theo `vt_key_age_days`, hoặc nghi lộ khoá.

## Tác động

Xoay DEK và bọc lại không đụng dữ liệu nghiệp vụ. Mã hoá lại khi nghi lộ đụng dữ liệu, cần hai người duyệt. Chứng chỉ do cert-manager gia hạn tự động; chứng chỉ sắp hết hạn có cảnh báo `CertificateExpiringSoon`.

## Kiểm tra nhanh

```promql
max by (loai, muc_dich) (vt_key_age_days)
```

```bash
kubectl get cronjob -A | grep -E 'xoay|boc-lai'                 # CronJob xoay khoá và bọc lại
kubectl get certificate -A | grep -v True                        # chứng chỉ chưa sẵn sàng
```

## Các bước

1. **Khoá dữ liệu (DEK, KEK):** làm theo `../xoay-vong-khoa-du-lieu.md` (xoay KEK hằng năm, nghi lộ một DEK, cảnh báo).
2. **Khoá Transit và bí mật trong OpenBao:** `../openbao-van-hanh.md` mục "Thao tác định kỳ".
3. **PKI nội bộ, CA trung gian:** `../openbao-pki.md` mục "Gia hạn và thu hồi".
4. **Khoá quá hạn 7 ngày:** tìm CronJob tương ứng, đọc log lần chạy gần nhất, chạy lại tay bằng `kubectl create job --from=cronjob/<tên> <tên>-tay-<ngày> -n <namespace>`.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Chạy xoay khoá ngay | (chưa có) | `kubectl create job --from=cronjob/<tên> ...` |

## Kiểm sau khi làm

`vt_key_age_days` của mọi loại khoá dưới hạn xoay; sự kiện kiểm toán `KEY_ROTATION_*` có trong `sys.audit.v1`.

## Quay lui

Phiên bản khoá cũ vẫn giải mã được (`DECRYPT_ONLY`) cho tới khi bọc lại xong; không huỷ phiên bản cũ trước khi `boc-lai` báo hết bản bọc cũ.

## Leo thang

Nghi lộ KEK hoặc CA trung gian: SEV-1 an ninh, gọi SOC và quản trị khoá cấp 2.

## Liên quan

`../xoay-vong-khoa-du-lieu.md`, `../openbao-van-hanh.md`, [certs](../canh-bao/certs.md), [openbao](../canh-bao/openbao.md).
