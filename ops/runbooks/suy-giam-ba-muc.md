# Sổ tay vận hành: Bộ điều khiển suy giảm ba mức (`degrade-controller`)

> **Mã truy vết:** INF-25 · TL-04 "Hành vi khi bão hoà", "Cắt tải và giới hạn tốc độ ở APISIX" · OPS-13, OPS-14 · tiêu chí F4, CWE-400.
> **Thành phần:** Deployment `degrade-controller` (namespace `edge`, chart `deploy/charts/degrade-controller`, mã `services-go/degrade-controller`); bộ plugin từng mức trong ConfigMap `apisix-degrade-levels` của chart `api-gateway` (DV-01-02). Tác động lên người dùng theo từng mức và cách đọc trạng thái cổng: runbook `degradation.md`.

## 1. Bật bộ điều khiển

1. Ở values của môi trường cho chart `api-gateway`, đặt `degrade.controller.external: true` (chart đó chỉ dựng ConfigMap bộ plugin, gỡ bộ điều khiển Python cũ).
2. Cài chart `degrade-controller` vào namespace `edge` qua Argo CD với `cell`, `apiServerCIDRs`, `jobNamespaces` (namespace có ScaledObject của tác vụ nền) và `notify.url` (kênh trực). Chart tạo Secret `apisix-degrade-webhook` bằng ExternalSecret từ OpenBao khoá `kv/cell-<cell>/edge/degrade-webhook`, thuộc tính `token` (`webhookToken.externalSecret`).
3. Đường cảnh báo nằm trong cell, không qua Alertmanager của cụm quản trị: chart `observability-cell` chạy một Alertmanager hai bản (`kube-prometheus-stack.alertmanager`) chỉ có receiver `vt-degrade` tới `http://degrade-controller.edge.svc:8080/alertmanager`, đọc token từ Secret cùng tên trong namespace `observability` (ExternalSecret cùng khoá OpenBao, `suyGiam.token`). Ba luật kích hoạt `DegradeLevel1..3` ở `deploy/charts/observability-cell/rules/suy-giam.yaml` theo đúng bảng điều kiện của TL-04; luật khác muốn tự bật mức chỉ cần mang nhãn `degrade_level`.
4. Gắn nhãn tạm dừng cho ScaledObject của tác vụ nền không gấp (đồng bộ tích hợp, tính lại hàng loạt không thuộc kỳ đang đóng) bằng `autoscaling.keda.degradePauseFrom: 2` trong values của service đó (chart `vt-service` sinh nhãn `vt.io/degrade-pause-from`). Hiện chưa service nào khai: integration-gateway co giãn bằng HPA, còn ScaledObject của scoring-service phục vụ chung tính điểm tăng dần của kỳ đang mở và tính lại hàng loạt, nên tạm dừng nó vi phạm điều kiện "giữ thao tác thuộc kỳ đang mở hạn" của mức 2. Khi tách worker tính lại hàng loạt thành workload riêng thì khai nhãn cho workload đó.

Kiểm chứng: `vtctl -e <môi trường> -c <cell> degrade status` trả `level`, `target`, `base`; `kubectl -n edge logs deploy/degrade-controller | grep degrade_controller_started`; Application của api-gateway và các service có `ignoreDifferences` cho nhãn mức, `spec.plugins`, chú thích tạm dừng (chart `deploy/argocd`, khoá `ignoreDifferences`).

## 2. Ghim, đặt và bỏ mức bằng tay (OPS-14)

```bash
vtctl -e <môi trường> -c <cell> degrade pin 3 --for 30m --reason "CPU primary 95% từ 09:10"            # chạy thử
vtctl -e <môi trường> -c <cell> degrade pin 3 --for 30m --reason "CPU primary 95% từ 09:10" --confirm  # ghim
vtctl -e <môi trường> -c <cell> degrade status   # level 3, manual 3, pin.until
vtctl -e <môi trường> -c <cell> degrade clear --reason "CPU primary về 60%" --confirm                  # bỏ ghim
```

