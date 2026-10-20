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
│   ├── vtGitopsBump.groovy     commit có chữ ký GPG cập nhật digest vào deploy/environments/<env>/digests.yaml
│   └── vtNotify.groovy         báo kết quả cho kênh của đội (webhook)
├── src/vn/vt/pipeline/
│   ├── CauHinh.groovy          tham số, giá trị mặc định, biến môi trường VT_* cấp controller
│   └── DigestsFile.groovy      sửa digests.yaml theo dòng, giữ chú thích
├── resources/vn/vt/pipeline/
│   ├── openapi-diff.sh         so hợp đồng contracts/openapi từng module với nhánh đích (ADR-32)
│   └── vt-provenance.py        bản tham chiếu công cụ sinh predicate SLSA Provenance v1
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
| Hợp đồng API › Pact can-i-deploy | `pact-broker can-i-deploy` | tắt mặc định, bật `pact: true` khi QA-02 xong | TL-01 ch.18 |
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
