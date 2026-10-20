package vn.vt.pipeline

import com.cloudbees.groovy.cps.NonCPS

/**
 * Cấu hình của vtJavaPipeline: giá trị mặc định theo TL-04 mục 9.6 (Jenkinsfile mẫu), ghép với tham số
 * của Jenkinsfile và với biến môi trường cấp controller `VT_*` (INF-05).
 *
 * Thứ tự ưu tiên: biến môi trường `VT_*` của controller > tham số trong Jenkinsfile > mặc định.
 * Biến `VT_*` do quản trị Jenkins đặt cho từng controller (ví dụ controller thử nghiệm dùng registry khác);
 * mã của dự án không đặt được biến cấp controller, nên đây không phải đường vòng để bỏ cổng: không có biến
 * nào tắt gitleaks, Semgrep, Trivy, kiểm thử hay openapi-diff.
 */
class CauHinh implements Serializable {

  /** Giá trị mặc định; khoá nào không có ở đây thì không được nhận từ Jenkinsfile. */
  static final Map MAC_DINH = [
    service              : null,                                // bắt buộc, tên theo baseline (vd. mission-core)
    dir                  : 'backend',                           // thư mục dự án Maven trong monorepo
    registry             : 'harbor.vt.local/okr',
    baseImage            : '',                                  // rỗng: dùng jib.from.image của POM (đã ghim digest)
    jibModule            : '',                                  // mô-đun Maven dựng ảnh khi dự án nhiều mô-đun
    mavenProfile         : 'ci',
    goldenModule         : '',                                  // vd. policy-pack-tests khi có Policy Pack (TL-04)
    semgrepConfigs       : ['p/java', '.semgrep/vt-rules.yml'],
    gitleaksConfig       : '.gitleaks.toml',
    coverageThreshold    : 80.0,                                // % dòng trên tệp thay đổi (J1, J3)
    contractsDir         : 'contracts/openapi',
    contractsBaseRef     : '',                                  // rỗng: origin/<nhánh đích> của MR, HEAD~1 trên main
    openapiDiffCmd       : 'openapi-diff',
    sonar                : true,
    sonarServer          : 'sonarqube',
    sonarGoal            : 'sonar:sonar',
    pact                 : false,                               // bật khi QA-02 dựng xong Pact Broker
    pactBrokerUrl        : 'https://pact.vt.local',
    pactCredentialsId    : 'pact-broker-token',
    dependencyTrack      : true,
    dependencyTrackUrl   : 'https://dtrack.vt.local',
    dependencyTrackCredentialsId: 'dependency-track-api-key',
    registryCredentialsId: 'harbor-robot-okr',                  // rỗng: registry không cần đăng nhập
    cosignKey            : 'hashivault://cosign-release',       // khoá trong OpenBao Transit (SEC-38)
    cosignVerifyKey      : '',                                  // rỗng: dùng cosignKey
    cosignVaultTokenCredentialsId: 'openbao-cosign-token',      // rỗng: không nạp VAULT_TOKEN
    vaultAddr            : 'https://openbao.vt.local',
    provenanceContainer  : 'provenance',                        // container riêng sinh và ký provenance (SLSA L3)
    builderId            : 'https://jenkins.vt.local/okr-builder',
    releaseBranch        : 'main',
    gitopsEnv            : 'staging',
    gitopsRepo           : '',                                  // rỗng: chính repo đang dựng (deploy/ trong monorepo)
    gitopsBranch         : 'main',
    gitopsCredentialsId  : 'gitops-push',
    gitopsSigningKeyCredentialsId: 'gitops-signing-gpg',
    notifyChannel        : 'okr-ci',
  ]

