# Đối soát dữ liệu của mission-core

> **Truy vết:** DL-12 · TL-03 "Đối soát và kiểm tra nhất quán" · luật `deploy/charts/mission-core/templates/reconcile-alerts.yaml` (nhãn `reconcile`).
> **Cảnh báo:** `MissionDoiSoatLechChuaXuLy` (một job đối soát kết thúc `LOI` trong 2 giờ qua); `MissionDoiSoatDaTuSua` (job đã tự phát lại dòng lệch trong 2 giờ qua). Các cảnh báo đối soát riêng (`MissionDoiSoatR3ChanKySo`, `MissionDoiSoatR2SoCaiLechNguon`…) trỏ thẳng tới sổ tay của job.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Một job đối soát của mission-core phát hiện dữ liệu lệch giữa hai nơi lưu (nguồn và bản sao, nguồn và sổ cái).

## Tác động

Tuỳ job: lệch chưa tự xử lý (`LOI`) cần người xử lý, SEV-3; tự sửa (`DaTuSua`) là thông tin, kiểm nguyên nhân gốc trong giờ làm việc.

## Kiểm tra nhanh

Đọc nhãn `reconcile` của cảnh báo để chọn sổ tay:

| `reconcile` | Sổ tay |
|---|---|
| `R2_SO_CAI_NGUON` | `../doi-soat-so-cai.md` mục 4 |
| `R3_DIEM_ANH_CHUP` | `../doi-soat-diem-anh-chup.md` |
| `R5_KHO_PHAN_TICH` | `../doi-soat-kho-phan-tich.md` |
| `R7_OUTBOX_KAFKA` | `../doi-soat-outbox-kafka.md` |

Đối soát bản sao tổ chức (R1) của workflow-service: `../doi-soat-ban-sao-to-chuc.md`; minh chứng và Ceph: `../doi-soat-minh-chung-ceph.md`; tái lập điểm (R4): `../doi-soat-tai-lap-diem.md`.

## Các bước

1. Mở sổ tay của job theo bảng trên, làm mục "Xử lý khi có `LOI`".
2. **Nếu `DaTuSua` lặp lại mỗi ngày:** nguyên nhân gốc (sự kiện bị mất, consumer không idempotent) chưa được sửa; mở phiếu cho chủ service.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Phát lại outbox | `vtctl -c <cell> outbox replay --schema <schema> --tenant <id> --from <t> --to <t> --confirm` | `../phat-lai-outbox.md` |
| Phát lại bản sao tổ chức | `vtctl -c <cell> org republish --tenant <id> --confirm` | `../org-republish.md` |

## Kiểm sau khi làm

Lượt đối soát kế tiếp `KHOP`; cảnh báo tự đóng.

## Quay lui

Đối soát chỉ đọc; phát lại idempotent.

## Leo thang

Lệch lặp lại ở nhiều tenant hoặc chặn ký số trong tuần khoá kỳ: SEV-2, gọi chủ service.

## Liên quan

[OPS-21](../ops/ops-21-tinh-lai-doi-chieu-diem.md), [outbox](outbox.md).
