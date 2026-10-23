// vtHelmPipeline: pipeline cho Helm chart và chính sách trong deploy/ (INF-06).
// Truy vết: TL-04 mục 2.1 (ba mô hình phân phối), 5.2–5.3 (chart dịch vụ), 9.3 (Kyverno) → SEC-26, SEC-28, SEC-38,
// SEC-55 → C1, C3, C7, C10. Các cổng dùng lại script của INF-02 (kiem-ba-mo-hinh.sh: helm lint, helm template ba
// mô hình, kubeconform -strict, kube-linter, Kyverno CLI với deploy/policies, ngoại lệ còn hạn, đối chiếu TL-04)
// và kyverno test của INF-07; thêm kiểm ngân sách kết nối của INF-03. Nhánh main đóng gói chart thành OCI,
// đẩy lên Harbor và ký cosign theo digest.
//   @Library('vt-pipeline@vt-pipeline-v1.1.0') _
//   vtHelmPipeline()
import vn.vt.pipeline.CauHinhLoai

def call(Map thamSo = [:]) {
  Map cfg = CauHinhLoai.ghep('helm', thamSo, env.getEnvironment())

  pipeline {
    agent {
      kubernetes {
        inheritFrom 'vt-build-ephemeral'
        defaultContainer 'helm'
      }
    }
    options {
      timeout(time: 30, unit: 'MINUTES')
      disableConcurrentBuilds(abortPrevious: true)
      buildDiscarder(logRotator(numToKeepStr: '50'))
    }
    environment {
      DOCKER_CONFIG = "${env.WORKSPACE}@tmp/vt-docker"
      HELM_REGISTRY_CONFIG = "${env.WORKSPACE}@tmp/vt-docker/config.json"
      // Kyverno CLI và promtool của các bộ kiểm thử chart (INF-28), cài ở stage "Phụ thuộc chart".
      VT_CONG_CU = "${env.WORKSPACE}@tmp/vt-cong-cu"
    }
    stages {
      stage('Phụ thuộc chart') {
        // Dựng charts/ của mọi chart trước khi quét: thiếu phụ thuộc thì trivy không render được chart con và bỏ qua
        steps {
          sh "bash ${vtBuoc.q(cfg.dependencyCmd)}"
          sh "bash ${vtBuoc.q(vtBuoc.napTaiNguyen('cai-cong-cu-chart.sh'))} \"\$VT_CONG_CU\""
        }
      }
      stage('Bí mật và cấu hình sai') {
        parallel {
          stage('gitleaks') { steps { script { vtBuoc.quetBiMat(cfg) } } }
          stage('Trivy config') { steps { script { vtBuoc.quetTrivyFs(cfg, 'misconfig,secret') } } }
        }
      }
      stage('Chính sách Kyverno') {
        steps {
          sh "PATH=\"\$VT_CONG_CU:\$PATH\" kyverno test ${vtBuoc.q(cfg.policiesDir + '/tests')}"
          sh "python3 ${vtBuoc.q(cfg.policiesDir + '/kiem-ngoai-le.py')}"
        }
      }
      stage('Ba mô hình phân phối') {
        // helm lint, helm template saas/private/airgap, kubeconform -strict, kube-linter, Kyverno, đối chiếu TL-04
        steps { sh "PATH=\"\$VT_CONG_CU:\$PATH\" BANG_RA=bang-doi-chieu-tl04.md bash ${vtBuoc.q(cfg.platformChart + '/ci/kiem-ba-mo-hinh.sh')}" }
      }
      stage('Kiểm thử chart') {
        // INF-28: bộ kiểm thử tĩnh từng chart (cfg.chartTests); mọi bộ đều chạy, một bộ đỏ thì stage đỏ.
        steps {
          script {
            List<String> hong = []
            for (String t : cfg.chartTests) {
              if (sh(script: "PATH=\"\$VT_CONG_CU:\$PATH\" bash ${vtBuoc.q(t)}", returnStatus: true) != 0) { hong << t }
            }
            if (hong) { error("Kiểm thử chart hỏng: ${hong.join(', ')}") }
          }
        }
      }
      stage('Ngân sách kết nối') {
        steps {
          script {
            if (cfg.capacityCheckCmd) {
              sh cfg.capacityCheckCmd
            } else {
              // Không bỏ qua im lặng: đánh dấu UNSTABLE để người duyệt thấy cổng này chưa có (INF-03 chưa gộp)
              unstable('Chưa có lệnh kiểm ngân sách kết nối của INF-03 (capacityCheckCmd)')
            }
          }
        }
      }
      stage('Đóng gói và ký chart') {
        when { branch 'main' }
        steps {
          script {
            vtBuoc.chanNeuDaLoi()
            vtBuoc.dangNhapRegistry(cfg)
            sh "rm -rf .vt-chart && mkdir .vt-chart && helm package ${vtBuoc.q(cfg.platformChart)} -d .vt-chart"
            String ra = sh(script: "helm push ${cfg.chartPushFlags} .vt-chart/*.tgz ${vtBuoc.q(cfg.chartRepo)} 2>&1", returnStdout: true)
            echo ra
            def m = ra =~ /Digest:\s*(sha256:[0-9a-f]{64})/
            if (!m) { error('helm push không trả digest') }
            String ten = sh(script: "helm show chart ${vtBuoc.q(cfg.platformChart)} | awk '/^name:/ {print \$2}'", returnStdout: true).trim()
            env.DIGEST = m[0][1]
            env.IMAGE_REF = "${cfg.chartRepo.replaceFirst('^oci://', '')}/${ten}@${env.DIGEST}"
            vtBuoc.trongContainerKy(cfg) {
              sh 'cosign sign --yes --key "$COSIGN_KEY" --tlog-upload=false --use-signing-config=false --new-bundle-format=false "$IMAGE_REF"'
              sh 'cosign verify --key "$COSIGN_VERIFY_KEY" --insecure-ignore-tlog=true "$IMAGE_REF" > cosign-verify.json'
            }
          }
        }
      }
    }
    post {
      always {
        archiveArtifacts allowEmptyArchive: true, artifacts: '*.sarif, bang-doi-chieu-tl04.md, cosign-verify.json, .vt-chart/*.tgz'
        sh 'rm -rf "$DOCKER_CONFIG"'
      }
      failure { vtNotify(channel: cfg.notifyChannel, service: cfg.service) }
    }
  }
}
