# OPS-22 · Kiểm tra chuỗi băm hằng ngày

> **Truy vết:** TL-04 mục 11.9 (03:00, bản neo được hai bên xác nhận, `verify_run` 24 giờ và 1% bản ghi cũ, thất bại kích hoạt DR-14) · SVC-09 `services-go/ledger` · bảng `ledger.verify_run` · DL-12, DV-09-03, DV-09-04.
> **Chu kỳ:** ngày · **Chịu trách nhiệm:** SOC · **Tự động hoá:** CronJob `verify_run` của ledger-service.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Đọc kết quả kiểm tra hằng ngày; cảnh báo `LedgerChainBroken` (thất bại kiểm tra), `LedgerKiemTraNgungChay`, `LedgerNiemPhongNgungChay`, `LedgerTSALoi`, `LedgerDauThoiGianQuaHan`.

## Tác động

Sổ cái là bằng chứng không chối bỏ của nghiệp vụ đánh giá. Kiểm tra thất bại là sự cố DR-14 (SEV-1): ngừng niêm phong mới, bảo toàn chứng cứ.

## Kiểm tra nhanh

```sql
SELECT kind, tenant_id, status, started_at, finished_at, detail
FROM ledger.verify_run ORDER BY started_at DESC LIMIT 20;
```

Lịch thật của các loại kiểm tra (niêm phong mỗi giờ, đầu chuỗi mỗi 15 phút, toàn bộ Chủ nhật, lưu trữ đầu quý, neo 00:15) ở `../doi-soat-so-cai.md` mục 1.

## Các bước

1. **Kiểm tra đạt:** ghi nhận, không làm gì.
2. **Kiểm tra ngừng chạy, niêm phong ngừng, TSA lỗi, dấu thời gian quá hạn:** `../doi-soat-so-cai.md` mục 2.
3. **Kiểm tra phát hiện phá vỡ chuỗi (`LedgerChainBroken`):** chuyển ngay [dr-14](../canh-bao/dr-14.md) và `../doi-soat-so-cai.md` mục 3.
4. **Đối soát sổ cái với bảng nguồn (R2):** `../doi-soat-so-cai.md` mục 4.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Niêm phong đột xuất | (chưa có) | `ledger-service admin seal --kind DOT_XUAT --window-end <mốc>` |
| Kiểm tra độc lập ngoài cụm | (chưa có) | `ledger-verify` (`services-go/ledger/cmd/ledger-verify/README.md`) |

## Kiểm sau khi làm

Lượt kiểm tra kế tiếp `DAT`; cảnh báo tự đóng; với DR-14, biên bản sự cố có kết luận của SOC.

## Quay lui

Không áp dụng: kiểm tra chỉ đọc; sổ cái không bao giờ sửa.

## Leo thang

Mọi thất bại kiểm tra chuỗi: SEV-1, gọi SOC và IC ngay.

## Liên quan

`../doi-soat-so-cai.md`, [dr-14](../canh-bao/dr-14.md), [ledger-lag](../canh-bao/ledger-lag.md).