  /** Biến môi trường controller → khoá cấu hình. Giá trị `-` nghĩa là để trống. */
  static final Map BIEN_MOI_TRUONG = [
    VT_REGISTRY                 : 'registry',
    VT_BASE_IMAGE               : 'baseImage',
    VT_SEMGREP_CONFIGS          : 'semgrepConfigs',              // cách nhau bằng dấu phẩy
    VT_OPENAPI_DIFF_CMD         : 'openapiDiffCmd',
    VT_SONAR                    : 'sonar',
    VT_PACT                     : 'pact',
    VT_DEPENDENCY_TRACK         : 'dependencyTrack',
    VT_DEPENDENCY_TRACK_URL     : 'dependencyTrackUrl',
    VT_REGISTRY_CREDENTIALS_ID  : 'registryCredentialsId',
    VT_COSIGN_KEY               : 'cosignKey',
    VT_COSIGN_VERIFY_KEY        : 'cosignVerifyKey',
    VT_VAULT_ADDR               : 'vaultAddr',
    VT_BUILDER_ID               : 'builderId',
    VT_GITOPS_REPO              : 'gitopsRepo',
    VT_GITOPS_CREDENTIALS_ID    : 'gitopsCredentialsId',
  ]

  static final List KIEU_BOOL = ['sonar', 'pact', 'dependencyTrack']
  static final List KIEU_DANH_SACH = ['semgrepConfigs']

  /** Ghép cấu hình; `envVars` là bản đồ biến môi trường (env.getEnvironment()). */
  @NonCPS
  static Map ghep(Map thamSo, Map envVars) {
    Map cfg = [:]
    cfg.putAll(MAC_DINH)
    thamSo.each { k, v ->
      if (!MAC_DINH.containsKey(k)) {
        throw new IllegalArgumentException("vtJavaPipeline: tham số không hỗ trợ '${k}'")
      }
      cfg[k] = v
    }
    BIEN_MOI_TRUONG.each { bien, khoa ->
      String giaTri = envVars?.get(bien)
      if (giaTri != null && giaTri != '') {
        cfg[khoa] = chuyenKieu(khoa, giaTri == '-' ? '' : giaTri)
      }
    }
    kiemTra(cfg)
    return cfg
  }

  @NonCPS
  static Object chuyenKieu(String khoa, String giaTri) {
    if (KIEU_BOOL.contains(khoa)) {
      return giaTri.toBoolean()
    }
    if (KIEU_DANH_SACH.contains(khoa)) {
      return giaTri.split(',').collect { it.trim() }.findAll { it }
    }
    return giaTri
  }

  @NonCPS
  static void kiemTra(Map cfg) {
    if (!(cfg.service ==~ /[a-z][a-z0-9-]{1,62}/)) {
      throw new IllegalArgumentException("vtJavaPipeline: 'service' phải là tên service theo baseline (chữ thường, số, gạch nối)")
    }
    if (!(cfg.dir ==~ /[A-Za-z0-9._\/-]+/) || cfg.dir.contains('..')) {
      throw new IllegalArgumentException("vtJavaPipeline: 'dir' không hợp lệ")
    }
    if (cfg.baseImage && !(cfg.baseImage ==~ /.+@sha256:[0-9a-f]{64}/)) {
      throw new IllegalArgumentException('vtJavaPipeline: ảnh nền phải ghim theo digest @sha256 (SEC-55)')
    }
    if (cfg.registry.contains(':latest') || cfg.registry.endsWith('/')) {
      throw new IllegalArgumentException("vtJavaPipeline: 'registry' không hợp lệ")
    }
    if (!(cfg.coverageThreshold instanceof Number) || cfg.coverageThreshold < 80.0) {
      throw new IllegalArgumentException('vtJavaPipeline: ngưỡng độ phủ không được thấp hơn 80% (J1, J3)')
    }
  }

  /** Dải commit cho gitleaks: commit của MR so với nhánh đích; ngoài MR thì commit mới nhất. */
  @NonCPS
  static String daiCommitGitleaks(Map envVars) {
    String dich = envVars?.get('CHANGE_TARGET')
    return dich ? "origin/${dich}..HEAD" : '-1'
  }

  /** Mốc so sánh hợp đồng OpenAPI (ADR-32): nhánh đích của MR, hoặc commit trước trên nhánh phát hành. */
  @NonCPS
  static String mocHopDong(Map cfg, Map envVars) {
    if (cfg.contractsBaseRef) {
      return cfg.contractsBaseRef
    }
    String dich = envVars?.get('CHANGE_TARGET')
    return dich ? "origin/${dich}" : 'HEAD~1'
  }
}
