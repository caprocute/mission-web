# Môi trường staging và môi trường tạm theo pull request (INF-21)

Nguồn: TL-05 mục 7.2 "Môi trường phi sản xuất"; TL-04 mục 11.2 (staging là điểm dừng bắt buộc của bản phát hành),
OPS-02 (đợt phát hành), SEC-52 (không dữ liệu thật ngoài sản xuất).

## Staging chạy thế nào

Staging dùng cùng chart `deploy/charts/vt-platform`, cùng danh sách service với production. Cell `CELL-STG`
(`deploy/cells/stg/`) dùng hồ sơ S rút gọn: giữ `minReplicas` của hồ sơ S và hạ `maxReplicas` bằng `minReplicas`, tắt
GPU (trợ lý AI tắt bằng cờ tính năng), một TTDL ở Hoà Lạc. Tầng dữ liệu (PostgreSQL, Kafka, Valkey, OpenSearch,
ClickHouse, OpenBao) chạy trên máy ảo ngoài cụm, nên Argo CD không sinh Application cho các chart dữ liệu.

Argo CD của cụm quản trị chạy hai ứng dụng gốc trên cùng chart `deploy/argocd`: `ung-dung-goc.yaml` (production,
`values.yaml`) và `ung-dung-goc-staging.yaml` (`values.yaml` + `values-staging.yaml`). Bản staging sinh ApplicationSet
`okr-stg-<service>` chỉ cho `CELL-STG`, AppProject riêng `okr-apps-stg`, `okr-nen-tang-stg`, đồng bộ tự động ngay khi
`digests.yaml` trong thư mục này đổi, không RollingSync và không cửa sổ đóng băng. Bản production loại `stg` khỏi bộ
sinh (`cellsExclude`). Thứ tự phát hành vì vậy là: pipeline ghi digest vào `staging/digests.yaml` → staging đồng bộ và
chạy hành trình QA → người phát hành nâng digest sang `production/digests.yaml` → đợt 0 (`CELL-NB`) đến đợt 4 của OPS-02.
Trong `cell.yaml` staging mang `releaseWave: "-1"` để ghi rõ vị trí đó.

## Môi trường tạm theo pull request

Bản staging còn sinh ApplicationSet `okr-stg-pr` (`deploy/argocd/templates/moi-truong-pr.yaml`). Bộ sinh `pullRequest`
đọc các PR đang mở có nhãn `moi-truong-tam` (GitLab hoặc GitHub, token đọc PR nằm trong Secret `scm-pr-token` của
namespace `argocd`, không nằm trong Git), ghép với tệp `deploy/environments/pr/digests/pr-<số>.yaml` trên nhánh
`moi-truong-pr` của kho GitOps. Mỗi cặp sinh một Application `pr-<số>` lên cụm staging, gồm hai nguồn:

1. chart khung `deploy/environments/pr/khung`: Namespace `pr-<số>` (Pod Security restricted, nhãn
   `vt.io/du-lieu: tong-hop`), ResourceQuota, LimitRange, NetworkPolicy mặc định từ chối;
2. chart `vt-platform` với hồ sơ compact và `deploy/environments/pr/values.yaml`, chỉ bật các service ứng dụng trong
   `prEnvironments.services` (mission-core, org-service, workflow-service, scoring-service, web-frontend), mọi service
   đặt vào namespace `pr-<số>`, mỗi service một bản.

Dọn tự động: Application có finalizer `resources-finalizer.argocd.argoproj.io`. Khi PR đóng, được gộp hoặc bị gỡ nhãn,
bộ sinh bỏ phần tử, Argo CD xoá Application cùng mọi tài nguyên của nó, kể cả Namespace, nên không còn gì sót lại.
AppProject `okr-pr` chỉ cho đích namespace `pr-*` trên cụm staging, chỉ cho tài nguyên cấp cụm là Namespace và chặn
RBAC. Chart và values luôn lấy từ nhánh `main` có chữ ký; thay đổi chart trong PR được kiểm tĩnh ở CI, không dựng ở
môi trường tạm.

Môi trường tạm phục vụ hành trình kiểm thử QA-04..07 và chỉ dùng dữ liệu tổng hợp (DL-08), không bao giờ nạp dữ liệu
thật (SEC-52).

## Bảng tài nguyên theo môi trường

Số liệu staging, `CELL-PERF`, `perf-gen` lấy từ TL-05 mục 7.2. Theo YC-PT-19, giai đoạn này chỉ dựng staging và môi
trường tạm; `CELL-PERF` và cụm sinh tải chỉ ghi mục tiêu (xem `infra/perf/README.md`).

| Môi trường | Khi cần | Tài nguyên | Ghi chú |
|---|---|---|---|
| Staging (`CELL-STG`, hồ sơ S rút gọn) | Từ QM-1, giữ nguyên ở mọi bậc | ≈ 22 node, 330 vCPU, 1,4 TiB bộ nhớ | Không GPU, tầng dữ liệu trên máy ảo |
| Môi trường tạm theo PR | Khi PR có nhãn `moi-truong-tam` | Mỗi PR tối đa 8 vCPU yêu cầu, 16 GiB, 30 pod (ResourceQuota) | Chạy trong cụm staging; khoảng 40 vCPU cho năm PR cùng lúc (suy luận) |
| `CELL-PERF` tầng dữ liệu | Từ QM-3 (hồ sơ M), nâng L trước QM-4 | 14 máy vật lý (M) hoặc 22 máy (L) | Chỉ mục tiêu, chưa dựng |
| `CELL-PERF` tầng ứng dụng | Từ QM-3, khi chạy chiến dịch thử tải | 31 đến 41 node VM theo hồ sơ | Chỉ mục tiêu, chưa dựng |
| `perf-gen` | Từ QM-3, chỉ trong thời gian chạy thử | ≈ 10 × VM-16-32 (≈ 150 vCPU) | Chỉ mục tiêu, chưa dựng |

## Chưa làm

- Pipeline PR (INF-05) chưa có bước dựng ảnh của nhánh, ghi rồi xoá tệp `pr-<số>.yaml` trên nhánh `moi-truong-pr`.
- CSDL tổng hợp riêng cho từng PR và bước nạp dữ liệu tổng hợp (DL-08) chưa có; định tuyến từ cổng vào staging tới
  `pr-<số>` và dải địa chỉ của tầng dữ liệu máy ảo (`dataCidrs` của chart khung) điền khi dựng staging thật.
- Một số chart vẫn khai `replicas: 3` ở hồ sơ compact (workflow-service, scoring-service); ResourceQuota của môi trường
  tạm đã tính tới.
