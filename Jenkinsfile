// WEB-002: pipeline CI của phân hệ web SVC-17 (mission-web) với cổng chất lượng theo TL-01
// chương 18, TL-04 mục "Chuỗi cung ứng phần mềm: SLSA, Jenkins, Argo CD" và ADR-16.
// Truy vết: SEC-23 (SBOM, Dependency-Track), SEC-37/SEC-38 (SLSA mức 3, ký cosign),
// SEC-39 (Semgrep chặn merge), SEC-49 (quét bí mật), SEC-55 (ảnh theo digest), PERF-21.
//
// Mọi cổng thất bại làm pipeline dừng (lệnh trả mã khác 0, khối parallel có failFast).
// Không cổng nào được hạ ngưỡng để pipeline xanh (AGENTS.md quy tắc 11, YC-PT-07).
//
// Bí mật: chỉ lấy qua Jenkins Credentials trong withCredentials, Jenkins tự che giá trị trong
// log; lệnh sh dùng chuỗi nháy đơn để Groovy không nội suy bí mật; không bật `set -x`;
// gitleaks chạy với --redact. Danh sách biến và credential cần có: wiki/11, mục "Pipeline Jenkins".

pipeline {
  agent {
    kubernetes {
      // Pod dựng dùng một lần, không giữ trạng thái giữa các lần dựng (SLSA mức 3, TL-04 9.6)
      inheritFrom 'vt-build-ephemeral'
      defaultContainer 'node'
      yaml '''
apiVersion: v1
kind: Pod
metadata:
  annotations:
    container.apparmor.security.beta.kubernetes.io/buildkit: unconfined
spec:
  containers:
    - name: node            # Node 22 + Chromium 1194 cho Lighthouse CI (khớp @playwright/test 1.56.1)
      image: mcr.microsoft.com/playwright:v1.56.1-noble
      command: [sleep]
      args: [infinity]
      resources:
        requests: { cpu: "2", memory: 6Gi }
        limits: { cpu: "4", memory: 8Gi }
    - name: python          # docs/phan-quyen/gen_matrix.py --check (chỉ thư viện chuẩn)
      image: python:3.12-alpine
      command: [sleep]
      args: [infinity]
    - name: gitleaks
      image: zricethezav/gitleaks:v8.21.2
      command: [sleep]
      args: [infinity]
    - name: semgrep
      image: semgrep/semgrep:1.95.0
      command: [sleep]
      args: [infinity]
    - name: trivy
      image: aquasec/trivy:0.57.1
      command: [sleep]
      args: [infinity]
    - name: syft
      image: anchore/syft:v1.18.1-debug
      command: [/busybox/sleep]
      args: [infinity]
    - name: cosign
      image: gcr.io/projectsigstore/cosign:v2.4.1-dev
      command: [sleep]
      args: [infinity]
    - name: buildkit        # BuildKit không root (TL-04 9.6)
      image: moby/buildkit:v0.18.2-rootless
      command: [sleep]
      args: [infinity]
      env:
        - name: BUILDKITD_FLAGS
          value: --oci-worker-no-process-sandbox
      securityContext:
        seccompProfile: { type: Unconfined }
'''
    }
  }

  options {
    timeout(time: 60, unit: 'MINUTES')
    disableConcurrentBuilds(abortPrevious: true)
    buildDiscarder(logRotator(numToKeepStr: '50'))
    timestamps()
  }

  environment {
    SERVICE = 'mission-web'
    // Registry Harbor cấu hình ở biến toàn cục của Jenkins (MISSION_REGISTRY), không ghi cứng
    REGISTRY = "${env.MISSION_REGISTRY}"
    // Khoá ký nằm trong OpenBao Transit; pod chỉ có quyền sign (TL-04 9.6)
    COSIGN_KEY = 'hashivault://cosign-release'
    CI = 'true'
    NPM_CONFIG_AUDIT = 'false'
    NPM_CONFIG_FUND = 'false'
    NPM_CONFIG_UPDATE_NOTIFIER = 'false'
    // Chromium có sẵn trong ảnh Playwright, dùng cho lhci
    CHROME_PATH = '/ms-playwright/chromium-1194/chrome-linux/chrome'
    // Thông tin đăng nhập registry chỉ nằm ở đây trong thời gian chạy, xoá ở post
    DOCKER_CONFIG = "${env.WORKSPACE}/.docker-ci"
  }

  stages {
    stage('Quét bí mật') {
      // Chạy đầu tiên để không bước nào khác xử lý mã có bí mật lộ (SEC-49, gitleaks theo ADR-16)
      steps {
        container('gitleaks') {
          script {
            // Nhánh chính quét toàn lịch sử; nhánh và merge request quét các commit mới so với main
            env.GITLEAKS_RANGE = (env.BRANCH_NAME == 'main') ? '' : '--log-opts=origin/main..HEAD'
          }
          sh '''
            gitleaks git --config deploy/web/ci/gitleaks.toml --redact --no-banner \
              --exit-code 1 --report-format sarif --report-path gitleaks.sarif $GITLEAKS_RANGE .
          '''
        }
      }
    }

    stage('Cài phụ thuộc') {
      // npm ci theo package-lock; registry npm là proxy Nexus chỉ đọc, cấu hình qua tệp .npmrc
      // trong credential (nếu cần xác thực), không ghi vào repo
      failFast true
      parallel {
        stage('frontend') {
          steps {
            withCredentials([file(credentialsId: 'mission-npmrc', variable: 'NPM_CONFIG_USERCONFIG')]) {
              dir('frontend') { sh 'npm ci --no-audit --no-fund' }
            }
          }
        }
        stage('contracts') {
          steps {
            withCredentials([file(credentialsId: 'mission-npmrc', variable: 'NPM_CONFIG_USERCONFIG')]) {
              dir('contracts') { sh 'npm ci --no-audit --no-fund' }
            }
          }
        }
      }
    }

    stage('Hợp đồng API') {
      // ADR-32, WEB-063, WEB-064: hợp đồng OpenAPI chạy trước lint của frontend (wiki/11)
      steps {
        dir('contracts') { sh 'npm run lint' }                 // Redocly lint contracts/openapi
        dir('frontend') { sh 'npm run api:check' }             // kiểu sinh trong @vt/api-client khớp hợp đồng
      }
    }

    stage('Lint, nội dung giao diện, phân quyền') {
      failFast true
      parallel {
        stage('ESLint') {
          steps { dir('frontend') { sh 'npm run lint' } }
        }
        stage('Nội dung giao diện') {
          // AGENTS.md quy tắc 9, YC-PT-10: chặn mã SCR/QT, mã trạng thái thô, tên kỹ thuật,
          // ghi chú giải pháp lộ ra màn hình (wiki/16)
          steps { dir('frontend') { sh 'npm run check:ui-text' } }
        }
        stage('Ma trận phân quyền') {
          steps {
            container('python') { sh 'python3 docs/phan-quyen/gen_matrix.py --check' }   // = npm run check:permissions
          }
        }
      }
    }

    stage('Kiểm thử đơn vị') {
      // Vitest cho mọi thư viện, remote và shell có spec (gồm contract-check.spec.ts).
      // Báo cáo độ phủ tệp thay đổi (ngưỡng 80% của TL-04) chưa bật: workspace chưa có
      // @vitest/coverage-v8; xem báo cáo WEB-002.
      steps { dir('frontend') { sh 'npm run test:all' } }
    }

    stage('Build') {
      // Ngân sách của angular.json (cấu hình production) làm build thất bại khi vượt mức lỗi
      steps { dir('frontend') { sh 'npm run build:all' } }
    }

    stage('Ngân sách bundle') {
      // TL-01 "Ngân sách hiệu năng": shell + design system + runtime Angular ≤ 250 KB gzip,
      // mỗi remote tải đầu ≤ 180 KB gzip; đo tĩnh trên dist, không cần trình duyệt
      steps {
        dir('frontend') { sh 'node ../deploy/web/ci/bundle-budget.mjs --json bundle-budget.json' }
      }
      post {
        always { archiveArtifacts artifacts: 'frontend/bundle-budget.json', allowEmptyArchive: true }
      }
    }

    stage('Lighthouse CI') {
      // PERF-21: giả lập 4G, CPU chậm 4 lần, ngưỡng trong frontend/lighthouserc.json.
      // Dùng lại kết quả build ở trên (không chạy perf:lhci vì lệnh đó build lại).
      steps {
        dir('frontend') {
          sh '''
            node tools/remotes/workspace.mjs assemble dist/lhci
            cd dist && npx lhci autorun --config=../lighthouserc.json
          '''
        }
      }
      post {
        always { archiveArtifacts artifacts: 'frontend/dist/lhci-report/**', allowEmptyArchive: true }
      }
    }

    stage('SAST và SCA') {
      failFast true
      parallel {
        stage('Semgrep') {
          // SEC-39: luật chung TypeScript và luật riêng của repo; lỗi mức ERROR chặn merge
          steps {
            container('semgrep') {
              sh '''
                semgrep scan --metrics=off --disable-version-check \
                  --config p/typescript --config deploy/web/ci/semgrep.yml \
                  --exclude node_modules --exclude dist \
                  --error --sarif --output semgrep.sarif
              '''
            }
          }
        }
        stage('Trivy fs') {
          // Lỗ hổng CVSS ≥ 7 (HIGH, CRITICAL) trong package-lock, cấu hình sai, giấy phép
          steps {
            container('trivy') {
              sh '''
                trivy fs --scanners vuln,misconfig,license --severity HIGH,CRITICAL --exit-code 1 \
                  --skip-dirs frontend/node_modules --skip-dirs contracts/node_modules \
                  --skip-dirs frontend/dist --no-progress .
              '''
            }
          }
        }
      }
    }

    stage('Ảnh, SBOM, quét ảnh') {
      steps {
        script {
          if (!env.MISSION_REGISTRY) { error('Chưa đặt biến toàn cục MISSION_REGISTRY của Jenkins') }
        }
        withCredentials([file(credentialsId: 'harbor-push-dockerconfig', variable: 'REGISTRY_AUTH')]) {
          sh 'mkdir -p "$DOCKER_CONFIG" && umask 077 && cp "$REGISTRY_AUTH" "$DOCKER_CONFIG/config.json"'
        }
        container('buildkit') {
          sh '''
            buildctl-daemonless.sh build --frontend dockerfile.v0 \
              --local context=. --local dockerfile=deploy/web --opt filename=Dockerfile \
              --output type=image,name=$REGISTRY/$SERVICE:$GIT_COMMIT,push=true \
              --metadata-file build-meta.json
          '''
        }
        script {
          env.DIGEST = readJSON(file: 'build-meta.json')['containerimage.digest']
          if (!(env.DIGEST ==~ /sha256:[0-9a-f]{64}/)) { error('Không đọc được digest của ảnh') }
        }
        container('syft') {
          sh 'syft "$REGISTRY/$SERVICE@$DIGEST" -o cyclonedx-json=sbom.cdx.json'      // SBOM CycloneDX (SEC-23)
        }
        container('trivy') {
          sh 'trivy image --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --no-progress "$REGISTRY/$SERVICE@$DIGEST"'
        }
      }
      post {
        always { archiveArtifacts artifacts: 'sbom.cdx.json, build-meta.json', allowEmptyArchive: true }
      }
    }

    stage('Ký và chứng thực') {
      // Chỉ nhánh main: ký ảnh, gắn SBOM và provenance SLSA (SEC-37, SEC-38); Kyverno ở cụm
      // chỉ cho chạy ảnh có chữ ký này
      when { branch 'main' }
      steps {
        container('cosign') {
          sh '''
            cosign sign --yes --key "$COSIGN_KEY" --tlog-upload=false "$REGISTRY/$SERVICE@$DIGEST"
            cosign attest --yes --key "$COSIGN_KEY" --tlog-upload=false --type cyclonedx \
              --predicate sbom.cdx.json "$REGISTRY/$SERVICE@$DIGEST"
          '''
        }
        // Provenance do container riêng của pod mẫu vt-build-ephemeral sinh (công cụ vt-provenance
        // của thư viện vt-pipeline), mã dự án không can thiệp được (TL-04 9.6)
        container('provenance') {
          sh 'vt-provenance --builder-id "$SLSA_BUILDER_ID" --repo "$GIT_URL" --commit "$GIT_COMMIT" --digest "$DIGEST" > provenance.json'
        }
        container('cosign') {
          sh 'cosign attest --yes --key "$COSIGN_KEY" --tlog-upload=false --type slsaprovenance1 --predicate provenance.json "$REGISTRY/$SERVICE@$DIGEST"'
        }
        withCredentials([string(credentialsId: 'dtrack-api-key', variable: 'DTRACK_API_KEY')]) {
          // Đẩy SBOM lên Dependency-Track (SEC-23); khoá chỉ đi qua header, Jenkins che trong log
          sh '''
            curl -sSf -o /dev/null -X POST -H "X-Api-Key: $DTRACK_API_KEY" \
              -F autoCreate=true -F projectName="$SERVICE" -F projectVersion="$GIT_COMMIT" \
              -F bom=@sbom.cdx.json "$DTRACK_URL/api/v1/bom"
          '''
        }
      }
    }
  }

  post {
    always {
      // Không để lại thông tin đăng nhập registry trong workspace
      sh 'rm -rf "$DOCKER_CONFIG"'
      archiveArtifacts artifacts: 'gitleaks.sarif, semgrep.sarif', allowEmptyArchive: true
    }
  }
}
