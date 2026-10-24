# Stack tối thiểu chạy thật INF-25 và INF-26 không cần Docker

> INF-25 (bộ điều khiển suy giảm ba mức), INF-26 (`vtctl`). Dùng khi không dựng được kind (container kiểm thử không có Docker daemon). Thay kind bằng etcd và kube-apiserver thật của envtest; mọi thành phần còn lại là bản nhị phân thật của repo hoặc của dự án gốc.

## Thành phần

| Thành phần | Bản chạy | Ghi chú |
|---|---|---|
| Kubernetes | etcd + kube-apiserver v1.34.1 (envtest) | Không có kubelet: Deployment không có pod, đủ để kiểm patch, chú thích, RBAC. Ba người dùng tĩnh: quản trị (`van-hanh`), người trực thuộc nhóm `oidc:platform-ops-sre-truc` (`truc`), người không thuộc nhóm (`khach`). |
| CRD | `crd-toi-thieu.yaml` | ApisixPluginConfig, ScaledObject với lược đồ mở, chỉ để lưu đối tượng. |
| Tài nguyên cụm | render chart thật | `security-cell` (ClusterRole `view-app`, `operate-app` và RoleBinding), `api-gateway` (`values-ci`, `degrade.controller.external=true`: 18 ApisixPluginConfig ở 6 namespace và ConfigMap bộ plugin), `degrade-controller` (ServiceAccount, Role, RoleBinding), `observability-cell` (cấu hình Alertmanager của cell). |
| Bộ điều khiển suy giảm | `services-go/degrade-controller` | Chạy ngoài cụm với token ServiceAccount; `HOLD_DOWN` 20 s, `ESCALATE_AFTER` 15 s để kịch bản ngắn. |
| Alertmanager | 0.28.1 | Cấu hình lấy từ render `observability-cell`, chỉ đổi URL webhook sang cổng cục bộ. Tín hiệu giả gửi bằng `amtool alert add`. |
| tenant-registry | `services-go/tenant-registry` | PostgreSQL 17 thật, tự migrate, bật saga kích hoạt và dịch vụ chuyển cell. |
| Keycloak vận hành | `oidc_gia_lap.py` | Giả lập luồng thiết bị, JWKS RS256, bước lên `aal2`. |
| Dịch vụ ngoài | `provision-gia-lap` | Giả lập core, org, DNS, chứng chỉ của saga. |
| Kênh trực | `thu_thong_bao.py` | Ghi mỗi thông báo thành một dòng JSONL. |

## Chạy

```bash
export VT_CONG_CU=/dev/shm/vt-cong-cu W=/dev/shm/vt-van-hanh
./tai-cong-cu.sh            # tải công cụ, dựng nhị phân (một lần)
./dung-stack.sh             # xoá và dựng lại $W, in lệnh source
source $W/moi-truong.sh
python3 -I kiem_thu_suy_giam.py   # INF-25
./dung-stack.sh             # dựng lại sạch trước kịch bản vtctl
python3 -I kiem_thu_vtctl.py      # INF-26
./dung-stack.sh dung
```

## Không phủ

Không có lưu lượng qua APISIX (kiểm bằng `deploy/charts/api-gateway/tests/kiem-thu-cuc-bo.sh` khi có Docker; ở đây kiểm bộ plugin được ghi lên ApisixPluginConfig), không có Kafka (`vtctl dlq`, `consumer lag` kiểm bằng `go test` với `kfake`), không có pod chạy (rollout restart chỉ kiểm chú thích template), không đo tải.
