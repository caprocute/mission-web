# degrade-controller (INF-25)

Bộ điều khiển suy giảm ba mức của cell theo TL-04 "Hành vi khi bão hoà", OPS-13 và OPS-14. Bộ điều khiển nhận webhook của Alertmanager và lệnh của người trực, tính mức áp dụng, rồi ghi mức đó ra cụm theo hai nhóm tác động. Thứ nhất là cổng API: nhãn `vt.io/degrade-level` và bộ plugin của từng lớp ưu tiên trên mọi `ApisixPluginConfig` gắn `vt.io/degrade-managed=true`; bộ plugin từng mức do chart `api-gateway` (DV-01-02) dựng sẵn trong ConfigMap `apisix-degrade-levels`. Thứ hai là tác vụ nền không gấp: `ScaledObject` của KEDA gắn nhãn `vt.io/degrade-pause-from=<mức>` được đặt `autoscaling.keda.sh/paused=true` khi mức đạt ngưỡng đó. Tăng mức thì áp cổng trước rồi tới tác vụ nền; hạ mức thì gỡ theo thứ tự ngược.

Bộ điều khiển thay bản Python `apisix-degrade-controller` của chart `api-gateway` và giữ tương thích API với bản đó (vtctl, Alertmanager không phải đổi). Khi cài chart `deploy/charts/degrade-controller`, đặt `degrade.controller.external=true` ở chart `api-gateway` để chart đó chỉ dựng ConfigMap bộ plugin.

## Quy tắc mức

- Mức mong muốn là mức ghim tay còn hạn nếu có; ngược lại là giá trị lớn nhất giữa mức nền (`BASE_LEVEL`) và mức của các cảnh báo đang cháy. Mức của cảnh báo lấy từ nhãn `degrade_level` của luật, hoặc từ bảng `ALERT_LEVELS` theo `alertname` khi luật không mang nhãn.
- Tăng mức thì áp ngay. Hạ mức chỉ khi mức mong muốn đã thấp hơn mức đang áp liên tục `HOLD_DOWN` (mặc định 10 phút, suy luận); trong thời gian đó cảnh báo cháy lại thì đếm lại từ đầu. Hạ thẳng về mức mong muốn, không đi qua từng bậc.
- Ghim tay luôn có thời hạn (mặc định `PIN_DEFAULT_TTL` 1 giờ, tối đa `PIN_MAX_TTL` 24 giờ), ưu tiên hơn cảnh báo theo cả hai chiều. Hết hạn thì trở về mức tự động; nếu mức tự động thấp hơn thì vẫn chờ chống dao động.
- Cảnh báo cháy không được Alertmanager gửi lại quá `ALERT_STALE_AFTER` (mặc định 1 giờ) thì coi như đã hết, phòng mất thông báo `resolved`.
- Khởi động lại pod thì đọc mức đang ghi trên các `ApisixPluginConfig`, không tụt mức ngay.
- Mức từ 2 trở lên kéo dài quá `ESCALATE_AFTER` (15 phút) thì gửi một thông báo nâng SEV-2 cho mỗi đợt (OPS-13).
- Mỗi lần cụm thật sự đổi mức: một dòng nhật ký JSON `degrade_level_changed` và một thông báo tới `NOTIFY_URL`. Lần ghi lỗi trả 502, được thử lại ở chu kỳ sau; mức cũng được ghi lại mỗi `RECONCILE_INTERVAL` để sửa cấu hình bị ghi đè.

## API

| Phương thức | Đường dẫn | Mô tả |
|---|---|---|
| GET | `/level` | Mức áp dụng, mức mong muốn, mức nền, ghim (`manual`, `pin.until`), cảnh báo đang cháy, `holdDownUntil` |
| POST | `/level` | `{"level": 0..3 \| null, "reason": "...", "ttl": "30m"}`; cần Bearer token. `null` bỏ ghim |
| POST | `/alertmanager` | Webhook Alertmanager; cần Bearer token |
| GET | `/metrics` | `vt_degrade_level`, `vt_degrade_target_level`, `vt_degrade_pinned`, `vt_degrade_level_since_seconds`, `vt_degrade_transitions_total`, `vt_degrade_escalations_total`, `vt_degrade_errors_total` |
| GET | `/healthz` | Sống |

## Biến môi trường

`CELL`, `PORT` (8080), `BASE_LEVEL`, `HOLD_DOWN`, `PIN_DEFAULT_TTL`, `PIN_MAX_TTL`, `ALERT_STALE_AFTER`, `ESCALATE_AFTER`, `RECONCILE_INTERVAL` (1m), `TICK_INTERVAL` (15s), `GATEWAY_NAMESPACES` (`edge`), `JOB_NAMESPACES`, `LEVELS_DIR`, `WEBHOOK_TOKEN_FILE`, `ALERT_LEVELS` (`TenCanhBao=1,...`), `NOTIFY_URL`, `NOTIFY_TOKEN_FILE`, `K8S_API`, `K8S_TOKEN_FILE`, `K8S_CA_FILE`.

## Kiểm thử

```bash
go vet ./... && go test ./...
```

Kiểm thử gồm bảng chuyển mức, chống dao động, ghim tay có hạn, nâng SEV-2, khởi động lại, và đầu cuối HTTP trên API server Kubernetes giả (thứ tự vá cổng và tác vụ nền khi tăng, hạ; không gỡ tạm dừng tay của người khác; 401, 422, 502 và thử lại).
