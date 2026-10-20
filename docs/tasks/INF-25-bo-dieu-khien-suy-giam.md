# INF-25 · TL-04 cắt tải Bộ điều khiển suy giảm ba mức khi quá tải

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-25` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 2 (trước mốc chốt kỳ đầu tiên có tải thật) |
| Luồng đề xuất | B8 |
| Cỡ việc | M |
| Phụ thuộc | DV-01-02 (cấu hình plugin APISIX theo mức), INF-15 (luật cảnh báo, Alertmanager); INF-04 (Argo CD, `ignoreDifferences`), INF-16 (KEDA), NEN-017 (thư viện Go) (bổ sung) |
| Truy vết | TL-04 "Hành vi khi bão hoà" (L1649–1662: ba mức suy giảm, nhãn `vt.io/degrade-level` trên `ApisixPluginConfig`, webhook từ Alertmanager, bật tắt tay theo OPS-14) → tiêu chí F4, CWE-400 → OPS-13 (mức 2 quá 15 phút nâng SEV-2), OPS-14 → SLO-AVL |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Khi tải vượt năng lực, hệ thống phải suy giảm có thứ tự, giữ thao tác quan trọng nhất và từ chối có kiểm soát phần còn lại, thay vì chậm đều rồi sập. TL-04 định nghĩa ba mức kích hoạt bằng nhãn trên `ApisixPluginConfig` do một bộ điều khiển suy giảm đặt khi nhận webhook của Alertmanager hoặc khi người trực bật tay. Task này viết bộ điều khiển đó cùng các tác động ngoài cổng (tắt trợ lý AI, tạm dừng tác vụ nền không gấp), có nhật ký và thông báo mỗi lần chuyển mức. Khi xong, trong thử tải PERF quá ngưỡng, hệ thống tự lên mức 1, 2, 3 đúng điều kiện và tự hạ mức khi chỉ số về ngưỡng, không dao động.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` "Cắt tải và giới hạn tốc độ ở APISIX" (L1579–1648), "Hành vi khi bão hoà" (L1649–1662: bảng bốn mức 0–3, điều kiện, hành động, chức năng còn giữ; mã 429/503, `Retry-After`), "SLO và ngân sách lỗi" (L1666–1690), OPS-13, OPS-14 (L3834–3847).
- `docs/quy-dinh/HA-TANG.md` (GitOps, không `kubectl apply` tay lên môi trường dùng chung).
- Thẻ `docs/tasks/DV-01-02-gioi-han-toc-do-waf.md` (plugin theo mức), `INF-15-slo-canh-bao-dashboard.md` (cảnh báo), `INF-16-co-gian-tu-dong.md` (ghi chú đề xuất hàng này), `VH-02-runbook-ops-14-26.md`.

## 3. Phạm vi công việc

- Viết `services-go/degrade-controller/`: nhận webhook Alertmanager (xác thực bằng mTLS hoặc token chia sẻ từ OpenBao), ánh xạ cảnh báo sang mức theo bảng TL-04 (mức 1: mission-core ở `maxReplicas` quá 5 phút và p95 đọc > 300 ms; mức 2: 5xx > 1% hoặc Hikari pending > 0 quá 2 phút; mức 3: CPU primary CSDL > 90% quá 2 phút); mức của cell là mức cao nhất trong các cảnh báo đang bắn.
- Áp mức bằng cách đặt nhãn `vt.io/degrade-level` trên `ApisixPluginConfig` của cell (plugin từng mức do DV-01-02 khai báo); khai báo `ignoreDifferences` cho riêng nhãn này trong Argo CD để GitOps không ghi đè, và ghi lý do vào `wiki/12` (ngoại lệ có kiểm soát của nguyên tắc GitOps).
- Tác động ngoài cổng: mức 1 bật cờ tắt trợ lý AI và tìm kiếm rút gọn (qua cờ tính năng nền tảng hoặc cấu hình ai-gateway, chọn theo CORE-08-07), mức 2 tạm dừng tác vụ nền không gấp (đặt `autoscaling.keda.sh/paused` trên ScaledObject được gắn nhãn "không gấp", suy luận); hạ mức thì gỡ theo thứ tự ngược.
- Chống dao động: chỉ hạ mức khi điều kiện đã hết ít nhất 10 phút (suy luận, tham số hoá); chế độ tay của người trực (API nội bộ hoặc `vtctl degrade set`) ghim mức và có thời hạn, ưu tiên hơn tự động.
- Mỗi lần chuyển mức: ghi nhật ký vận hành, metric `vt_degrade_level`, gửi thông báo kênh trực; mức ≥ 2 kéo dài quá 15 phút thì phát cảnh báo nâng SEV-2 (OPS-13).
- Chart `deploy/charts/degrade-controller/`: không root, RBAC chỉ `patch` nhãn trên `ApisixPluginConfig` và ScaledObject trong namespace cho phép, NetworkPolicy; runbook bật, tắt, ghim mức.
- Điểm lệch cần lưu ý: DV-01-02 hiện ghi cả "bộ điều khiển suy giảm ba mức ... kèm webhook"; phân định: DV-01-02 giữ cấu hình plugin APISIX của từng mức, INF-25 giữ bộ điều khiển và tác động ngoài cổng; ghi vào báo cáo để điều chỉnh thẻ DV-01-02.

## 4. Ngoài phạm vi

- Giới hạn tốc độ ba tầng, WAF, nội dung plugin từng mức (DV-01-02); luật cảnh báo và dashboard (INF-15); hiển thị thông báo xếp hàng ở frontend (WEB).

## 5. Tệp được phép sửa

- `services-go/degrade-controller/**` (mới)
- `deploy/charts/degrade-controller/**` (mới), `deploy/argocd/**` (chỉ `ignoreDifferences` cho nhãn mức suy giảm), `deploy/environments/kind/**`
- `ops/runbooks/suy-giam-ba-muc.md`
- `wiki/12-ha-tang-dev-va-trien-khai.md`

## 6. Tiêu chí hoàn thành

- `cd services-go/degrade-controller && go vet ./... && go test ./...` xanh: bảng chuyển mức, chống dao động, ghim tay, thứ tự gỡ tác động.
- `helm lint`, `kubeconform -strict` sạch; trên kind với APISIX: gửi webhook giả mức 3 thì request ngoài danh sách ưu tiên nhận 503 kèm `Retry-After: 60`, request nộp hồ sơ vẫn qua; hết cảnh báo 10 phút thì về mức 0.
- Argo CD đồng bộ lại không xoá nhãn mức; runbook có bước kiểm chứng và quay lui; báo cáo cuối task theo AGENTS.md mục 6.

## 7. Báo cáo

Theo AGENTS.md mục 6.
