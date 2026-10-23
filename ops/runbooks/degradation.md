# Sổ tay vận hành: Bật, tắt mức suy giảm của cổng API (`degradation`)

> **Mã truy vết:** OPS-14 · DV-01-02 · TL-04 "Hành vi khi bão hoà" (ba mức suy giảm) và "Cắt tải và giới hạn tốc độ ở APISIX" · TL-01 mục 14.5 (lớp ưu tiên) · cảnh báo `SaturationLevel2` (`deploy/charts/observability-cell/rules/canh-bao-toi-thieu.yaml`).
> **Thành phần:** bộ điều khiển suy giảm `apisix-degrade-controller` (namespace `edge`, chart `deploy/charts/api-gateway`), ApisixPluginConfig `protected-api`, `protected-api-p1`, `protected-api-p3` ở mọi namespace có tuyến.

## 1. Triệu chứng và tác động

Cell đang ở mức suy giảm khác 0 khi metric `vt_degrade_level` của bộ điều khiển lớn hơn 0, hoặc khi nhãn `vt.io/degrade-level` trên các ApisixPluginConfig `protected-api*` khác `"0"`. Cảnh báo `SaturationLevel2` (critical, đội IC) bật khi mức từ 2 trở lên. Tác động lên người dùng theo từng mức như sau.

| Mức | Hành động ở cổng | Người dùng thấy |
|---|---|---|
| 0 | Không | Bình thường |
| 1 | Tuyến `/api/v1/ai/*` trả 503 kèm `Retry-After: 60`; mọi request tới upstream mang header `X-Vt-Degrade-Level: 1` để dịch vụ rút gọn tìm kiếm, đọc bảng điều hành từ tổng hợp sẵn, chuyển xuất báo cáo sang hàng đợi | Trợ lý AI tạm tắt |
| 2 | Thêm: giới hạn tenant (`limit-req`, luật ghi đè theo tenant) giảm 30% | Tenant dùng nhiều có thể nhận 429 sớm hơn |
| 3 | Thêm: mọi tuyến lớp 2 và lớp 3 trả 503 kèm `Retry-After: 60` và thân RFC 9457; chỉ tuyến lớp 1 (nộp, ký hồ sơ, `esign`) được qua | Giao diện hiển thị thông báo xếp hàng, chỉ nộp và ký được |

Mức áp dụng là mức đặt tay nếu người trực đã đặt, ngược lại là mức cao nhất trong số mức nền khai trong Git (`degrade.level`) và mức của các cảnh báo đang cháy có nhãn `degrade_level`.

## 2. Kiểm tra nhanh

```bash
vtctl -e <môi trường> -c <cell> degrade status
```

Phương án dự phòng khi chưa có vtctl (lệnh thủ công):

```bash
# Mức hiện hành, mức đích, mức đặt tay và các mức cảnh báo đang cháy.
kubectl -n edge port-forward svc/apisix-degrade-controller 18080:8080 &
curl -s http://127.0.0.1:18080/level
# Nhãn trên từng ApisixPluginConfig (phải cùng một mức).
kubectl get apisixpluginconfigs -A -l vt.io/degrade-managed=true \
  -L vt.io/priority-class,vt.io/degrade-level
# Nhật ký chuyển mức (JSON một dòng, sự kiện degrade_level_changed hoặc degrade_apply_failed).
kubectl -n edge logs deploy/apisix-degrade-controller --since=1h | grep degrade_
```

Trên Grafana của cell, đối chiếu `vt_degrade_level` với p95 đọc của mission-core, tỷ lệ 5xx, Hikari pending và CPU primary PostgreSQL, là các điều kiện kích hoạt mức 1, 2 và 3 của TL-04.

## 3. Bật hoặc tắt mức bằng tay

Mọi lệnh ghi của `vtctl` mặc định chỉ chạy thử và in tóm tắt tác động; thêm `--confirm` mới thực hiện, khi đó vtctl ghi một bản ghi kiểm toán (INF-26, `services-go/vtctl/README.md`). vtctl tự đọc token webhook từ Secret mà không in ra màn hình; người chạy cần quyền đọc Secret đó trong namespace `edge`.

```bash
vtctl -e <môi trường> -c <cell> degrade set 3 --reason "CPU primary 95% từ 09:10" --confirm
vtctl -e <môi trường> -c <cell> degrade clear --reason "CPU primary về 60%" --confirm
# Kiểm chứng: mức áp dụng trả về đúng mức đã đặt, nhãn vt.io/degrade-level trên ApisixPluginConfig cùng một mức.
vtctl -e <môi trường> -c <cell> degrade status
```

