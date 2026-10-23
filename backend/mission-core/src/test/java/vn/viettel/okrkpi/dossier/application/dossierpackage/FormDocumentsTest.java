package vn.viettel.okrkpi.dossier.application.dossierpackage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.Pack;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.UnitProfile;

/** CORE-06-06: tài liệu JSON chuẩn tắc của biểu mẫu và băm {@code data_snapshot_hash}. */
class FormDocumentsTest {

  static final UUID PERIOD = UUID.fromString("01927a06-0000-7000-8000-000000000001");
  static final UUID UNIT = UUID.fromString("01927a06-0000-7000-8000-000000000002");
  static final Pack PACK =
      new Pack("PP-DANG", "1.3.0", List.of("BANG_CHAM_DIEM"), List.of("MAU_3"), Map.of());

  static UnitProfile profile(String id, String status, BigDecimal score) {
    return new UnitProfile(
        UUID.fromString(id),
        UUID.fromString("01927a06-0000-7000-8000-0000000000aa"),
        PERIOD,
        20260,
        UNIT,
        "DANG",
        status,
        "HTTNV",
        score,
        null,
        false,
        PACK);
  }

  @Test
  void bieuMauCaNhanChuanTacVaBamKhop() throws Exception {
    UnitProfile p =
        profile("01927a06-0000-7000-8000-000000000010", "DA_QUYET_DINH", new BigDecimal("85.50"));
    FormDocuments.Document doc = FormDocuments.personal("BANG_CHAM_DIEM", p);
    String json = new String(doc.canonicalJson(), StandardCharsets.UTF_8);
    assertThat(json)
        .startsWith("{\"boQuyTac\":{\"ma\":\"PP-DANG\",\"phienBan\":\"1.3.0\"}")
        .contains("\"tongDiem\":\"85.5\"")
        .contains("\"maMau\":\"BANG_CHAM_DIEM\"")
        .contains("\"anhChupDiemId\":null");
    assertThat(doc.sha256())
        .isEqualTo(MessageDigest.getInstance("SHA-256").digest(doc.canonicalJson()));
    assertThat(FormDocuments.personal("BANG_CHAM_DIEM", p).sha256()).isEqualTo(doc.sha256());
  }

  @Test
  void bieuMauDonViChiLayHoSoDaQuyetDinhTheoThuTuMa() {
    UnitProfile b =
        profile("01927a06-0000-7000-8000-000000000020", "DA_KY_SO", new BigDecimal("100"));
    UnitProfile a =
        profile("01927a06-0000-7000-8000-000000000011", "DA_QUYET_DINH", new BigDecimal("1E+2"));
    UnitProfile open = profile("01927a06-0000-7000-8000-000000000015", "CHO_QUYET_DINH", null);
    FormDocuments.Document doc =
        FormDocuments.unit("MAU_3", PERIOD, UNIT, PACK, List.of(b, open, a));
    String json = new String(doc.canonicalJson(), StandardCharsets.UTF_8);
    assertThat(json.indexOf(a.id().toString())).isLessThan(json.indexOf(b.id().toString()));
    assertThat(json).doesNotContain(open.id().toString()).contains("\"tongDiem\":\"100\"");
    assertThat(FormDocuments.unit("MAU_3", PERIOD, UNIT, null, List.of()).canonicalJson())
        .asString(StandardCharsets.UTF_8)
        .contains("\"boQuyTac\":{\"ma\":null,\"phienBan\":null}")
        .contains("\"danhSachHoSo\":[]");
  }
}
