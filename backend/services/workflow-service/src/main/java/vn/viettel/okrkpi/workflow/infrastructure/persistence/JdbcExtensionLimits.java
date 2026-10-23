package vn.viettel.okrkpi.workflow.infrastructure.persistence;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.viettel.okrkpi.workflow.application.port.ExtensionLimits;

/**
 * {@link ExtensionLimits} đọc khoá {@code gia_han_toi_da_ngay_lam_viec} của {@code
 * flowable.wf_binding.params_json} (tham số mẫu luồng của tenant, suy luận DV-06-10) trên các dòng
 * {@code HIEU_LUC} dùng mẫu có mã tương ứng; nhiều dòng thì lấy giá trị nhỏ nhất (chặt nhất).
 */
@Repository
public class JdbcExtensionLimits implements ExtensionLimits {

  /** Khoá tham số trong {@code params_json}. */
  public static final String PARAM = "gia_han_toi_da_ngay_lam_viec";

  private final JdbcTemplate jdbc;

  public JdbcExtensionLimits(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public OptionalInt maxWorkingDays(UUID tenantId, String templateCode) {
    if (templateCode == null) {
      return OptionalInt.empty();
    }
    TenantBinding.bind(jdbc, tenantId);
    List<String> values =
        jdbc.queryForList(
            "SELECT b.params_json ->> ? FROM flowable.wf_binding b"
                + " JOIN flowable.wf_template t ON t.id = b.wf_template_id"
                + " WHERE b.tenant_id = ? AND t.template_code = ? AND b.status = 'HIEU_LUC'"
                + " AND b.params_json ->> ? IS NOT NULL",
            String.class,
            PARAM,
            tenantId,
            templateCode,
            PARAM);
    return values.stream()
        .map(JdbcExtensionLimits::parse)
        .filter(OptionalInt::isPresent)
        .mapToInt(OptionalInt::getAsInt)
        .min();
  }

  static OptionalInt parse(String value) {
    if (value == null) {
      return OptionalInt.empty();
    }
    try {
      int days = Integer.parseInt(value.strip());
      return days < 0 ? OptionalInt.empty() : OptionalInt.of(days);
    } catch (NumberFormatException e) {
      return OptionalInt.empty();
    }
  }
}
