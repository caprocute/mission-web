// vtFlutterPipeline: pipeline của ứng dụng di động Flutter (SVC-18) (INF-06; docs/quy-dinh/DI-DONG.md).
// Truy vết: TL-04 mục 9.6 → SEC-09, SEC-23, SEC-38, SEC-39 → J1–J3. Merge request chạy phân tích, kiểm thử, quét;
// nhánh main dựng bản phát hành Android (AAB) và iOS (IPA), ký ứng dụng bằng credential Jenkins (không ghi giá trị
// vào mã hay log), sinh SBOM và ký gói bằng cosign sign-blob để kho phát hành nội bộ kiểm được nguồn gốc.
//   @Library('vt-pipeline@vt-pipeline-v1.1.0') _
//   vtFlutterPipeline(dir: 'mobile')
import vn.vt.pipeline.CauHinhLoai

def call(Map thamSo = [:]) {
  Map cfg = CauHinhLoai.ghep('flutter', thamSo, env.getEnvironment())

  pipeline {
    agent {
      kubernetes {
        inheritFrom 'vt-build-ephemeral'
        defaultContainer 'flutter'
      }
    }
    options {
      timeout(time: 60, unit: 'MINUTES')
      disableConcurrentBuilds(abortPrevious: true)
      buildDiscarder(logRotator(numToKeepStr: '30'))
    }
    environment {
      PUB_CACHE = "${env.WORKSPACE}@tmp/pub-cache"
    }
    stages {
      stage('Bí mật và quét phụ thuộc') {
        parallel {
          stage('gitleaks') { steps { script { vtBuoc.quetBiMat(cfg) } } }
          stage('Semgrep') { steps { script { vtBuoc.quetSemgrep(cfg) } } }
          // Trivy đọc pubspec.lock (Dart) và Gradle lockfile của android/
          stage('Trivy fs') { steps { script { vtBuoc.quetTrivyFs(cfg, 'vuln,license') } } }
        }
      }
      stage('Phân tích và kiểm thử') {
        steps {
          dir(cfg.dir) {
            sh 'flutter --version --machine > /dev/null && flutter pub get --enforce-lockfile'
            sh 'dart format --output=none --set-exit-if-changed lib test'
            sh 'flutter analyze --fatal-infos --fatal-warnings'
            sh 'flutter test --coverage --machine > flutter-test.json'
          }
          script {
            // lcov: tỷ lệ dòng được chạy trên tổng dòng có mã
            vtBuoc.kiemDoPhu(cfg, 'Flutter', "awk -F: '/^LF:/ {t+=\$2} /^LH:/ {h+=\$2} END {printf \"%.1f\", (t ? h*100/t : 0)}' " +
                             "${vtBuoc.q(cfg.dir + '/coverage/lcov.info')}")
          }
        }
      }
      stage('Bản phát hành') {
        when { branch 'main' }
        parallel {
          stage('Android') {
            when { expression { return cfg.buildAndroid } }
            steps {
              script { vtBuoc.chanNeuDaLoi() }
              withCredentials([file(credentialsId: cfg.androidKeystoreCredentialsId, variable: 'VT_KEYSTORE'),
                               file(credentialsId: cfg.androidKeyPropsCredentialsId, variable: 'VT_KEY_PROPS')]) {
                dir(cfg.dir) {
                  // key.properties trỏ tới keystore trong thư mục tạm của lần dựng, xoá ngay sau khi dựng
                  sh '''set +x
                    umask 077
                    sed "s|^storeFile=.*|storeFile=$VT_KEYSTORE|" "$VT_KEY_PROPS" > android/key.properties
                    trap 'rm -f android/key.properties' EXIT
                    flutter build appbundle --release --build-number "$BUILD_NUMBER"'''
                }
              }
              sh "cp ${vtBuoc.q(cfg.dir + '/build/app/outputs/bundle/release/app-release.aab')} app-release.aab"
              stash name: 'android', includes: 'app-release.aab'
            }
          }
          stage('iOS') {
            when { expression { return cfg.buildIos } }
            agent { label cfg.iosAgentLabel }            // máy macOS có Xcode; chứng chỉ ký nằm trong keychain của agent
            steps {
              script { vtBuoc.chanNeuDaLoi() }
              withCredentials([file(credentialsId: cfg.iosExportOptionsCredentialsId, variable: 'VT_EXPORT_OPTIONS')]) {
                dir(cfg.dir) {
                  sh 'flutter pub get --enforce-lockfile && flutter build ipa --release --build-number "$BUILD_NUMBER" ' +
                     '--export-options-plist "$VT_EXPORT_OPTIONS"'
                  sh 'cp build/ios/ipa/*.ipa ../app-release.ipa'
                }
              }
              stash name: 'ios', includes: 'app-release.ipa'
            }
          }
        }
      }
      stage('SBOM và ký gói') {
        when { branch 'main' }
        steps {
          script {
            vtBuoc.chanNeuDaLoi()
            List goi = []
            if (cfg.buildAndroid) { unstash 'android'; goi << 'app-release.aab' }
            if (cfg.buildIos) { unstash 'ios'; goi << 'app-release.ipa' }
            sh "syft scan dir:${vtBuoc.q(cfg.dir)} -o cyclonedx-json=sbom.cdx.json"
            vtBuoc.dayDependencyTrack(cfg)
            vtBuoc.kyTep(cfg, goi + ['sbom.cdx.json'])
          }
        }
      }
    }
    post {
      always {
        archiveArtifacts allowEmptyArchive: true,
                         artifacts: '*.sarif, sbom.cdx.json, *.aab, *.ipa, *.cosign.bundle, **/coverage/lcov.info'
        sh 'rm -rf "$PUB_CACHE"'
      }
      failure { vtNotify(channel: cfg.notifyChannel, service: cfg.service) }
    }
  }
}
