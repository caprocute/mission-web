package vn.viettel.okrkpi.org.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor;
import vn.viettel.okrkpi.platform.db.migration.MigrationCredentials;
import vn.viettel.okrkpi.platform.db.migration.MigrationOptions;
import vn.viettel.okrkpi.platform.db.migration.SchemaMigrator;
import vn.viettel.okrkpi.platform.db.migration.SchemaNames;
import vn.viettel.okrkpi.platform.db.migration.TestPostgres;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseBootstrap;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.DatabaseLayout;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleBootstrapPlan;
import vn.viettel.okrkpi.platform.db.migration.bootstrap.RoleLimits;
import vn.viettel.okrkpi.shared.crypto.BlindIndexer;
import vn.viettel.okrkpi.shared.crypto.CryptoException;
import vn.viettel.okrkpi.shared.crypto.CryptoProperties;
import vn.viettel.okrkpi.shared.crypto.DekCache;
import vn.viettel.okrkpi.shared.crypto.DekService;
import vn.viettel.okrkpi.shared.crypto.JcaAesGcmCryptoProvider;
import vn.viettel.okrkpi.shared.crypto.OpenBaoTransitClient;

/**
 * Mã hoá trường C2 của org-service bằng thư viện NEN-015 thật: PostgreSQL 17 có {@code keyring.dek} (RLS FORCE, vai
 * trò {@code svc_org} như môi trường thật) và OpenBao 2.2.0 (cùng bản của hạ tầng dev) chế độ dev có Transit. Trước bản này org-service chỉ có
 * hiện thực từ chối, nên lần đồng bộ nhân sự không ghi được số định danh, ngày sinh (HT-07).
 */
class KeyringFieldEncryptorIT {
  private static final String PASSWORD = "mat-khau-thu-nghiem";
  private static final String ROOT = "kiem-thu-" + UUID.randomUUID();
  private static final String COL_NATIONAL = "org.person.national_id_enc";

  private static PostgreSQLContainer<?> pg;
  private static GenericContainer<?> bao;
  private static Path tokenFile;
  private static CryptoProperties props;

  @TempDir
  static Path dir;

  @BeforeAll
  static void start() throws Exception {
    pg = TestPostgres.container("td_org_crypto");
    pg.start();
    Map<String, String> env = new HashMap<>();
    for (String role : RoleBootstrapPlan.roles(DatabaseLayout.TENANT_DATA)) {
      env.put(role.toUpperCase(Locale.ROOT) + "_PASSWORD", PASSWORD);
    }
    try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      new DatabaseBootstrap(RoleLimits.load(), env).apply(admin, DatabaseLayout.TENANT_DATA);
    }
    new SchemaMigrator(MigrationOptions.defaults()).migrate(pg.getJdbcUrl(), "keyring",
        new MigrationCredentials.Credentials(SchemaNames.migrationRole("keyring"), PASSWORD));

