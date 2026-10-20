# INF-02 · TL-04 ch.2, ch.5 Chart từng service, chart tổng `vt-platform` và values cho ba mô hình phân phối

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-02` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0b |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | INF-01 |
| Truy vết | TL-04 ch.2 (mục 2.1–2.4), ch.5 (mục 5.2–5.4) → SVC-01..SVC-17 → ADR-06 (`crypto.provider`), OPS-02, OPS-12 → SEC-28 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Hiện thực nguyên tắc "một mã nguồn, một bộ Helm chart, ba cách phân phối" của TL-04 chương 2: mỗi service có chart riêng dựa trên `vt-service`, chart cha `vt-platform` gom toàn bộ thành phần của một cell, và ba tệp values `values-saas.yaml`, `values-private.yaml`, `values-airgap.yaml` chỉ khác nhau ở các khoá mà TL-04 liệt kê. Khi xong, `helm template` cho cả ba mô hình chạy sạch, qua Kyverno CLI, và bản dựng cho cell hạng L có số replica, tài nguyên, probe, PDB đúng bảng TL-04 mục 5.2.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` chương "Mô hình triển khai": mục "Nguyên tắc chung: một mã nguồn, một bộ Helm chart, ba cách phân phối" (dòng 91–113), "Mô hình 1: SaaS trên Viettel Cloud" (115), "Mô hình 2" (121), "Mô hình 3" (127–131).
- Cùng tệp, mục "Bảng cấu hình theo service" (851–881), "Helm values mẫu cho mission-core" (883–969), "Helm values mẫu cho scoring-service" (971–1034); mục "Namespace theo miền và vùng bảo mật" (294–331).
- `/mnt/project-files/kien-truc-okr-kpi/src/00-co-so-thiet-ke-chung.md` mục 5 (bảng SVC) để đặt tên chart.
- `deploy/web/` (ảnh SVC-17 hiện có), chart `deploy/charts/vt-service` của INF-01, `docs/quy-dinh/HA-TANG.md`.

## 3. Phạm vi công việc

- Tạo chart cho từng service dùng `vt-service`, đặt tên theo baseline: `api-gateway` (cấu hình APISIX trong cell để INF-08 hoàn thiện), `iam`, `org-service`, `mission-core`, `workflow-service` (hai workload `workflow-api` và `workflow-executor` cùng ảnh), `scoring-service`, `evidence-service` (kèm ClamAV), `ledger-service`, `esign-service`, `document-service` (kèm Gotenberg), `notification-service`, `integration-gateway`, `ai-gateway` (kèm vLLM tuỳ chọn), `analytics` (analytics-api, Cube, Superset), `search` (indexer), `web-frontend`; `tenant-registry` (SVC-03) là chart toàn cục, không nằm trong cell.
- Điền values mặc định cho cell hạng L đúng bảng TL-04 mục 5.2: min/max replica, CPU request/limit, bộ nhớ, runtime, probe, PDB, kiểu phát hành; ví dụ `mission-core` 6/24, CPU 3/4, 8 GiB, Argo Rollouts canary, `maxUnavailable: 1`; `web-frontend` 2/4, CPU 0,25/0,5, 256 MiB, nginx không root, `/healthz`, `minAvailable: 1`.
- Values `mission-core` theo đúng TL-04 mục 5.3: tám pool DataSource theo module (task 8, okr 4, appraisal 4, product 3, config 2, policy 1, dossier 1, oversight 1 = 24), Hikari `connectionTimeoutMs 2000`, bulkhead, `preScaleWindows: deadline-default`, canary `[5, 25, 50, 100]` với `analysisTemplate: slo-api`, đường dẫn bí mật dạng `kv/cells/<cell>/...` (chỉ đường dẫn, không giá trị).
- Tạo chart cha `deploy/charts/vt-platform` khai báo các chart service và chart hạ tầng trong cell (CNPG, Strimzi, Valkey, OpenSearch, ClickHouse, OpenBao, ngăn xếp quan sát) dưới dạng dependency có `condition`, để INF-10, INF-11, INF-13, INF-14 điền nội dung.
- Viết ba tệp `values-saas.yaml`, `values-private.yaml`, `values-airgap.yaml` chỉ khác nhau ở các khoá TL-04 mục 2.1: `global.deploymentModel`, kho đối tượng, `crypto.provider` (`standard`/`standard`/`bcy`), egress (`egress.allowInternet: false` ở airgap), định danh, AI SVC-14, kênh cập nhật, `sizing`; bản airgap tắt VNeID, MySign, LGSP và cờ ứng dụng di động công khai.
- Viết script CI `deploy/charts/vt-platform/ci/kiem-ba-mo-hinh.sh` chạy `helm template` cho ba mô hình rồi đưa đầu ra qua Kyverno CLI với bộ chính sách của `deploy/policies/` (SEC-28); hỏng bản airgap phải làm đỏ build.
- Điểm lệch cần lưu ý: `networkPolicy.egressTo` của mission-core ở TL-04 mục 5.3 có `org-service`, `evidence-service`, `opa-bundle`, trong khi mẫu NetworkPolicy mục 3.6 chỉ có tầng dữ liệu và mục 7.2 nói mission-core không gọi đồng bộ org-service trên đường găng. Chọn theo mục 5.3 (cấu hình cụ thể của service) và ghi câu hỏi vào `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.
- Gợi ý tách: (a) chart nhóm `core`, `identity`, `platform`; (b) chart nhóm `workers`, `analytics`, `secure`; (c) chart cha, ba tệp values và script CI.

## 4. Ngoài phạm vi

- Bốn tệp `sizing/values-sizing-*.yaml` và values từng cell (INF-03); kho GitOps, ApplicationSet (INF-04).
- Nội dung CR của CNPG, Strimzi, OpenBao, quan sát (INF-10, INF-11, INF-13, INF-14); chính sách Kyverno (INF-07, ở đây chỉ gọi bộ chính sách có sẵn).

## 5. Tệp được phép sửa

- `deploy/charts/<ten-dich-vu>/**` cho các service liệt kê ở mục 3, `deploy/charts/vt-platform/**`.
- `deploy/web/**` chỉ khi cần cho ảnh SVC-17 khớp chart (ví dụ thêm `/healthz`), ghi rõ trong báo cáo.
- `wiki/12-ha-tang-dev-va-trien-khai.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 6. Tiêu chí hoàn thành

- `helm lint` mọi chart và `helm dependency build deploy/charts/vt-platform` không lỗi.
- `kiem-ba-mo-hinh.sh` chạy xanh cho cả ba mô hình: `helm template` + `kubeconform -strict` + `kube-linter` + Kyverno CLI (khi INF-07 chưa gộp, chạy với bộ PSS restricted và ghi rõ trong báo cáo).
- Kiểm thử đối chiếu: một bảng sinh tự động từ `helm template` (values L) so với TL-04 mục 5.2 cho từng service, không có dòng lệch ngoài các điểm đã ghi.
- Cài `vt-platform` với các service bật ở chế độ ảnh thử (ví dụ chỉ `web-frontend` và một service mẫu) lên kind: pod `Ready`, probe đạt.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki 12 mô tả cách chồng values.

## 7. Báo cáo

Theo AGENTS.md mục 6.
