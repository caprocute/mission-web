# OPS-17 · Danh mục chuẩn bị trước mốc hạn

> **Truy vết:** TL-04 mục 11.8 (danh mục tám mục, saga khoá kỳ sáu bước) · TL-02 QT-08 · EVT-09, EVT-10, EVT-17, EVT-18, EVT-19, EVT-26 · PERF-04, PERF-17, PERF-19, PERF-20 · INF-16 `deploy/tools/prescale/sinh_cua_so.py` · DV-06-06 saga chốt kỳ.
> **Chu kỳ:** mỗi kỳ, bắt đầu mười ngày trước mốc hạn · **Chịu trách nhiệm:** SRE, chủ sản phẩm · **Tự động hoá:** một phần.
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes). Theo YC-PT-19, bước chạy PERF-04 được ghi nhận để làm ở giai đoạn đo tải, không chạy ở giai đoạn phát triển.

## Điều kiện kích hoạt

Mười ngày làm việc trước mỗi mốc hạn nộp hoặc mốc khoá kỳ của kỳ đánh giá (quý, năm) theo `config.period` của từng tenant.

## Tác động

Tải đỉnh tập trung cuối kỳ; thiếu chuẩn bị dẫn tới vi phạm SLO đúng lúc đông người dùng nhất, và lỗi ở bước 4, 5 của saga khoá kỳ không quay lui được.

## Kiểm tra nhanh

```bash
cat deploy/cells/<cell>/prescale-windows.yaml                     # cửa sổ tăng trước đã sinh cho kỳ
argocd proj windows list okr-apps                                 # cửa sổ đóng băng đã có (OPS-06)
vtctl -e <env> -c <cell> period-lock status <period-id>           # trạng thái khoá kỳ của tenant lớn
```

## Các bước

Danh mục (đánh dấu từng mục vào phiếu chuẩn bị của kỳ, ghi người và ngày):

1. Lịch tăng trước KEDA đã sinh đúng theo `config.period` của từng tenant: chạy `deploy/tools/prescale/sinh_cua_so.py` (hằng ngày, mở merge request) và đối chiếu `prescale-windows.yaml` của mỗi cell với mốc hạn.
2. Năng lực dự trữ Viettel Cloud sẵn sàng (hợp đồng giữ chỗ, TL-04 mục 4.4).
3. PERF-04 trên `CELL-PERF` với dữ liệu cập nhật (giai đoạn đo tải).
4. Ngân sách lỗi còn đủ; đóng các hạng mục khắc phục còn mở (xem `release-freeze`).
5. Lịch trực tăng cường đã xác nhận (OPS-13).
6. Thông báo khách hàng cửa sổ đóng băng và kênh hỗ trợ.
7. Kết quả diễn tập khôi phục gần nhất đạt (OPS-18, `restore-check`).
8. Dung lượng còn trống của Ceph, Kafka, OpenSearch ≥ 30% sau khi tính lượng tăng dự kiến (dashboard năng lực, cảnh báo NG-07, NG-08, NG-09).

Chuẩn bị cho saga khoá kỳ (QT-08): bước 0 kiểm điều kiện; 1 chuyển kỳ `DANG_KHOA` (EVT-09); 2 tính điểm hàng loạt (EVT-10); 3 sinh biểu mẫu (EVT-17); 4 ký lô (EVT-18); 5 `DA_KHOA` và niêm phong sổ cái (EVT-19). Lệnh khoá do lãnh đạo (AC-04) phê duyệt, quản trị tenant (AC-02) khởi động có MFA. Ca trực ghi thời điểm dự kiến của bước 4 với từng tenant lớn vào phiếu: trước bước 4 còn huỷ được (cần người duyệt thứ hai AC-09, phát EVT-26), sau bước 4 chỉ thử lại hoặc sửa tiến.

Trong khi saga chạy: theo dõi theo `../saga-chot-ky.md` mục 2, xử lý theo mục 3.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| Trạng thái khoá kỳ | `vtctl period-lock status <period-id>` | `GET /api/v1/periods/{id}/lock` |
| Tạm dừng, tiếp tục | `vtctl period-lock pause <period-id> --confirm`, `period-lock resume <period-id> --confirm` | API `/periods/{id}/lock*` |
| Thử lại hồ sơ lỗi | `vtctl period-lock retry-failed <period-id> [--profile <id>] --confirm` | API `/periods/{id}/lock*` |

## Kiểm sau khi làm

Phiếu chuẩn bị có đủ tám mục đã đánh dấu và thời điểm dự kiến bước 4 cho tenant lớn; sau mốc, kỳ của mọi tenant ở `DA_KHOA` và `ledger-verify` hợp lệ.

## Quay lui

Mục chuẩn bị không đạt (ví dụ dung lượng dưới 30%): nâng năng lực hoặc lùi mốc hạn với chủ sản phẩm, không bỏ qua. Saga khoá kỳ: huỷ chỉ trước bước 4.

## Leo thang

Thiếu năng lực dự trữ hoặc diễn tập khôi phục gần nhất thất bại: báo chủ sản phẩm và trưởng ca SRE ngay, ghi vào CAB tuần.

## Liên quan

`../saga-chot-ky.md`, [OPS-06](ops-06-dong-bang-thay-doi.md), [OPS-18](ops-18-dien-tap-khoi-phuc.md), [nang-bac](../canh-bao/nang-bac.md).
