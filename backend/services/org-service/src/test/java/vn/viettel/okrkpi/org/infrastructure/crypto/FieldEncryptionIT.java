package vn.viettel.okrkpi.org.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.org.application.port.FieldEncryptor;
import vn.viettel.okrkpi.org.support.AesGcmTestFieldEncryptor;
import vn.viettel.okrkpi.org.support.Fixtures;
import vn.viettel.okrkpi.org.support.PostgresSupport;

/** Cột C2 của {@code org.person} chỉ chứa bản mã; tra cứu bằng chỉ mục mù; ràng buộc {@code ck_person__dek}. */
class FieldEncryptionIT {

  private static final UUID TENANT = UUID.randomUUID();
  private final Fixtures fx = new Fixtures();

  @Test
  void hienThucMacDinhTuChoiMoiThaoTac() {
    FieldEncryptor none = new UnavailableFieldEncryptor();
    assertThatThrownBy(() -> none.encrypt(TENANT, "org.person.national_id_enc", UUID.randomUUID(), new byte[1]))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> none.decrypt(TENANT, "c", UUID.randomUUID(), UUID.randomUUID(), new byte[1]))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> none.blindIndex(TENANT, "c", "x")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void ghiBanMaVaTraCuuBangChiMucMu() throws SQLException {
    AesGcmTestFieldEncryptor enc = new AesGcmTestFieldEncryptor();
    String cccd = "001099012345";
    UUID versionId = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    FieldEncryptor.Encrypted ct = enc.encrypt(TENANT, "org.person.national_id_enc", id,
        cccd.getBytes(StandardCharsets.UTF_8));
    byte[] bidx = enc.blindIndex(TENANT, "org.person.national_id_bidx", cccd);
    try (Connection c = PostgresSupport.as("svc_org", PostgresSupport.SVC_PASSWORD)) {
      c.setAutoCommit(false);
      try (PreparedStatement p = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
        p.setString(1, TENANT.toString());
        p.execute();
      }
      try (PreparedStatement p = c.prepareStatement("INSERT INTO org.person (version_id, id, tenant_id, full_name, "
          + "employment_status, national_id_enc, national_id_bidx, dek_id, valid_from, created_by, updated_by) "
          + "VALUES (?, ?, ?, 'Hoàng Văn E', 'DANG_CONG_TAC', ?, ?, ?, '2020-01-01', ?, ?)")) {
        p.setObject(1, versionId);
        p.setObject(2, id);
        p.setObject(3, TENANT);
        p.setBytes(4, ct.ciphertext());
        p.setBytes(5, bidx);
        p.setObject(6, ct.dekId());
        p.setObject(7, Fixtures.SYSTEM_ACTOR);
        p.setObject(8, Fixtures.SYSTEM_ACTOR);
        p.executeUpdate();
      }
      try (PreparedStatement p = c.prepareStatement("SELECT id, national_id_enc, dek_id FROM org.person "
          + "WHERE national_id_bidx = ?")) {
        p.setBytes(1, enc.blindIndex(TENANT, "org.person.national_id_bidx", cccd));
        try (ResultSet rs = p.executeQuery()) {
          assertThat(rs.next()).isTrue();
          byte[] stored = rs.getBytes("national_id_enc");
          assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(cccd);
          assertThat(new String(enc.decrypt(TENANT, "org.person.national_id_enc", id,
              rs.getObject("dek_id", UUID.class), stored), StandardCharsets.UTF_8)).isEqualTo(cccd);
          // AAD gắn row_id: chép bản mã sang dòng khác thì không giải mã được.
          assertThatThrownBy(() -> enc.decrypt(TENANT, "org.person.national_id_enc", UUID.randomUUID(),
              enc.dekId(), stored)).isInstanceOf(IllegalStateException.class);
        }
      }
      c.commit();
    }
    // Cột _enc không có dek_id bị CHECK chặn.
    assertThatThrownBy(() -> fx.admin().update("INSERT INTO org.person (version_id, id, tenant_id, full_name, "
        + "employment_status, national_id_enc, valid_from, created_by, updated_by) VALUES (gen_random_uuid(), "
        + "gen_random_uuid(), ?, 'X', 'DANG_CONG_TAC', '\\x00'::bytea, '2020-01-01', ?, ?)", TENANT,
        Fixtures.SYSTEM_ACTOR, Fixtures.SYSTEM_ACTOR)).hasMessageContaining("ck_person__dek");
  }
}
