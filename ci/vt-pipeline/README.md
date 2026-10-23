# vt-pipeline: thư viện pipeline Jenkins dùng chung (INF-05)

> Nguồn: ADR-16; TL-01 ch.18 "Kiểm thử và tích hợp, triển khai liên tục" (Tháp kiểm thử, Đường ống CI/CD);
> TL-04 mục 9.6 "Chuỗi cung ứng phần mềm: SLSA, Jenkins, Argo CD" (Jenkinsfile mẫu) và "Đợt 0c";
> SEC-05, SEC-07, SEC-09, SEC-20, SEC-23, SEC-37, SEC-38, SEC-39, SEC-49, SEC-55.

Thư viện dùng chung Jenkins (shared library) cho các service Java của Mission. Pipeline Go, Python, Flutter,
Helm, Policy Pack, UI Pack thuộc INF-06; pipeline frontend thuộc WEB-002.

## Cấu trúc

```
ci/vt-pipeline/
├── vars/
│   ├── vtJavaPipeline.groovy   pipeline declarative đầy đủ cho service Java (Maven)
│   ├── vtGoPipeline.groovy     service Go (INF-06)
│   ├── vtPythonPipeline.groovy service Python (INF-06)
│   ├── vtFlutterPipeline.groovy ứng dụng di động Flutter (INF-06)
│   ├── vtHelmPipeline.groovy   chart Helm và chính sách Kyverno (INF-06)
│   ├── vtPolicyPackPipeline.groovy gói chính sách chấm điểm (INF-06, QT-17)
│   ├── vtUiPackPipeline.groovy gói giao diện tenant (INF-06, ADR-05)
│   ├── vtBuoc.groovy           các bước dùng chung của sáu pipeline INF-06
│   ├── vtGitopsBump.groovy     commit có chữ ký GPG cập nhật digest vào deploy/environments/<env>/digests.yaml
│   └── vtNotify.groovy         báo kết quả cho kênh của đội (webhook)
├── src/vn/vt/pipeline/
│   ├── CauHinh.groovy          tham số, giá trị mặc định, biến môi trường VT_* cấp controller
│   ├── CauHinhLoai.groovy      mặc định và kiểm tham số theo loại cho các pipeline INF-06
│   └── DigestsFile.groovy      sửa digests.yaml theo dòng, giữ chú thích
├── resources/vn/vt/pipeline/
│   ├── openapi-diff.sh         so hợp đồng contracts/openapi từng module với nhánh đích (ADR-32)
│   ├── vt-provenance.py        bản tham chiếu công cụ sinh predicate SLSA Provenance v1
│   ├── vt-api.py               gọi API theo quy ước baseline mục 13 (Idempotency-Key, If-Match, RFC 9457, 202)
│   ├── vt-policy-pack.py       liệt kê, kiểm chữ ký nghiệp vụ, đóng gói tất định Policy Pack
│   ├── vt-ui-pack.py           kiểm lược đồ, registry, an toàn, chế độ cao tuổi, ngân sách CSS của UI Pack
│   ├── vt-ui-pack-axe.mjs      axe-core trên trang mẫu, chế độ thường và cao tuổi
│   └── vt-ui-pack-anh.mjs      ảnh chụp trước, sau và khác biệt trên staging
└── tests/                      kiểm thử thư viện (xem mục "Kiểm thử")
```

Quy tắc Semgrep riêng nằm ở `.semgrep/vt-rules.yml` (ca kiểm thử `.semgrep/vt-rules.java`), cấu hình
gitleaks ở `.gitleaks.toml`, Jenkinsfile của backend ở `backend/Jenkinsfile`.

## Cách dùng

```groovy
@Library('vt-pipeline@vt-pipeline-v1.0.0') _
vtJavaPipeline(service: 'mission-core', dir: 'backend')
```

Job là Multibranch Pipeline trên repo Mission, Script Path `backend/Jenkinsfile`. Thẻ `vt-pipeline-v1.0.0`
được tạo khi gộp INF-05; mỗi thay đổi thư viện phát hành thẻ mới, Jenkinsfile ghim thẻ (không dùng `main`).

## Các stage

