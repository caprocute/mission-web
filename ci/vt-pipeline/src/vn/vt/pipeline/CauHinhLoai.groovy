package vn.vt.pipeline

import com.cloudbees.groovy.cps.NonCPS

/**
 * Cấu hình của các pipeline INF-06 (Go, Python, Flutter, Helm, Policy Pack, UI Pack). Cùng nguyên tắc với
 * CauHinh của vtJavaPipeline: mặc định theo TL-04 mục 9.6 < tham số Jenkinsfile < biến `VT_*` cấp controller,
 * khoá lạ bị từ chối, và không có biến nào tắt được cổng bí mật, SAST, lỗ hổng hay kiểm thử.
 */
class CauHinhLoai implements Serializable {

  /** Khoá dùng chung cho mọi loại; giá trị lấy từ CauHinh.MAC_DINH để hai bên không lệch. */
  static final List KHOA_CHUNG = ['service', 'registry', 'gitleaksConfig', 'coverageThreshold', 'dependencyTrack',
    'dependencyTrackUrl', 'dependencyTrackCredentialsId', 'registryCredentialsId', 'cosignKey', 'cosignVerifyKey',
    'cosignVaultTokenCredentialsId', 'vaultAddr', 'provenanceContainer', 'builderId', 'releaseBranch', 'gitopsEnv',
    'gitopsRepo', 'gitopsBranch', 'gitopsCredentialsId', 'gitopsSigningKeyCredentialsId', 'notifyChannel']

  static final Map RIENG = [
    go: [
      dir: '.', semgrepConfigs: ['p/golang', '.semgrep/vt-rules-go.yml'], dockerfile: 'Dockerfile',
      goPackages: './...', imageBuilder: 'buildkit', minUid: 10000,
    ],
    python: [
      dir: '.', semgrepConfigs: ['p/python', '.semgrep/vt-rules-python.yml'], dockerfile: 'Dockerfile',
      requirements: 'requirements.txt', requireHashes: true, testRequirements: 'requirements-dev.txt', pytestArgs: '',
      imageBuilder: 'buildkit', minUid: 10000,
    ],
    flutter: [
      service: 'mobile-app', dir: 'mobile', semgrepConfigs: ['.semgrep/vt-rules-dart.yml'],
      androidKeystoreCredentialsId: 'mobile-android-upload-keystore',      // tệp keystore + mật khẩu (credential)
      androidKeyPropsCredentialsId: 'mobile-android-key-properties',
      iosAgentLabel: 'macos-xcode', iosExportOptionsCredentialsId: 'mobile-ios-export-options',
      buildIos: true, buildAndroid: true, artifactsRepo: 'harbor.vt.local/platform/mobile-builds',
    ],
    helm: [
      service: 'vt-platform', dir: 'deploy', chartsDir: 'deploy/charts', platformChart: 'deploy/charts/vt-platform',
      policiesDir: 'deploy/policies', models: ['saas', 'private', 'airgap'],
      trivySkipDirs: ['**/policies/tests'],  // tài nguyên mẫu cố ý vi phạm của kyverno test
      trivyIgnoreFile: 'deploy/.trivyignore.yaml', dependencyCmd: 'deploy/charts/vt-platform/ci/cap-nhat-phu-thuoc.sh',
      capacityCheckCmd: '',            // INF-03: lệnh kiểm ngân sách kết nối; rỗng khi INF-03 chưa gộp (bước báo, không bỏ qua im lặng)
      chartRepo: 'oci://harbor.vt.local/platform/charts', chartPushFlags: '',
    ],
    policyPack: [
      service: 'policy-pack', dir: 'data/policy-pack', packs: [],            // ['khoi-chinh-quyen/nd335', ...]; rỗng = mọi gói
      staticCheckCmd: 'python3 -I data/policy-pack/kiem_goi.py .',
      unitTestCmd: 'python3 -m pytest -q data/golden',
      goldenCmd: 'python3 -I data/golden/chay_ca_mau.py data/policy-pack',
      apiBase: 'https://api.vt.local/api/v1', apiCredentialsId: 'mission-ci-policy-pack', apiCa: '',
      packIds: [:],                     // tên gói → id Policy Pack ở máy chủ (MOD-05)
      shadow: true, shadowCompareWithVersion: '', approverGroup: 'policy-pack-approvers',
      effectivePeriodId: '', scopes: [],
    ],
    uiPack: [
      service: 'ui-pack', dir: 'ui-packs', packFile: 'pack.json', uiPackId: '',
      registrySources: ['frontend/projects/**/widgets/**/*.ts', 'frontend/projects/sdui-renderer/src/lib/builtin-components.ts',
                        'frontend/projects/fe-shell/src/app/widgets/*.ts'],
      cssBudgetKb: 30, stagingUrl: '',
      apiBase: 'https://api.vt.local/api/v1', apiCredentialsId: 'mission-ci-ui-pack', apiCa: '',
      approverGroup: 'ui-pack-approvers',
    ],
  ]

