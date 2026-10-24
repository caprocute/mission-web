# INF-01 · TL-04 ch.5 Chart thư viện `vt-service`: probe, PDB, phân bố vùng, HPA, NetworkPolicy, tài nguyên

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-01` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | – |
| Truy vết | TL-04 ch.5 (mục 5.1, 5.2, 5.5) → SEC-26, SEC-28, SEC-49, SEC-55 → ADR-17 (ESO + OpenBao), ADR-21 (OPA cùng pod) → tiêu chí C1–C7 |
| Trạng thái | Hoàn thành (20/10/2026, đã gộp vào `main`: `deploy/charts/vt-service/`, `deploy/charts/vt-service/tests/chay-kiem-thu.sh`) |

## 1. Mục tiêu

Có một Helm library chart `vt-service` mà mọi service SVC-01..SVC-17 dùng chung để sinh manifest đúng chuẩn của TL-04 chương 5: mặc định an toàn, có probe, PDB, phân bố theo vùng sự cố, một bộ co giãn duy nhất, NetworkPolicy chặn mặc định và tài nguyên khai báo đầy đủ. Khi task xong, một chart thử dùng `vt-service` dựng ra manifest khớp bản tham chiếu org-service của TL-04 mục 5.5, qua kiểm tra tĩnh và cài được lên cụm kind với pod sẵn sàng. Đây là nền cho INF-02 và cho tiêu chí đợt 0b "mất một node không gián đoạn".

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` chương "Cấu hình triển khai từng service": mục "Nguyên tắc cấu hình chung" (dòng 835–849), "Bảng cấu hình theo service" (851–881), "Manifest mẫu: Deployment, HPA và PDB" (1036–1196).
- Cùng tệp, chương "Thiết kế cụm Kubernetes cho một cell": mục "Taint, toleration và ràng buộc phân bố theo vùng sự cố" (254–292), "NetworkPolicy mặc định từ chối" (333–443); chương co giãn mục "HPA autoscaling/v2 với chỉ số tài nguyên và chỉ số tuỳ chỉnh" (1208–1248).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-05-dinh-co-he-thong.md` Phụ lục A (dòng 757–875): hồ sơ `compact` đổi phân bố từ theo vùng sang theo node.
- `docs/quy-dinh/HA-TANG.md`, `wiki/12-ha-tang-dev-va-trien-khai.md`, trang wiki 19 hạ tầng production (do NEN-003 tạo, nếu đã có).

## 3. Phạm vi công việc

- Tạo library chart `deploy/charts/vt-service` (type `library`) với các template sinh: Deployment hoặc Rollout (Argo Rollouts, theo `workload.kind`), Service, ServiceAccount, ConfigMap, ExternalSecret, PodDisruptionBudget, NetworkPolicy, HPA hoặc ScaledObject, ServiceMonitor, PrometheusRule; chart báo lỗi khi values bật đồng thời HPA và ScaledObject cho cùng workload (TL-04 mục 6.1).
- Đặt mặc định an toàn: chạy không root UID 10001, `readOnlyRootFilesystem: true`, drop ALL capabilities, `allowPrivilegeEscalation: false`, seccomp `RuntimeDefault`, `automountServiceAccountToken: false`, `/tmp` là emptyDir 1 GiB, ảnh bắt buộc tham chiếu theo digest (SEC-49, SEC-55); template từ chối render khi ảnh không có `@sha256:`.
- Sinh probe trên cổng quản trị 8081 theo nhóm health của Actuator cho Java (startup và liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness`), cho phép ghi đè đường dẫn và cổng cho Go (`/healthz`, `/readyz`), Python và nginx của SVC-17 (`/healthz`); đặt `preStop` sleep 5 s, `terminationGracePeriodSeconds: 40` mặc định.
- Sinh hai ràng buộc `topologySpreadConstraints` cho mọi workload từ 2 replica: theo `topology.kubernetes.io/zone` `maxSkew: 1` `DoNotSchedule` và theo `kubernetes.io/hostname` `maxSkew: 2` `ScheduleAnyway`, cả hai có `matchLabelKeys: [pod-template-hash]`; khi `global.sizing: compact` chỉ giữ ràng buộc theo node (TL-05 Phụ lục A).
- Sinh toleration và `nodeSelector` từ khoá `nodePool` (`app-general`, `worker`, `ingress`, `secure`…) đúng taint của TL-04 mục 3.3–3.4.
- Sinh NetworkPolicy từ `networkPolicy.ingressFrom` và `networkPolicy.egressTo`, luôn có luồng DNS tới `kube-system/kube-dns` cổng 53; không sinh luồng nào khác ngoài khai báo.
- Cung cấp preset runtime: Java (JRE 21, `-XX:MaxRAMPercentage=70`, ZGC thế hệ cho API hoặc G1 cho lô, `requests.memory = limits.memory`), Go (`GOMEMLIMIT` 80% limit, `GOMAXPROCS` theo CPU limit), Python (số worker Uvicorn bằng số vCPU limit), và container `opa` tuỳ chọn 100m/128 MiB, limit 256 MiB (ADR-21).
- Sinh PDB trước Deployment trong thứ tự render, có `unhealthyPodEvictionPolicy: AlwaysAllow`; HPA mặc định theo behavior chuẩn TL-04 mục 6.2 (scaleUp window 0, tối đa gấp đôi hoặc +4 pod mỗi 30 s; scaleDown window 600 s, tối đa 20% mỗi 120 s).
- Điểm lệch cần lưu ý: manifest org-service ở TL-04 mục 5.5 dùng +2 pod/30 s, khác chuẩn +4 pod ở mục 6.2. Chọn +4 pod làm mặc định của chart (quy tắc chung), org-service đặt +2 pod qua values để golden khớp mục 5.5; ghi điểm này vào `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.
- Gợi ý tách nếu quá lớn: (a) workload, bảo mật, probe; (b) PDB, spread, NetworkPolicy; (c) co giãn, ServiceMonitor, PrometheusRule, golden test.

## 4. Ngoài phạm vi

- Chart cụ thể của từng service, chart tổng `vt-platform` và ba tệp values mô hình phân phối (INF-02); values định cỡ theo hồ sơ (INF-03).
- Chính sách Kyverno chạy trên cụm (INF-07); Prometheus Adapter, KEDA và luật cảnh báo cụ thể (INF-15, INF-16).

## 5. Tệp được phép sửa

- `deploy/charts/vt-service/**` (gồm `tests/` chứa golden và chart thử `tests/vt-service-example/`).
- `deploy/environments/kind/**` (cấu hình cụm kind ba node gắn nhãn ba vùng giả lập, suy luận về vị trí).
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md` (chỉ mục liên quan).

## 6. Tiêu chí hoàn thành

- `helm lint deploy/charts/vt-service/tests/vt-service-example` và `helm template` không lỗi; đầu ra qua `kubeconform -strict` (kèm schema CRD của Argo Rollouts, KEDA, ESO, Prometheus Operator) và `kube-linter lint` không có lỗi.
- Golden test: `helm template` với values mô phỏng org-service cho ra Deployment, HPA, PDB khớp manifest TL-04 mục 5.5 (dòng 1040–1194) về các trường đã nêu ở mục 3; chạy bằng script trong `tests/` và chạy được trong CI.
- Render với ảnh không có digest, hoặc bật đồng thời HPA và ScaledObject, phải thất bại có thông báo rõ.
- Cài chart thử lên cụm kind ba node: mọi pod `Ready`, PDB và spread có hiệu lực (xoá một node giả lập, pod xếp lại trên node khác trong ≤ 2 phút).
- Báo cáo cuối task theo AGENTS.md mục 6, ghi rõ phần đã kiểm trên kind và giới hạn so với cụm thật; wiki 12 có mục mô tả cách dùng `vt-service`.

## 7. Báo cáo

Theo AGENTS.md mục 6.
