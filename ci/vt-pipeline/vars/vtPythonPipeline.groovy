// vtPythonPipeline: pipeline dùng chung cho dịch vụ và job Python (SVC-14 ai-gateway, job SVC-15 analytics) (INF-06).
// Truy vết: TL-04 mục 9.6 → SEC-09, SEC-23, SEC-37, SEC-38, SEC-39, SEC-49, SEC-55 → J1–J3.
//   @Library('vt-pipeline@vt-pipeline-v1.1.0') _
//   vtPythonPipeline(service: 'ai-gateway', dir: 'services-py/ai-gateway')
import vn.vt.pipeline.CauHinhLoai

def call(Map thamSo = [:]) {
  Map cfg = CauHinhLoai.ghep('python', thamSo, env.getEnvironment())

  pipeline {
    agent {
      kubernetes {
        inheritFrom 'vt-build-ephemeral'
        defaultContainer 'python'
      }
    }
    options {
      timeout(time: 30, unit: 'MINUTES')
      disableConcurrentBuilds(abortPrevious: true)
      buildDiscarder(logRotator(numToKeepStr: '50'))
    }
    environment {
      DOCKER_CONFIG = "${env.WORKSPACE}@tmp/vt-docker"
      PIP_DISABLE_PIP_VERSION_CHECK = '1'
      VENV = "${env.WORKSPACE}@tmp/venv"
    }
    stages {
      stage('Bí mật và SAST') {
        parallel {
          stage('gitleaks') { steps { script { vtBuoc.quetBiMat(cfg) } } }
          stage('Semgrep') { steps { script { vtBuoc.quetSemgrep(cfg) } } }
          stage('Trivy fs') { steps { script { vtBuoc.quetTrivyFs(cfg) } } }
          stage('Dockerfile') { steps { script { vtBuoc.kiemDockerfile(cfg) } } }
        }
      }
      stage('pip-audit') {
        // Phụ thuộc ghim phiên bản kèm hash (mặc định bắt buộc); lỗ hổng đã biết nào cũng chặn
        steps {
          dir(cfg.dir) {
            sh "pip-audit --strict ${cfg.requireHashes ? '--require-hashes --disable-pip ' : ''}-r ${vtBuoc.q(cfg.requirements)} " +
               '--format cyclonedx-json -o pip-audit.cdx.json'
          }
        }
      }
      stage('pytest') {
        steps {
          dir(cfg.dir) {
            sh """python3 -m venv "\$VENV" && . "\$VENV/bin/activate"
                  pip install -q ${cfg.requireHashes ? "--require-hashes " : ""}-r ${vtBuoc.q(cfg.requirements)}
                  if [ -f ${vtBuoc.q(cfg.testRequirements)} ]; then pip install -q -r ${vtBuoc.q(cfg.testRequirements)}; fi
                  python -m pytest --junitxml=pytest.xml --cov=. --cov-report=xml:coverage.xml \\
                    --cov-fail-under=${cfg.coverageThreshold} ${cfg.pytestArgs}"""
          }
        }
        post { always { junit testResults: "${cfg.dir}/pytest.xml", allowEmptyResults: true } }
      }
      stage('Ảnh, SBOM, quét') {
        steps { script { vtBuoc.chanNeuDaLoi(); vtBuoc.dungAnh(cfg); vtBuoc.sbomVaQuetAnh(cfg) } }
      }
      stage('Ký và chứng thực') {
        when { branch 'main' }
        steps { script { vtBuoc.chanNeuDaLoi(); vtBuoc.kyVaChungThucAnh(cfg) } }
      }
      stage('Cập nhật GitOps') {
        when { branch 'main' }
        steps { script { vtBuoc.chanNeuDaLoi(); vtBuoc.capNhatGitops(cfg) } }
      }
    }
    post {
      always {
        archiveArtifacts allowEmptyArchive: true,
                         artifacts: '*.sarif, sbom.cdx.json, provenance.json, cosign-verify*.json, **/coverage.xml, **/pip-audit.cdx.json'
        sh 'rm -rf "$DOCKER_CONFIG" "$VENV"'
      }
      failure { vtNotify(channel: cfg.notifyChannel, service: cfg.service) }
    }
  }
}
