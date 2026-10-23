# OPS-25 · Rà soát năng lực

> **Truy vết:** TL-04 mục 11.9 (so số liệu thật trên Thanos độ phân giải 1 giờ với mô hình định cỡ, lệch quá 15% thì cập nhật) · TL-05 NG-01 đến NG-14 · INF-03 `deploy/capacity/` (`tinh_dinh_co.py`, `kiem_ngan_sach_ket_noi.py`, `assumptions.yaml`) · đầu vào của QA-12 và OPS-23.
> **Chu kỳ:** quý · **Chịu trách nhiệm:** SRE, kiến trúc · **Tự động hoá:** báo cáo Thanos.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). Phần tính mô hình chạy được ngay: `deploy/capacity/chay-kiem-thu.sh`.

## Điều kiện kích hoạt

Đầu mỗi quý, sau kỳ đỉnh của quý trước; hoặc khi một cảnh báo "kế hoạch năng lực" NG bật.

## Tác động

Không ảnh hưởng người dùng. Kết quả quyết định thay đổi `maxReplicas`, ngân sách kết nối, giới hạn node pool, mua sắm phần cứng và tách cell.

## Kiểm tra nhanh

```bash
python3 deploy/capacity/tinh_dinh_co.py --ho-so M --bao-cao /tmp/dinh-co-M.md
python3 deploy/capacity/kiem_ngan_sach_ket_noi.py
```

Số liệu thật trên Thanos, độ phân giải 1 giờ, cho kỳ đỉnh: RPS đỉnh theo cell, RPS mỗi pod, CPU primary CSDL, dung lượng lưu trữ, tăng trưởng minh chứng, trạng thái cảnh báo NG-01 đến NG-14.

## Các bước

1. Xuất số liệu thật của kỳ đỉnh cho từng cell.
2. Cập nhật giả định đo được (GD-xx) trong `deploy/capacity/assumptions.yaml` bằng merge request, chạy lại `tinh_dinh_co.py` cho các hồ sơ đang dùng.
3. Lệch quá 15% giữa số thật và mô hình: cập nhật `maxReplicas` trong `deploy/charts/vt-platform/sizing/`, ngân sách kết nối (kiểm bằng `kiem_ngan_sach_ket_noi.py`), kế hoạch phần cứng.
4. Tổng hợp trạng thái mọi ngưỡng NG ở mọi cell thành báo cáo năng lực quý; ngưỡng chạm hai kỳ đỉnh liên tiếp đi theo quy trình nâng bậc ([nang-bac](../canh-bao/nang-bac.md)).
5. Dự báo chạm 80% ngưỡng tách cell trong hai quý: mở OPS-23.

## Lệnh vtctl và lệnh tương đương

Không có lệnh `vtctl`; công cụ là `deploy/capacity/*.py` và truy vấn Thanos.

## Kiểm sau khi làm

Báo cáo năng lực quý có đủ cell và ngưỡng, merge request cập nhật values (nếu có) đã được CAB duyệt.

## Quay lui

Revert merge request values.

## Leo thang

Một ngưỡng cần phần cứng mới mà thời gian mua sắm dài hơn thời gian dự báo chạm ngưỡng: báo kiến trúc và chủ sản phẩm.

## Liên quan

[nang-bac](../canh-bao/nang-bac.md), [capacity](../canh-bao/capacity.md), [OPS-23](ops-23-them-cell-moi.md), `deploy/capacity/`.