    bao = new GenericContainer<>("quay.io/openbao/openbao:2.2.0")
        .withCommand("server", "-dev", "-dev-root-token-id=" + ROOT, "-dev-listen-address=0.0.0.0:8200")
        .withExposedPorts(8200)
        .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)));
    bao.start();
    var res = bao.execInContainer("sh", "-c",
        "BAO_ADDR=http://127.0.0.1:8200 BAO_TOKEN=" + ROOT + " bao secrets enable transit");
    assertThat(res.getExitCode()).as(res.getStderr()).isZero();
    tokenFile = Files.writeString(dir.resolve("token"), ROOT, StandardCharsets.UTF_8);

    props = new CryptoProperties();
    props.setOpenBaoBaseUrl(java.net.URI.create("http://" + bao.getHost() + ":" + bao.getMappedPort(8200)));
    props.setTokenFile(tokenFile);
    props.setAutoCreateKek(true);
  }

  @AfterAll
  static void stop() {
    if (bao != null) {
      bao.stop();
    }
    if (pg != null) {
      pg.stop();
    }
  }

  private static KeyringFieldEncryptor encryptor(DekCache cache) {
    JdbcTemplate svcOrg = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), "svc_org", PASSWORD));
    var provider = new JcaAesGcmCryptoProvider();
    var transit = new OpenBaoTransitClient(props, new ObjectMapper(), Clock.systemUTC());
    var dek = new DekService(svcOrg, transit, provider, cache, Clock.systemUTC());
    return new KeyringFieldEncryptor(new vn.viettel.okrkpi.shared.crypto.FieldEncryptor(dek, provider),
        new BlindIndexer(dek, provider));
  }

  @Test
  void maHoaGiaiMaBangDekTrongKeyringVaGanVaoDongCot() {
    DekCache cache = new DekCache(100, Duration.ofMinutes(5), Clock.systemUTC());
    KeyringFieldEncryptor enc = encryptor(cache);
    UUID tenant = UUID.randomUUID();
    UUID row = UUID.randomUUID();
    byte[] plain = "001090012345".getBytes(StandardCharsets.UTF_8);

    FieldEncryptor.Encrypted e = enc.encrypt(tenant, COL_NATIONAL, row, plain);
    assertThat(e.dekId()).isNotNull();
    assertThat(new String(e.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("001090012345");

    // Bỏ đệm để lần giải mã đọc DEK từ keyring.dek và mở bọc qua OpenBao Transit
    cache.clear();
    assertThat(enc.decrypt(tenant, COL_NATIONAL, row, e.dekId(), e.ciphertext())).isEqualTo(plain);

    // AAD gắn bản mã vào đúng dòng, đúng cột, đúng tenant
    assertThatThrownBy(() -> enc.decrypt(tenant, COL_NATIONAL, UUID.randomUUID(), e.dekId(), e.ciphertext()))
        .isInstanceOf(CryptoException.class);
    assertThatThrownBy(() -> enc.decrypt(tenant, "org.person.birth_date_enc", row, e.dekId(), e.ciphertext()))
        .isInstanceOf(CryptoException.class);
    assertThatThrownBy(() -> enc.decrypt(UUID.randomUUID(), COL_NATIONAL, row, e.dekId(), e.ciphertext()))
        .isInstanceOf(CryptoException.class);
  }

  @Test
  void chiMucMuOnDinhTheoTenantVaCot() {
    KeyringFieldEncryptor enc = encryptor(new DekCache(100, Duration.ofMinutes(5), Clock.systemUTC()));
    UUID tenant = UUID.randomUUID();
    byte[] a = enc.blindIndex(tenant, "org.person.national_id_bidx", "001090012345");
    assertThat(a).hasSize(BlindIndexer.BLIND_INDEX_LENGTH);
    assertThat(enc.blindIndex(tenant, "org.person.national_id_bidx", "001090012345")).isEqualTo(a);
    assertThat(enc.blindIndex(tenant, "org.sync_record.fingerprint", "001090012345")).isNotEqualTo(a);
    assertThat(enc.blindIndex(UUID.randomUUID(), "org.person.national_id_bidx", "001090012345")).isNotEqualTo(a);
  }

  @Test
  void cauHinhChonThuVienKhiCoXacThucOpenBaoNeuKhongThiTuChoi() {
    FieldEncryptorConfig config = new FieldEncryptorConfig();
    StaticListableBeanFactory none = new StaticListableBeanFactory();
    FieldEncryptor off = config.orgFieldEncryptor(none.getBeanProvider(CryptoProperties.class),
        none.getBeanProvider(vn.viettel.okrkpi.shared.crypto.FieldEncryptor.class),
        none.getBeanProvider(BlindIndexer.class));
    assertThat(off).isInstanceOf(UnavailableFieldEncryptor.class);

    KeyringFieldEncryptor real = encryptor(new DekCache(100, Duration.ofMinutes(5), Clock.systemUTC()));
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("props", props);
    var dekProvider = new JcaAesGcmCryptoProvider();
    var transit = new OpenBaoTransitClient(props, new ObjectMapper(), Clock.systemUTC());
    var dek = new DekService(new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), "svc_org", PASSWORD)),
        transit, dekProvider, new DekCache(100, Duration.ofMinutes(5), Clock.systemUTC()), Clock.systemUTC());
    beans.addBean("enc", new vn.viettel.okrkpi.shared.crypto.FieldEncryptor(dek, dekProvider));
    beans.addBean("idx", new BlindIndexer(dek, dekProvider));
    FieldEncryptor on = config.orgFieldEncryptor(beans.getBeanProvider(CryptoProperties.class),
        beans.getBeanProvider(vn.viettel.okrkpi.shared.crypto.FieldEncryptor.class),
        beans.getBeanProvider(BlindIndexer.class));
    assertThat(on).isInstanceOf(KeyringFieldEncryptor.class);
    UUID tenant = UUID.randomUUID();
    UUID row = UUID.randomUUID();
    FieldEncryptor.Encrypted e = on.encrypt(tenant, COL_NATIONAL, row, new byte[] {1, 2, 3});
    assertThat(real.decrypt(tenant, COL_NATIONAL, row, e.dekId(), e.ciphertext())).containsExactly(1, 2, 3);
  }
}
