# INF-05 · ADR-16, SEC-37..SEC-39 Thư viện pipeline Jenkins `vt-pipeline` cho Java

> ### 0. QUY ĐỊNH CHUNG – BẮT BUỘC ĐỌC TRƯỚC KHI LÀM
> Trước khi đọc tiếp, mở và đọc toàn bộ `AGENTS.md` ở gốc repo (quy tắc không được vi phạm, quy trình, mẫu báo cáo), rồi đọc quy định riêng của phân hệ: `docs/quy-dinh/BACKEND.md` (task NEN, CORE, DV, DL), `docs/quy-dinh/HA-TANG.md` (task INF, VH). Nhắc nhanh:
> bám baseline và TL-01..TL-05, giữ nguyên mã định danh, tên schema, bảng, trạng thái; API-first, sửa mã chứ không sửa hợp đồng; chỉ sửa tệp trong phạm vi;
> không commit bí mật; máy chủ là nguồn sự thật, quyền theo ma trận `wiki/15a`; ghi kèm Idempotency-Key, cập nhật kèm If-Match (409); outbox trong cùng giao dịch;
> build + kiểm thử thật trước khi báo xong; báo cáo trung thực theo AGENTS.md mục 6. Chạy `./scripts/agent-task.sh INF-05` để có prompt đầy đủ.

| Thuộc tính | Giá trị |
|---|---|
| Giai đoạn | Đợt 0c |
| Luồng đề xuất | B8 |
| Cỡ việc | L (> 1 ngày, nên tách) |
| Phụ thuộc | NEN-005 |
| Truy vết | ADR-16 → TL-01 ch.18 "Đường ống CI/CD" → TL-04 mục 9.6 → SEC-09, SEC-23, SEC-37, SEC-38, SEC-39, SEC-55 → tiêu chí J1–J3, J8, C10 |
| Trạng thái | Chưa bắt đầu |

## 1. Mục tiêu

Có thư viện dùng chung Jenkins `vt-pipeline` và Jenkinsfile cho `backend/` thực hiện đủ cổng chất lượng của TL-01 chương 18 và TL-04 mục 9.6: build, kiểm thử đơn vị và tích hợp, ArchUnit, độ phủ, Sonar, Semgrep, gitleaks, Trivy, `openapi-diff`, Pact, SBOM, dựng ảnh, ký cosign và provenance SLSA mức 3. Khi xong, bốn merge request cố ý sai (test lỗi, bí mật giả, CVE Critical, phá hợp đồng API) đều bị chặn, đúng tiêu chí đợt 0c của TL-04.

## 2. Đầu vào bắt buộc đọc

- `/mnt/project-files/kien-truc-okr-kpi/src/TL-04-trien-khai-van-hanh.md` mục "Chuỗi cung ứng phần mềm: SLSA, Jenkins, Argo CD" (3022–3138, Jenkinsfile mẫu 3028–3110) và "Đợt 0c: chịu lỗi và cổng chất lượng (6 tuần)" (3915–3927).
- `/mnt/project-files/kien-truc-okr-kpi/src/TL-01-thiet-ke-kien-truc.md` chương "Kiểm thử và tích hợp, triển khai liên tục": "Tháp kiểm thử" (3124) và "Đường ống CI/CD" (3130–3138).
- `docs/quy-dinh/BACKEND.md` mục 4, `docs/tasks/WEB-002-pipeline-jenkins.md` (pipeline frontend để thống nhất stage), `wiki/11-kiem-thu-va-chat-luong.md` mục "Cổng chất lượng trong pipeline".

## 3. Phạm vi công việc