  /** Biến controller thêm cho INF-06 (ngoài BIEN_MOI_TRUONG của CauHinh). */
  static final Map BIEN_THEM = [
    VT_IMAGE_BUILDER : 'imageBuilder',      // buildkit (cụm) | docker (máy kiểm thử)
    VT_API_BASE      : 'apiBase',
    VT_API_CA        : 'apiCa',
    VT_STAGING_URL   : 'stagingUrl',
    VT_CHART_REPO    : 'chartRepo',
    VT_CHART_PUSH_FLAGS: 'chartPushFlags',   // ví dụ --plain-http cho registry cục bộ của máy kiểm thử
  ]

  @NonCPS
  static Map ghep(String loai, Map thamSo, Map envVars) {
    Map rieng = RIENG[loai]
    if (rieng == null) {
      throw new IllegalArgumentException("CauHinhLoai: loại không hỗ trợ '${loai}'")
    }
    Map cfg = [:]
    KHOA_CHUNG.each { cfg[it] = CauHinh.MAC_DINH[it] }
    cfg.putAll(rieng)
    cfg.loai = loai
    thamSo.each { k, v ->
      if (!cfg.containsKey(k) || k == 'loai') {
        throw new IllegalArgumentException("vt-pipeline (${loai}): tham số không hỗ trợ '${k}'")
      }
      cfg[k] = v
    }
    (CauHinh.BIEN_MOI_TRUONG + BIEN_THEM).each { bien, khoa ->
      String giaTri = envVars?.get(bien)
      if (giaTri != null && giaTri != '' && cfg.containsKey(khoa)) {
        cfg[khoa] = CauHinh.chuyenKieu(khoa, giaTri == '-' ? '' : giaTri)
      }
    }
    kiemTra(cfg)
    return cfg
  }

  @NonCPS
  static void kiemTra(Map cfg) {
    if (!(cfg.service ==~ /[a-z][a-z0-9-]{1,62}/)) {
      throw new IllegalArgumentException("vt-pipeline (${cfg.loai}): 'service' phải là tên theo baseline (chữ thường, số, gạch nối)")
    }
    if (!(cfg.dir ==~ /[A-Za-z0-9._\/-]+/) || cfg.dir.contains('..')) {
      throw new IllegalArgumentException("vt-pipeline (${cfg.loai}): 'dir' không hợp lệ")
    }
    if (cfg.registry.contains(':latest') || cfg.registry.endsWith('/')) {
      throw new IllegalArgumentException("vt-pipeline (${cfg.loai}): 'registry' không hợp lệ")
    }
    if (!(cfg.coverageThreshold instanceof Number) || cfg.coverageThreshold < 80.0) {
      throw new IllegalArgumentException("vt-pipeline (${cfg.loai}): ngưỡng độ phủ không được thấp hơn 80% (J1, J3)")
    }
    if (cfg.containsKey('imageBuilder') && !(cfg.imageBuilder in ['buildkit', 'docker'])) {
      throw new IllegalArgumentException("vt-pipeline (${cfg.loai}): imageBuilder chỉ nhận buildkit hoặc docker")
    }
    if (cfg.containsKey('cssBudgetKb') && cfg.cssBudgetKb > 30) {
      throw new IllegalArgumentException('vt-pipeline (uiPack): ngân sách CSS không được vượt 30 KB gzip (TL-01)')
    }
  }
}