| Stage | Lệnh chính | Chặn khi | Mã |
|---|---|---|---|
| Bí mật và SAST › gitleaks | `gitleaks git --redact --exit-code 1 --log-opts="origin/<đích>..HEAD"` | có bí mật trong commit của MR | SEC-09 |
| Bí mật và SAST › Semgrep | `semgrep scan --config p/java --config .semgrep/vt-rules.yml --error --sarif` | có phát hiện | SEC-39, SEC-05, SEC-07, SEC-20 |
| Bí mật và SAST › Trivy fs | `trivy fs --scanners vuln,misconfig,license --severity HIGH,CRITICAL --exit-code 1` | lỗ hổng, cấu hình sai, giấy phép HIGH/CRITICAL | SEC-23 |
| Hợp đồng API › openapi-diff | `resources/.../openapi-diff.sh origin/<đích> contracts/openapi` | thay đổi phá vỡ tương thích, xoá tệp module | ADR-32 |
| Hợp đồng API › Pact can-i-deploy | `pact-broker can-i-deploy` | tắt mặc định; QA-02 chạy Pact bằng tệp trong repo (không dựng Broker), bên cung cấp xác minh bằng các lớp `*PactIT` trong `verify -Pci`, chỉ bật `pact: true` khi có Broker | TL-01 ch.18 |
| Dựng và kiểm thử | `./mvnw -B -ntp verify -Pci`; `junit`; `recordCoverage` LINE ≥ 80% trên tệp thay đổi | kiểm thử lỗi, ArchUnit, Modulith, độ phủ | J1–J3 |
| SonarQube | `withSonarQubeEnv` + `waitForQualityGate abortPipeline: true` | quality gate không đạt | TL-01 ch.18 |
| Ảnh, SBOM, quét | Jib `compile jib:build` → digest; `syft` CycloneDX; `trivy image --ignore-unfixed --exit-code 1`; tải SBOM lên Dependency-Track | lỗ hổng HIGH/CRITICAL đã có bản vá | SEC-23, SEC-49, SEC-55 |
| Ký và chứng thực (chỉ `main`) | `cosign sign`, `cosign attest --type cyclonedx`, `vt-provenance`, `cosign attest --type slsaprovenance1`, rồi tự `cosign verify` và `cosign verify-attestation` | ký hoặc kiểm chữ ký lỗi | SEC-37, SEC-38 |
| Cập nhật GitOps (chỉ `main`) | `vtGitopsBump` | không ký được, không đẩy được | OPS-01 |

Các bước ghi nhận kết quả (độ phủ, JUnit) đặt trạng thái FAILURE mà không ném lỗi; mọi stage sau đó gọi
`chanNeuDaLoi()` nên không dựng, ký hay phát hành ảnh khi một cổng đã không đạt. Lưu SARIF, SBOM,
provenance và báo cáo openapi-diff làm artifact của bản dựng.

## Tham số `vtJavaPipeline`

Đầy đủ ở `MAC_DINH` trong `src/vn/vt/pipeline/CauHinh.groovy`; tham số lạ bị từ chối. Thường dùng: `service`
(bắt buộc), `dir` (`backend`), `jibModule` (mô-đun dựng ảnh khi nhiều mô-đun), `baseImage` (phải có
`@sha256:`), `goldenModule` (bộ ca vàng Policy Pack), `pact`, `sonar`, `dependencyTrack`. Ngưỡng độ phủ
không được đặt thấp hơn 80%.

## Biến môi trường cấp controller (quản trị Jenkins đặt, chỉ tên)

`VT_REGISTRY`, `VT_BASE_IMAGE`, `VT_SEMGREP_CONFIGS`, `VT_OPENAPI_DIFF_CMD`, `VT_SONAR`, `VT_PACT`,
`VT_DEPENDENCY_TRACK`, `VT_DEPENDENCY_TRACK_URL`, `VT_REGISTRY_CREDENTIALS_ID`, `VT_COSIGN_KEY`,
`VT_COSIGN_VERIFY_KEY`, `VT_VAULT_ADDR`, `VT_BUILDER_ID`, `VT_GITOPS_REPO`, `VT_GITOPS_CREDENTIALS_ID`.
Giá trị `-` nghĩa là để trống. Không có biến nào tắt được gitleaks, Semgrep, Trivy, kiểm thử hay openapi-diff.

Biến của pod dựng (đặt trong pod template, không phải bí mật): `TRIVY_DB_REPOSITORY` và
`TRIVY_JAVA_DB_REPOSITORY` trỏ bản sao CSDL lỗ hổng trong Harbor (pod dùng một lần không giữ CSDL ~3 GB
giữa các lần dựng); `SEMGREP_ENABLE_VERSION_CHECK=0`; `MAVEN_ARGS` nếu cần trỏ proxy Nexus chỉ đọc.