Lệnh ghi đọc token webhook từ Secret `edge/apisix-degrade-webhook`. Vai trò thường trực `operate-app` không cho đọc Secret, nên chart cấp riêng Role `degrade-controller-token-reader` (chỉ `get` đúng Secret này) cho các nhóm ở `webhookToken.nguoiDoc`, mặc định `oidc:platform-ops-sre-truc`; người ngoài nhóm nhận hướng dẫn xin quyền qua PAM. Kiểm: `kubectl auth can-i get secret/apisix-degrade-webhook -n edge` trả `yes`.

Ghim ưu tiên hơn cảnh báo theo cả hai chiều và hết hạn tự động (mặc định 1 giờ, tối đa 24 giờ). `degrade set <mức>` tương đương ghim với thời hạn mặc định. Bỏ ghim hoặc hết hạn thì trở về mức tự động; nếu mức tự động thấp hơn, bộ điều khiển chờ 10 phút chống dao động rồi mới hạ. Muốn hạ ngay, ghim mức thấp hơn trong thời gian ngắn rồi bỏ ghim.

Phương án dự phòng khi không có vtctl: `kubectl -n edge port-forward svc/degrade-controller 18080:8080`, rồi `curl -s -X POST -H "Authorization: Bearer $TOKEN" -d '{"level":3,"ttl":"30m","reason":"..."}' http://127.0.0.1:18080/level` (token đọc từ Secret `apisix-degrade-webhook` vào biến của phiên shell, không in ra, xoá biến sau khi dùng).

## 3. Kiểm chứng sau mỗi lần chuyển mức

1. `degrade status`: `level` bằng mức mong muốn; khi đang chờ hạ thì có `holdDownUntil`.
2. `kubectl get apisixpluginconfigs -A -l vt.io/degrade-managed=true -L vt.io/degrade-level` cùng một mức ở mọi namespace của `gatewayNamespaces` (ApisixPluginConfig nằm ở namespace của backend, không ở `edge`).
3. Mức 3: request ngoài danh sách ưu tiên nhận 503 kèm `Retry-After: 60` và thân RFC 9457; request nộp hồ sơ vẫn qua.
4. Mức ≥ 2: `kubectl get scaledobjects -n <ns> -l vt.io/degrade-pause-from -o custom-columns=TEN:.metadata.name,DUNG:.metadata.annotations.autoscaling\.keda\.sh/paused` cho thấy `true`; về dưới 2 thì chú thích bị gỡ (chỉ chú thích do bộ điều khiển đặt, đánh dấu `vt.io/degrade-paused-by`).
5. Nhật ký `degrade_level_changed` và thông báo trên kênh trực; mức ≥ 2 quá 15 phút có thông báo `degrade_escalate_sev2`, IC nâng sự cố SEV-2 (OPS-13).

## 4. Xử lý sự cố

- **502 khi đặt mức, `degrade_apply_failed`, `vt_degrade_errors_total` tăng:** kiểm Role `degrade-controller-gateway` (get, list, patch `apisixpluginconfigs`) và `degrade-controller-jobs` ở từng namespace, NetworkPolicy tới kube-apiserver (`apiServerCIDRs`). Mức đã tính vẫn được ghi lại ở chu kỳ sau khi API server hồi phục.
- **Thiếu tệp bộ plugin (`thiếu bộ plugin lớp ... mức ...`):** ConfigMap `apisix-degrade-levels` chưa có, kiểm `degrade.controller.external=true` và `degrade.controller.enabled=true` ở api-gateway.
- **Mức tự quay về sau đồng bộ Argo CD:** Application thiếu `ignoreDifferences` hoặc `RespectIgnoreDifferences=true`; kiểm `deploy/argocd/values.yaml` khoá `ignoreDifferences`.
- **Mức không tự hạ dù hết tải:** xem `firingAlerts` trong `degrade status`; cảnh báo chưa `resolved` ở Alertmanager thì xử lý ở đó; ghim còn hạn thì `degrade clear`.

## 5. Quay lui

Quay về bộ điều khiển Python cũ: đặt `degrade.controller.external: false` ở api-gateway và gỡ Application `degrade-controller`; đổi receiver Alertmanager về `apisix-degrade-controller.edge.svc`. Mức đang ghi trên ApisixPluginConfig giữ nguyên trong lúc chuyển. Khi không có bộ điều khiển nào, đổi mức nền qua Git như runbook `degradation.md` mục 3.