Quay lui: `degrade clear` trả về mức theo cảnh báo và mức nền. Phương án dự phòng (lệnh thủ công):

Token webhook nằm trong Secret `apisix-degrade-webhook` (ExternalSecret từ OpenBao). Không in token ra màn hình, không dán vào kênh chat; đọc vào biến môi trường của phiên shell rồi xoá biến sau khi dùng.

```bash
TOKEN=$(kubectl -n edge get secret apisix-degrade-webhook -o jsonpath='{.data.token}' | base64 -d)
# Đặt mức 3 (chế độ bảo vệ), ghi lý do để vào nhật ký vận hành.
curl -s -X POST http://127.0.0.1:18080/level -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"level": 3, "reason": "OPS-14: CPU primary 95% từ 09:10"}'
# Bỏ mức đặt tay: trở về mức theo cảnh báo và mức nền.
curl -s -X POST http://127.0.0.1:18080/level -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"level": null, "reason": "OPS-14: CPU primary về 60%"}'
unset TOKEN
```

Kết quả trả `{"level": <mức đã áp dụng>}`. Mã 401 là sai token, 422 là mức ngoài 0..3, 502 là bộ điều khiển không ghi được ApisixPluginConfig (xem mục 5).

Khi bộ điều khiển không chạy, người trực đổi mức nền trong kho GitOps của môi trường (`degrade.level` trong `deploy/environments/<môi trường>/api-gateway/values.yaml`) và để Argo CD đồng bộ; cách này chậm hơn nhưng không phụ thuộc bộ điều khiển. Trên cụm kind (chế độ standalone, không có bộ điều khiển), dùng `helm upgrade api-gateway deploy/charts/api-gateway --reuse-values --set degrade.level=<0..3>`.

## 4. Nối Alertmanager

Luật cảnh báo nào muốn tự bật mức suy giảm thì mang nhãn `degrade_level: "1" | "2" | "3"` và được định tuyến tới receiver webhook sau (cấu hình của INF-15, ghi ở đây để đối chiếu):

```yaml
receivers:
  - name: vt-degrade
    webhook_configs:
      - url: http://apisix-degrade-controller.edge.svc:8080/alertmanager
        send_resolved: true
        http_config:
          authorization: { type: Bearer, credentials_file: /etc/alertmanager/secrets/apisix-degrade-webhook/token }
route:
  routes:
    - matchers: ['degrade_level=~"1|2|3"']
      receiver: vt-degrade
      continue: true
      group_by: [alertname, cell]
      repeat_interval: 5m
```

`send_resolved: true` là bắt buộc để bộ điều khiển hạ mức khi cảnh báo hết. `repeat_interval` ngắn giúp bộ điều khiển dựng lại trạng thái sau khi pod khởi động lại, vì trạng thái cảnh báo chỉ nằm trong bộ nhớ.

## 5. Hành động theo nhánh và leo thang

1. Mức 1 kéo dài: kiểm tra mission-core đã ở `maxReplicas` chưa và có thể tăng trần HPA theo runbook `capacity`; không cần leo thang nếu p95 đọc trở lại dưới 300 ms.
2. Mức 2 trở lên quá 15 phút: nâng sự cố thành SEV-2 theo OPS-13, thông báo trên kênh trực, chỉ huy sự cố quyết định giữ hay hạ mức.
3. Mức 3: thông báo cho chủ sản phẩm của cell; kiểm tra truy vấn nặng trên primary (`pg-cpu`) và tạm dừng tác vụ nền không gấp.
4. `degrade_apply_failed` trong nhật ký hoặc 502 khi đặt mức: kiểm tra quyền của ServiceAccount `apisix-degrade-controller` (ClusterRole chỉ có get, list, patch `apisixpluginconfigs`) và NetworkPolicy tới kube-apiserver (`ingressController.apiServerCIDRs`).
5. Mức trên ApisixPluginConfig tự quay về mức nền sau vài phút: Argo CD đang tự chữa lệch (selfHeal) với bản trong Git. Application của api-gateway cần `ignoreDifferences` cho `/spec/plugins` và nhãn `vt.io/degrade-level` của kind ApisixPluginConfig cùng `RespectIgnoreDifferences=true`; nếu chưa có, đổi mức qua Git như mục 3.

## 6. Xác nhận đã khắc phục

Mức đã về 0 khi `curl -s http://127.0.0.1:18080/level` trả `"level": 0`, mọi ApisixPluginConfig `protected-api*` mang `vt.io/degrade-level=0`, request tới `/api/v1/tasks` có token hợp lệ trả 200 và upstream nhận `X-Vt-Degrade-Level: 0`. Ghi thời điểm bật, tắt, lý do và người thao tác vào nhật ký sự cố.
