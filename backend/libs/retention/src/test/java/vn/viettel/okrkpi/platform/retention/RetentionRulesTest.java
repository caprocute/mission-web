package vn.viettel.okrkpi.platform.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.Period;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.shared.crypto.DekPurpose;

/** DL-13: quy tắc thuần của lớp lưu giữ, kỳ, ánh xạ bảng và kiểu cột Parquet. */
class RetentionRulesTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void periodBucketEndsAfterQuarterOrYear() {
    assertThat(PeriodBucket.end(20231)).isEqualTo(LocalDate.of(2023, 4, 1));
    assertThat(PeriodBucket.end(20234)).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(PeriodBucket.end(20230)).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThatThrownBy(() -> PeriodBucket.end(20235)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parseFallsBackToDefaultsAndLongestKeepsLongerTerms() throws Exception {
    RetentionClass.Rule bad =
        RetentionClass.SO_CAI.parse(JSON.readTree("{\"hot\":\"P1Y\",\"total\":\"P9Y\"}"));
    assertThat(bad).isEqualTo(RetentionClass.SO_CAI.defaults());
    RetentionClass.Rule a =
        RetentionClass.THUC_HIEN.parse(JSON.readTree("{\"hot\":\"P1Y\",\"total\":\"P12Y\"}"));
    RetentionClass.Rule l = a.longest(RetentionClass.THUC_HIEN.defaults());
    assertThat(l.hot()).isEqualTo(Period.ofYears(3));
    assertThat(l.total()).isEqualTo(Period.ofYears(12));
    assertThat(l.proposed()).isFalse();
  }

  @Test
  void classMapUsesSchemaDefaultsTechnicalTablesAndOverrides() {
    Properties p = new Properties();
    p.setProperty("lop.task.bang_dac_biet", "HO_SO_CAN_BO");
    p.setProperty("muc-dich.VET_TINH", "NHAN_XET_DANH_GIA, GOP_Y_KIEM_DIEM");
    ClassMap m = new ClassMap(p);
    assertThat(m.of("appraisal", "self_review")).isEqualTo(RetentionClass.HO_SO_CAN_BO);
    assertThat(m.of("task", "outbox_event")).isEqualTo(RetentionClass.KY_THUAT);
    assertThat(m.of("task", "bang_dac_biet")).isEqualTo(RetentionClass.HO_SO_CAN_BO);
    assertThat(m.of("la", "x")).isNull();
    assertThat(m.purposes(RetentionClass.THUC_HIEN)).containsExactly(DekPurpose.TEP_MINH_CHUNG);
    assertThat(m.purposes(RetentionClass.VET_TINH))
        .containsExactly(DekPurpose.NHAN_XET_DANH_GIA, DekPurpose.GOP_Y_KIEM_DIEM);
  }

  @Test
  void parquetTypeMappingAndIdentifierGuard() {
    assertThat(ParquetCodec.toDuck("c0", "numeric(18,2)")).isEqualTo("CAST(c0 AS DECIMAL(18,2))");
    assertThat(ParquetCodec.toDuck("c0", "numeric")).isEqualTo("c0");
    assertThat(ParquetCodec.toDuck("c0", "bytea")).isEqualTo("from_hex(substr(c0, 3))");
    assertThat(ParquetCodec.toDuck("c0", "jsonb")).isEqualTo("c0");
    assertThatThrownBy(() -> ParquetCodec.quote("x\"; DROP TABLE y; --"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