## Credential Jenkins (chỉ tên, không giá trị)

| ID | Loại | Dùng ở | Ghi chú |
|---|---|---|---|
| `harbor-robot-okr` | Username with password | Ảnh, SBOM, quét; Ký và chứng thực | Robot account Harbor dự án `okr`, quyền push |
| `openbao-cosign-token` | Secret text | Ký và chứng thực | Token OpenBao chỉ có quyền `sign` trên khoá Transit `cosign-release`, cấp cho tài khoản dịch vụ của container `provenance` (SLSA L3) |
| `dependency-track-api-key` | Secret text | Ảnh, SBOM, quét | Quyền `BOM_UPLOAD`, `PROJECT_CREATION_UPLOAD` |
| `gitops-push` | Username with password | Cập nhật GitOps | Quyền push nhánh `main` của kho GitOps |
| `gitops-signing-gpg` | Secret file | Cập nhật GitOps | Khoá riêng GPG dạng ASCII armor; khoá công khai khai báo ở `AppProject.spec.signatureKeys` của Argo CD |
| `pact-broker-token` | Secret text | Pact can-i-deploy | Chỉ khi `pact: true` |
| `vt-ci-webhook` | Secret text | vtNotify | URL webhook vào của kênh `okr-ci`; thiếu thì chỉ ghi log |
| (cấu hình SonarQube server `sonarqube`) | Cấu hình hệ thống | SonarQube | Token nằm trong cấu hình server của plugin SonarQube Scanner |

## Yêu cầu với Jenkins và pod dựng

- Plugin: Pipeline (workflow-aggregator), Pipeline: Groovy Libraries, Kubernetes, Git, Credentials Binding,
  Plain Credentials, Pipeline Utility Steps, JUnit, Coverage, SonarQube Scanner.
- Global Pipeline Library `vt-pipeline`: Modern SCM Git trỏ repo Mission, Library Path `ci/vt-pipeline/`,
  không cho `implicit`, cho phép ghi đè phiên bản.
- Nguồn Multibranch: không shallow clone; thêm refspec `+refs/heads/main:refs/remotes/origin/main` để gitleaks
  và openapi-diff có nhánh đích; bỏ qua commit có `[vt-gitops]` trong thông điệp (vòng lặp: commit cập nhật
  digest trên `main` của chính repo sẽ kích hoạt lại job).
- Pod template `vt-build-ephemeral`: container `jdk21` (mặc định) có JDK 21, git, gitleaks, semgrep, trivy,
  syft, cosign, openapi-diff, curl, gpg; container `provenance` có `vt-provenance` (dựng từ
  `resources/vn/vt/pipeline/vt-provenance.py`) và cosign. Pod chạy không root, không có cache Maven ghi chung.

## Pipeline theo loại (INF-06, thư viện `vt-pipeline-v1.1.0`)

Sáu pipeline mới dùng chung bộ bước trong `vars/vtBuoc.groovy` nên cổng bảo mật giống hệt pipeline Java:
gitleaks, Semgrep (bộ chuẩn của ngôn ngữ cộng quy tắc riêng `.semgrep/vt-rules-<ngôn ngữ>.yml`), Trivy fs, kiểm
Dockerfile (mọi `FROM` ghim `@sha256`, `USER` cuối là UID số ≥ `minUid`), dựng ảnh bằng BuildKit, SBOM bằng
Syft, quét ảnh bằng Trivy, đẩy SBOM lên Dependency-Track, ký và chứng thực bằng cosign, rồi cập nhật digest
GitOps. Tham số chung (registry, ngưỡng độ phủ, nhánh phát hành…) lấy từ `CauHinh.MAC_DINH`; tham số riêng
từng loại nằm trong `CauHinhLoai.MAC_DINH`, tham số lạ hoặc ngưỡng độ phủ dưới 80% làm hỏng build ngay.