- Tạo thư viện `ci/vt-pipeline` (cấu trúc Jenkins shared library `vars/`, `src/`, `resources/`; vị trí suy luận) với bước `vtJavaPipeline` và `backend/Jenkinsfile` gọi nó; agent `vt-build-ephemeral`, timeout 45 phút, `disableConcurrentBuilds(abortPrevious: true)`, giữ 50 bản.
- Stage song song đầu tiên: `gitleaks git --redact --exit-code 1` trên dải commit của merge request (SEC-09); `semgrep scan --config p/java --config .semgrep/vt-rules.yml --error --sarif` (SEC-39); `trivy fs --scanners vuln,misconfig,license --severity HIGH,CRITICAL --exit-code 1`.
- Viết `.semgrep/vt-rules.yml` có quy tắc `vt-sql-string-concat` (CWE-89, SEC-05), cấm ghi log thân request và token (SEC-07), cấm thuật toán ngoài danh sách SEC-20 (DES, MD5, SHA-1 ký, TrustManager tin mọi chứng chỉ).
- Stage build và kiểm thử: `./mvnw -B -ntp verify -Pci` (đơn vị, Testcontainers, ArchUnit, Modulith `verify()`, JaCoCo) với `recordCoverage` ngưỡng 80% dòng trên tệp thay đổi (J1, J3); SonarQube quality gate; `openapi-diff` so `contracts/openapi` của nhánh với `main` (ADR-32); bước `pact can-i-deploy` để sẵn, bật khi QA-02 xong.
- Stage ảnh: dựng ảnh bằng Jib trên nền JRE 21 distroless theo digest (SEC-49, SEC-55), sinh SBOM CycloneDX bằng syft, `trivy image --ignore-unfixed --exit-code 1`, đẩy SBOM vào Dependency-Track (SEC-23).
- Stage nhánh `main`: `cosign sign` và `cosign attest` với khoá `hashivault://cosign-release`, provenance `slsaprovenance1` sinh trong container riêng (SEC-37, SEC-38), rồi commit có chữ ký cập nhật digest vào `deploy/environments/staging/digests.yaml` (bước `vtGitopsBump`).
- Liệt kê biến và credential Jenkins cần có trong `ci/vt-pipeline/README.md` (chỉ tên, không giá trị).
- Điểm lệch cần lưu ý: TL-01 chương 18 dựng ảnh Java bằng Jib, Jenkinsfile mẫu TL-04 dùng BuildKit không root. Chọn Jib (khớp NEN-005 đã cấu hình Jib trong POM, ảnh vẫn distroless theo digest) và ghi vào `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.
- Gợi ý tách: (a) stage quét và Semgrep rules; (b) build, kiểm thử, độ phủ, Sonar, `openapi-diff`; (c) ảnh, SBOM, ký, provenance, bump GitOps.

## 4. Ngoài phạm vi

- Pipeline cho Go, Python, Flutter, Helm, Policy Pack, UI Pack (INF-06); pipeline frontend (WEB-002).
- Dựng máy chủ Jenkins, SonarQube, Dependency-Track production (hạ tầng cụm quản trị); Pact Broker (QA-02).

## 5. Tệp được phép sửa

- `ci/vt-pipeline/**` (thư mục mới, suy luận), `backend/Jenkinsfile`, `.semgrep/**`, `.gitleaks.toml`.
- `deploy/environments/staging/digests.yaml` (chỉ cấu trúc ban đầu).
- `wiki/11-kiem-thu-va-chat-luong.md`, `wiki/14-quyet-dinh-va-cau-hoi-mo.md`.

## 6. Tiêu chí hoàn thành

- Jenkinsfile và thư viện qua kiểm cú pháp (`jenkins-cli declarative-linter` hoặc chạy trên Jenkins LTS cục bộ trong Docker); pipeline chạy hết trên `backend/` thật của nhánh, ghi thời gian từng stage.
- Bốn merge request thử (test lỗi, chuỗi bí mật giả, phụ thuộc có CVE Critical, xoá một thao tác trong hợp đồng OpenAPI) bị chặn 4/4; kết quả kèm trong báo cáo.
- `semgrep --test` cho `.semgrep/vt-rules.yml` có ca dương và ca âm cho từng quy tắc.
- Trên nhánh main thử nghiệm: ảnh có chữ ký và attestation kiểm được bằng `cosign verify` và `cosign verify-attestation --type slsaprovenance1`.
- Báo cáo cuối task theo AGENTS.md mục 6; wiki 11 có mục mô tả pipeline backend.

## 7. Báo cáo

Theo AGENTS.md mục 6.
