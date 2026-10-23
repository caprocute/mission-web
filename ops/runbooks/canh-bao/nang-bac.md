# Ngưỡng nâng bậc NG-01 đến NG-14 và quy trình nâng bậc

> **Truy vết:** TL-05 chương "Ngưỡng nâng bậc và quy trình mở rộng" · TL-04 OPS-23, OPS-25 · luật `deploy/charts/observability-mgmt/rules/nang-luc.yaml`.
> **Cảnh báo:** `NG01CellQuaTai` đến `NG14MoHinhDinhCo` (mức "kế hoạch năng lực", không gọi trực ca, gửi kênh đội nền tảng).
> **Chạy thử:** chưa chạy thử theo sổ tay trên kind hay staging (giai đoạn này chỉ phát triển, YC-PT-19; môi trường làm việc của luồng không có cụm Kubernetes).

## Điều kiện kích hoạt

Một chỉ số năng lực vượt ngưỡng cảnh báo sớm. Một ngưỡng chỉ được coi là chạm khi vượt ở hai kỳ đỉnh liên tiếp (hai đợt hạn nộp quý), trừ ngưỡng ghi "bất kỳ lúc nào" hoặc ngưỡng cứng.

## Tác động

Không ảnh hưởng người dùng ngay; nếu không hành động trước kỳ đỉnh tiếp theo, cell có thể không đủ năng lực.

## Kiểm tra nhanh

| Cảnh báo | Mã | Ngưỡng | Hành động | Phần cứng mới |
|---|---|---|---|---|
| `NG01CellQuaTai` | NG-01 | Cell vượt 1,5 triệu tài khoản hoặc 75.000 CCU | Tách cell theo OPS-23, chuyển tenant theo OPS-08 | Có, một cell mới |
| `NG02FlowableHaiKy, NG02FlowableDinh` | NG-02 | User task hoàn thành > 150/giây ở hai kỳ; > 300/giây bất kỳ lúc nào | Tăng `maxReplicas` workflow API và executor, kiểm `pg-flowable`; trên 300/giây tách engine theo cell con | Có thể |
| `NG03CpuPgCore` | NG-03 | CPU primary `pg-core` p95 giờ đỉnh > 60% ở hai kỳ | Hồ sơ M: nâng instance lên 24 vCPU; hồ sơ L: chuyển truy vấn đọc sang standby, rà truy vấn chậm, xem xét tách cell | Không ở M; có thể ở L |
| `NG04TranHoSo` | NG-04 | Tài khoản > 90% trần hồ sơ hoặc dự báo vượt trong 2 quý | Nâng hồ sơ: compact → S → M → L | Có |
| `NG05MissionCoreMaxReplicas` | NG-05 | mission-core ở `maxReplicas` > 10 phút trong hai ngày hạn liên tiếp | Tăng `maxReplicas` trong ngân sách kết nối, kiểm NG-03 | Không |
| `NG06PoolChamTran` | NG-06 | Node pool chạm giới hạn trên của Cluster Autoscaler trong ngày hạn | Nâng giới hạn pool và hợp đồng giữ chỗ | Không (máy ảo) |
| `NG07CephDay, NG07CephNguongCung` | NG-07 | Ceph > 60%: đặt hàng ổ; > 75%: ngưỡng cứng | Lắp ổ vào khe trống; hết khe thì thêm máy PHY-CEPH; ở ngưỡng cứng ngừng nhận dữ liệu không thiết yếu | Có |
| `NG08KafkaDia` | NG-08 | Đĩa broker > 50% kéo dài 7 ngày; > 4.000 phân vùng mỗi broker | Rà thời gian lưu giữ; thêm broker | Có thể |
| `NG09OpenSearch` | NG-09 | Shard > 50 GB; heap > 75% kéo dài | Chia chỉ mục theo năm sớm hơn; thêm node dữ liệu | Có thể |
| `NG10ClickHouse` | NG-10 | Đĩa ClickHouse > 70%; p95 truy vấn dashboard > 1 giây | Thêm shard, rà lớp tổng hợp trước của Cube | Có thể |
| `NG11Valkey` | NG-11 | Bộ nhớ Valkey > 70% `maxmemory` hoặc có trục xuất khoá quyền | Thêm shard Valkey | Không |
| `NG12ChuoiMetric` | NG-12 | Hơn 3 triệu chuỗi metric hoạt động mỗi cell | Nâng pool system; rà nhãn có số giá trị lớn | Không (máy ảo) |
| `NG13Gpu` | NG-13 | Hàng đợi vLLM > 200 kéo dài 15 phút; 503 của ai-gateway > 1% giờ làm việc | Thêm GPU cho cell | Có |
| `NG14MoHinhDinhCo` | NG-14 | CCU > 6% số tài khoản hoặc thời gian suy nghĩ < 15 giây | Chạy lại mô hình định cỡ với số đo, cập nhật TL-05 trước khi đặt hàng bậc tiếp | Tuỳ kết quả |

## Các bước

1. Ghi ngưỡng đã chạm vào báo cáo năng lực quý ([OPS-25](../ops/ops-25-ra-soat-nang-luc.md)).
2. Khi chạm ở hai kỳ đỉnh liên tiếp: đội nền tảng chạy lại kịch bản PERF liên quan trên `CELL-PERF` với số đo cập nhật, để phân biệt tải thật cao hơn giả định với hồi quy hiệu năng trong mã (giai đoạn đo tải, YC-PT-19).
3. Chọn loại hành động: điều chỉnh trong hồ sơ (merge request vào kho GitOps, có hiệu lực trong ngày), nâng hồ sơ cell (mua sắm phần cứng), hoặc tách cell (OPS-23).
4. Ngưỡng cứng NG-07 (Ceph > 75%): xử lý như sự cố SEV-2, ngừng nhận dữ liệu không thiết yếu.

## Lệnh vtctl và lệnh tương đương

| Thao tác | vtctl | Thủ công |
|---|---|---|
| (mọi thao tác) | (chưa có) | như mục "Kiểm tra nhanh" và "Các bước" |

## Kiểm sau khi làm

Chỉ số về dưới ngưỡng sau hành động; báo cáo năng lực quý ghi kết quả.

## Quay lui

Revert merge request điều chỉnh hồ sơ.

## Leo thang

Hành động cần phần cứng mà thời gian mua sắm dài hơn thời gian dự báo chạm ngưỡng: báo kiến trúc và chủ sản phẩm.

## Liên quan

[OPS-23](../ops/ops-23-them-cell-moi.md), [OPS-25](../ops/ops-25-ra-soat-nang-luc.md), [capacity](capacity.md), `deploy/capacity/`.