| Bước | Cổng riêng ngoài bộ chung | Mẫu Jenkinsfile |
|---|---|---|
| `vtGoPipeline` | govulncheck (chạy song song với gitleaks, Semgrep, Trivy fs, Dockerfile), `go vet`, độ phủ | `examples/go.Jenkinsfile` |
| `vtPythonPipeline` | pip-audit với `--require-hashes`, pytest trong venv với `--cov-fail-under` | `examples/python.Jenkinsfile` |
| `vtFlutterPipeline` | `flutter analyze`, kiểm thử và độ phủ lcov; bản phát hành Android và iOS chỉ trên `main`, SBOM và `cosign sign-blob` | `examples/flutter.Jenkinsfile` |
| `vtHelmPipeline` | `kyverno test` và `kiem-ngoai-le.py`, `kiem-ba-mo-hinh.sh` (ba mô hình phân phối), ngân sách kết nối (INF-03); trên `main` đóng gói, `helm push`, ký chart | `examples/helm.Jenkinsfile`, `deploy/Jenkinsfile` |
| `vtPolicyPackPipeline` | cú pháp quy tắc, kiểm thử đơn vị song song với ca mẫu vàng 100%; trên `main` kiểm chữ ký nghiệp vụ, đóng gói tất định, ký, chạy bóng, duyệt, gửi phát hành | `examples/policy-pack.Jenkinsfile`, `data/policy-pack/Jenkinsfile` |
| `vtUiPackPipeline` | lược đồ, registry, an toàn, chế độ cao tuổi, CSS ≤ 30 KB gzip; axe-core; ảnh so sánh trên staging; ký; tải lên, gửi kiểm tra, duyệt, phát hành | `examples/ui-pack.Jenkinsfile` |

Biến môi trường cấp controller thêm cho INF-06 (chỉ tên): `VT_IMAGE_BUILDER` (`buildkit` trên cụm, `docker` trên
máy kiểm thử), `VT_API_BASE`, `VT_API_CA`, `VT_STAGING_URL`, `VT_CHART_REPO`, `VT_CHART_PUSH_FLAGS`. Credential
thêm (chỉ tên): `mission-ci-policy-pack`, `mission-ci-ui-pack` (token API dịch vụ), `mobile-android-upload-keystore`,
`mobile-android-key-properties`, `mobile-ios-export-options`. Bước duyệt phát hành dùng `input` với nhóm
`policy-pack-approvers` hoặc `ui-pack-approvers`.

Lưu ý khi dùng: cosign 3 phải ký với `--new-bundle-format=false` vì Kyverno 1.15 chưa đọc định dạng bundle mới;
Go phải dựng bằng bản vá mới nhất (thư viện chuẩn Go 1.24 có lỗ hổng mà govulncheck chặn); Dockerfile của các
service Go hiện có chưa ghim digest nên sẽ hỏng cổng Dockerfile cho tới khi được sửa; `capacityCheckCmd` rỗng
(INF-03 chưa gộp) làm stage ngân sách kết nối ở trạng thái UNSTABLE chứ không bỏ qua im lặng.

## Kiểm thử

```bash
semgrep --test --metrics=off --config .semgrep/vt-rules.yml .semgrep/vt-rules.java
ci/vt-pipeline/tests/chay-kiem-thu.sh            # tat-ca | cong | jenkins
```

`chay-kiem-thu.sh` dựng một monorepo thu nhỏ từ `tests/fixture-repo` (dự án Maven mẫu, bản sao nhỏ của
hợp đồng `jobs.yaml`), tạo năm merge request (sạch, kiểm thử lỗi, bí mật giả sinh lúc chạy, log4j-core
2.14.1 có CVE-2021-44228, xoá thao tác `cancelJob`) và kiểm ở hai tầng: chạy trực tiếp từng cổng, và chạy
`backend/Jenkinsfile` cùng thư viện trên Jenkins thật (Jenkinsfile Runner) với registry cục bộ, khoá cosign
và khoá GPG sinh tạm. Giới hạn của môi trường kiểm thử (không có plugin kubernetes, coverage, máy chủ
SonarQube, Dependency-Track) ghi ở đầu script và ở wiki 11.

Pipeline INF-06 kiểm bằng `tests/chay-kiem-thu-inf06.sh`: dựng repo mẫu từ `tests/fixture-inf06`, chạy từng
Jenkinsfile trên Jenkinsfile Runner với registry cục bộ, API giả (`tests/jenkins/api-gia.py` kiểm xác thực,
Idempotency-Key, If-Match và tác vụ 202), staging giả và khoá cosign tạm; mỗi loại có ca sạch, ca phải bị chặn
(bí mật giả, govulncheck, kiểm thử hỏng, ca vàng sai, CSS 31 KB…) và ca nhánh `main`. Bước `input` được thay bằng
bản tự duyệt trong `tests/jenkins/stub-lib`, Flutter dùng CLI giả `tests/jenkins/flutter-gia` vì máy kiểm thử
không có SDK Flutter.
