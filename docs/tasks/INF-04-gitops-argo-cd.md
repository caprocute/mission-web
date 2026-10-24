# INF-04 · OPS-01, OPS-02, OPS-03 Kho GitOps: Argo CD ApplicationSet theo cell và môi trường, sync window, Argo Rollouts canary có phân tích SLI

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-04` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b (cụm quản trị, OPS-01); canary theo đợt cell hoàn thiện ở đợt 2 |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-02 |
| Truy vết | ADR-16 → OPS-01, OPS-02, OPS-03, OPS-05, OPS-06 → TL-04 ch.11 mục "GitOps và phát hành theo đợt cell", "Đóng băng thay đổi và hội đồng thay đổi"; TL-04 mục 9.6 (AppProject) → MOD-08 bảng `config.period` |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Dựng kho GitOps trong `deploy/` theo cấu trúc OPS-01 và cấu hình Argo CD để mọi thay đổi cụm đi qua Git: ApplicationSet sinh một Application cho mỗi cặp cell × TTDL, phát hành theo bốn đợt cell bằng `RollingSync`, khoá đồng bộ trong cửa sổ đóng băng trước mốc hạn, và Argo Rollouts canary 5% → 25% → 50% → 100% tự quay lui khi vi phạm SLI. Khi xong, trên cụm kind có Argo CD, một thay đổi digest ảnh được phát hành lần lượt qua hai cell giả lập, và một bản canary cố ý tăng lỗi bị huỷ tự động.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` chương "Quy trình vận hành": "Danh mục quy trình OPS" (3583–3614), "GitOps và phát hành theo đợt cell" (3616–3761, gồm ApplicationSet `okr-mission-core`, AnalysisTemplate `slo-api`, Rollout `mission-core`), "Đóng băng thay đổi và hội đồng thay đổi" (3763–3791).
- Cùng tệp mục "Chuỗi cung ứng phần mềm: SLSA, Jenkins, Argo CD" (3022–3138, phần AppProject 3114–3138) và "Phiên bản Kubernetes và chính sách nâng cấp" (197–218: Argo CD 3.1, Argo Rollouts 1.8).
- `contracts/openapi/modules/config.yaml` thao tác `listPeriods` (`GET /periods`) dùng để sinh lịch đóng băng.
- `wiki/12-ha-tang-dev-va-trien-khai.md` mục Kubernetes, `docs/quy-dinh/HA-TANG.md`.

## 3. Phạm vi công việc

- Đặt cấu trúc kho GitOps trong repo (ánh xạ suy luận từ OPS-01): `deploy/clusters/<cụm>/` cho cấu hình riêng của cụm, `deploy/cells/<cell>/cell.yaml` (hạng, TTDL chính, tenant silo, nhãn `wave`), `deploy/charts/` thay cho `apps/`, `deploy/environments/<môi trường>/digests.yaml` chứa digest ảnh; ghi bảng ánh xạ vào wiki 12.
- Viết ApplicationSet cho từng service theo mẫu `okr-mission-core`: `goTemplate`, `missingkey=error`, ma trận `cells/*/cell.yaml` × `{hl, sn}`, `RollingSync` bốn bước theo nhãn `wave` (bước 2 `maxUpdate: 50%`), valueFiles theo thứ tự mô hình → hồ sơ → cell → digest, `ServerSideApply`, `PruneLast`, retry 3 lần; có ApplicationSet riêng cho `web-frontend` (SVC-17).
- Khai báo đợt phát hành OPS-02: đợt 0 CELL-NB, đợt 1 CELL-G4, đợt 2 CELL-G1..G3 và CELL-SH, đợt 3 CELL-HN và CELL-DTW, đợt 4 CELL-HCM.
- Viết `AppProject` an ninh theo TL-04 mục 9.6: `signatureKeys`, `sourceRepos` chỉ kho GitOps, đích `app-*`, `clusterResourceWhitelist: []`, cấm ResourceQuota và RBAC; chỉ vai trò `release-manager` được sync.
- Viết `syncWindows` đóng băng OPS-06 (deny `"0 0 24 3,6,9,12 *"` 408 giờ, deny `"0 0 20 1 *"` 384 giờ, `manualSync: true`, múi giờ `Asia/Ho_Chi_Minh`) và một script sinh cửa sổ bổ sung từ kỳ đánh giá đọc qua `GET /api/v1/periods`, mở merge request cho người trực duyệt (không tự gộp).
- Viết `AnalysisTemplate slo-api` (lỗi < 0,002; p95 đọc < 0,3 s; p95 ghi < 0,8 s; interval 2m, count 15) và cấu hình Rollout canary `[5, 25, 50, 100]` có `abortScaleDownDelaySeconds 600`, định tuyến lưu lượng qua APISIX (OPS-03, OPS-05).
- Gợi ý tách: (a) cấu trúc kho + ApplicationSet + AppProject; (b) sync window + script sinh lịch; (c) Rollouts + AnalysisTemplate + thử canary.

## 4. Ngoài phạm vi

- Cài Argo CD vào cụm quản trị production (phụ thuộc cụm thật, INF-08, INF-20); Harbor và Kyverno (INF-07).
- KEDA cron tăng trước mốc hạn (INF-16); quy trình CAB OPS-19 (VH-03).

## 5. Tệp được phép sửa

- `deploy/argocd/**` (ApplicationSet, AppProject, AnalysisTemplate; thư mục mới, suy luận), `deploy/clusters/**`, `deploy/cells/*/cell.yaml`, `deploy/environments/**`.
- `deploy/charts/mission-core/**`, `deploy/charts/web-frontend/**` chỉ phần Rollout và canary.
- `wiki/12-ha-tang-dev-va-trien-khai.md`.

## 6. Tiêu chí hoàn thành

- `kubeconform -strict` (kèm schema CRD Argo CD, Argo Rollouts) và `argocd appset generate` (hoặc kiểm tra tương đương) cho ra đúng số Application = số cell × 2 TTDL × số service bật.
- Trên kind có Argo CD 3.1 và Argo Rollouts 1.8: phát hành một digest mới qua hai cell giả lập theo đúng thứ tự `wave`; trong cửa sổ đóng băng, sync tự động bị từ chối.
- Canary với ảnh thử trả 5% lỗi bị AnalysisTemplate đánh dấu thất bại và Rollout quay về bản ổn định; ghi lại nhật ký trong báo cáo.
- Script sinh cửa sổ có kiểm thử đơn vị với dữ liệu kỳ giả lập theo lược đồ của `listPeriods`.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki 12 cập nhật cấu trúc kho GitOps thật.

## 7. Báo cáo

Theo AGENTS.md mục 6.
