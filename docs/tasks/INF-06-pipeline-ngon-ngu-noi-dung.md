# INF-06 · OPS-10, OPS-11 Pipeline cho Go, Python, Flutter, Helm và nội dung (Policy Pack, UI Pack)

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH), `docs/quy-dinh/DI-DONG.md` (task MOB). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-06` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c |
| Luồng đề xuất | B8 |
| Cỡ việc | M (≤ 1 ngày) |
| Phụ thuộc | INF-05 |
| Truy vết | OPS-10 (Policy Pack ≤ 5 ngày), OPS-11 (UI Pack ≤ 1 ngày) → NFR-07, NFR-11 → EVT-13, EVT-23 → SEC-23, SEC-37..SEC-39 → tiêu chí J6, J8 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Mở rộng thư viện `vt-pipeline` của INF-05 cho các ngôn ngữ và loại sản phẩm còn lại của hệ thống: microservice Go (SVC-03, SVC-09, SVC-12), Python (SVC-14, job SVC-15), ứng dụng Flutter (SVC-18), Helm chart trong `deploy/`, và hai pipeline nội dung không phát hành lại mã là Policy Pack (OPS-10) và UI Pack (OPS-11). Khi xong, mỗi loại có một bước pipeline dùng chung với cùng cổng chặn (bí mật, SAST, lỗ hổng, kiểm thử, SBOM, ký), và pipeline UI Pack chạy đủ các kiểm tra tự động trong một lượt.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Phát hành Policy Pack, UI Pack và bản LTS" (3826–3833), "Chuỗi cung ứng phần mềm: SLSA, Jenkins, Argo CD" (3022–3138).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` mục "Đường ống CI/CD" (3130–3138); chương Policy Pack của TL-01 (ca vàng, J6).
- `wiki/03-giao-dien-theo-tenant.md` (UI Pack, ngưỡng 30 KB), `wiki/11-kiem-thu-va-chat-luong.md` mục "Kiểm thử UI Pack bốn tầng", `docs/quy-dinh/DI-DONG.md`, `docs/quy-dinh/BACKEND.md` mục 4.
- `contracts/openapi/modules/policies.yaml` (`runPolicyTests`, `submitPolicyPackVersion`) và `contracts/openapi/modules/config.yaml` (`createUiPackVersion`, `publishUiPackVersion`).

## 3. Phạm vi công việc

- Bước `vtGoPipeline`: gitleaks, Semgrep (`p/golang`), `go vet ./...`, `go test -race ./...` có độ phủ, `govulncheck ./...`, dựng ảnh distroless static theo digest, SBOM, Trivy ảnh, ký cosign và provenance như INF-05.
- Bước `vtPythonPipeline`: gitleaks, Semgrep (`p/python`), `pytest` có độ phủ, `pip-audit`, ảnh không root, SBOM, Trivy, ký.
- Bước `vtFlutterPipeline`: `flutter analyze`, `flutter test`, gitleaks, quét phụ thuộc; dựng bản phát hành Android và iOS chỉ ở nhánh main (ký ứng dụng dùng credential Jenkins, không ghi giá trị).
- Bước `vtHelmPipeline` cho `deploy/`: `helm lint`, `helm template` ba mô hình phân phối, `kubeconform -strict`, `kube-linter`, Kyverno CLI với `deploy/policies/`, kiểm ngân sách kết nối của INF-03.
- Pipeline Policy Pack theo OPS-10: kiểm cú pháp quy tắc, kiểm thử đơn vị quy tắc, bộ ca vàng phải đạt 100% (J6), bước so sánh bóng trên dữ liệu đã che của kỳ trước xuất báo cáo chênh lệch theo tenant; phát hành chỉ sau khi có người duyệt, phát EVT-13 qua API chứ không do pipeline ghi CSDL.
- Pipeline UI Pack theo OPS-11: kiểm lược đồ token, SDUI, biểu mẫu bằng validator không sinh mã lúc chạy (AGENTS.md quy tắc 5), kiểm kích thước CSS biên dịch ≤ 30 KB gzip, axe-core WCAG 2.1 AA qua Playwright trên trang mẫu, ảnh chụp so sánh trên staging; phát hành qua `publishUiPackVersion` để máy chủ phát EVT-23.
- Đặt bước Jenkinsfile mẫu cho từng loại trong `ci/vt-pipeline/examples/` để các task DV, MOB dùng lại.

## 4. Ngoài phạm vi

- Pipeline Java (INF-05), pipeline frontend Angular (WEB-002).
- Máy chủ soạn Policy Pack và UI Pack (task CORE MOD-05, MOD-08); công cụ shadow chạy trên dữ liệu thật đã che (cần dữ liệu từ DL, chỉ nối bước gọi).

## 5. Tệp được phép sửa

- `ci/vt-pipeline/**`, `services-go/*/Jenkinsfile`, `services-py/*/Jenkinsfile`, `mobile/Jenkinsfile`, `deploy/Jenkinsfile`, `data/policy-pack/Jenkinsfile`.
- `.semgrep/**` (thêm bộ quy tắc Go, Python, Dart nếu cần).
- `wiki/11-kiem-thu-va-chat-luong.md`.

## 6. Tiêu chí hoàn thành

- Mỗi bước mới chạy được trên Jenkins LTS cục bộ (Docker) với một dự án mẫu tối thiểu cùng loại; ghi thời gian và kết quả từng stage.
- Mỗi pipeline có ít nhất một merge request thử bị chặn đúng cổng (ví dụ: Go có `govulncheck` báo lỗi, Helm có manifest chạy root, UI Pack có CSS 31 KB, Policy Pack có một ca vàng sai).
- Pipeline UI Pack chạy trọn trong một lượt dưới 60 phút trên dữ liệu mẫu (đảm bảo phát hành ≤ 1 ngày của OPS-11).
- Báo cáo cuối task theo AGENTS.md mục 6; wiki 11 liệt kê đủ các pipeline và cổng.

## 7. Báo cáo

Theo AGENTS.md mục 6.
