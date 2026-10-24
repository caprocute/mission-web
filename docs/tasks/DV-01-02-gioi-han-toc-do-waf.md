# DV-01-02 · SVC-01 Giới hạn tốc độ ba tầng, bảo vệ API, cắt tải và WAF Coraza

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh DV-01-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 1 |
| Luồng đề xuất | B5 |
| Cỡ việc | M |
| Phụ thuộc | DV-01-01; Valkey cluster của cell (INF-11) |
| Truy vết | SVC-01 → NFR-02, NFR-03; TL-01 chương "Độ tin cậy" mục "Giới hạn tốc độ và cắt tải"; TL-04 WI-35, WI-36 (OPS-13, OPS-14); bảng `registry.tenant_quota` (hạn mức theo tenant) |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Bảo vệ cell khỏi quá tải và tấn công tầng ứng dụng ngay tại cổng: giới hạn theo người dùng, theo tenant và theo tuyến tốn kém; cắt tải theo lớp ưu tiên khi upstream chậm; WAF Coraza với OWASP CRS 4 chặn tấn công phổ biến. Khi xong, vượt hạn mức trả 429 dạng RFC 9457 kèm `Retry-After`, cell quá tải từ chối lớp 3 (dashboard, tìm kiếm, xuất) trước khi chạm tới đăng nhập, ký và nộp hồ sơ, và bộ điều khiển suy giảm ba mức có thể bật bằng nhãn.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "SVC-01 api-gateway" (dòng 954–972: `limit-count`, `limit-req`, Coraza) và mục "Giới hạn tốc độ và cắt tải" (dòng 2882, có trích `ApisixRoute` xuất báo cáo lớp ưu tiên 3).
- Cùng tệp, mục "Cô lập tài nguyên và giới hạn theo tenant" (dòng 2726) cho hạn mức theo tenant.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Cắt tải và giới hạn tốc độ ở APISIX" (dòng 1579–1662): `ApisixPluginConfig protected-api`, bộ điều khiển suy giảm ba mức.
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-03-thiet-ke-csdl.md` bảng `registry.tenant_quota` (dòng 1031–1044).
- Hợp đồng: `contracts/openapi/common/common.yaml` (phản hồi 429, 503 và header `Retry-After`).
- Wiki: `wiki/06-quy-uoc-api.md`, `wiki/09-hieu-nang-va-trai-nghiem.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 3. Phạm vi công việc

- Cấu hình `limit-count` khoá `$vt_tenant $vt_sub` với `policy: redis-cluster` trỏ Valkey (TLS, kiểm chứng chỉ), `show_limit_quota_header`, `allow_degradation: true`; thân 429 là Problem Details và `response-rewrite` đặt `Retry-After`, `Content-Type: application/problem+json`.
- Cấu hình `limit-req` theo tenant (rate, burst, `nodelay`) và cho phép ghi đè theo tenant bằng values sinh từ `registry.tenant_quota` (2 rps/100 tài khoản, tối thiểu 20 rps, đột biến ×3 trong 10 s theo TL-01 mục 12.5).
- Tạo tầng thứ ba theo tuyến: tuyến tốn kém (xuất Excel, gói hồ sơ, AI, tìm kiếm toàn văn) có hạn mức thấp hơn; tuyến tải lên `/files/uploads/*` đặt `client-control.max_body_size` theo giới hạn phần tải lên.
- Gắn lớp ưu tiên 1/2/3 cho mọi route của DV-01-01 bằng nhãn và cấu hình `limit-conn` theo lớp với ngưỡng giảm dần để lớp 3 bị từ chối trước.
- Hiện thực bộ điều khiển suy giảm ba mức bằng nhãn `vt.io/degrade-level` trên `ApisixPluginConfig` (mức 1 tắt AI và rút gọn tìm kiếm; mức 2 giới hạn tenant giảm 30%; mức 3 chỉ nhận ghi ưu tiên, còn lại 503 + `Retry-After: 60`), kèm webhook nhận từ Alertmanager.
  - Phân định với INF-25 (ghi thêm ngày 23/10/2026): DV-01-02 giữ bộ plugin từng mức (ConfigMap `apisix-degrade-levels` của chart `api-gateway`) và bộ điều khiển Python chạy ở chế độ standalone; INF-25 giữ bộ điều khiển Go `services-go/degrade-controller`, luật kích hoạt ba mức, đường Alertmanager trong cell và tác động ngoài cổng (tạm dừng tác vụ nền).
- Bật Coraza (coraza-proxy-wasm) với OWASP CRS 4, chạy chế độ phát hiện ở dev và chế độ chặn ở staging trở lên; ghi danh sách loại trừ tối thiểu cho payload JSON hợp lệ của hợp đồng.
- Điểm lệch cần lưu ý: TL-01 ghi giới hạn người dùng 20 request/giây (đột biến 40) và "60 ghi/phút/người", TL-04 ghi `limit-count` 600/60 s theo tenant + người dùng và `limit-req` 400/burst 200 theo tenant; chọn số của TL-04 (tài liệu triển khai mới hơn) làm mặc định, để mọi ngưỡng là giá trị Helm và ghi lựa chọn vào wiki trang 14.

## 4. Ngoài phạm vi

- Bộ lọc trả 503 sớm trong Spring Boot (thuộc thư viện nền NEN).
- Phía web tôn trọng 429/503 (`@vt/api-client`, task WEB tương ứng); cảnh báo Alertmanager (INF-15).

## 5. Tệp được phép sửa

- `deploy/charts/api-gateway/**`
- `deploy/environments/*/api-gateway/**`
- `ops/runbooks/` (chỉ thêm mục bật, tắt mức suy giảm theo OPS-14)
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`

## 6. Tiêu chí hoàn thành

- `helm lint` và `helm template | kubeconform -strict` không lỗi.
- Kiểm thử tải nhỏ bằng k6 trên APISIX cục bộ: vượt hạn mức người dùng trả 429 Problem Details có `Retry-After`; khi Valkey ngừng, request vẫn đi qua (`allow_degradation`).
- Kiểm thử WAF: bộ payload OWASP mẫu (SQLi, XSS) bị chặn ở chế độ chặn; các ví dụ request trong hợp đồng OpenAPI không bị chặn nhầm.
- Kịch bản đặt nhãn mức 3 làm route lớp 3 trả 503 + `Retry-After: 60`, route lớp 1 vẫn qua.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki được cập nhật nếu có tri thức mới.

## 7. Báo cáo

Theo AGENTS.md mục 6.
