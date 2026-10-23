package vn.viettel.okrkpi.workflow.domain.replica;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Trạng thái đầy đủ của một lát hiệu lực đơn vị mang trong EVT-14 {@code org.unit.changed.v1} hoặc
 * {@code org.bootstrap.v1} (ECST), áp vào bản sao {@code org_replica.org_unit_v} của
 * workflow-service (TL-03 "Bảng org_replica.org_unit_v"). Khoá bản sao là {@code (tenant_id, id,
 * valid_from)}; đơn vị giải thể biểu diễn bằng {@code validTo}, không có xoá.
 *
 * @param id mã đơn vị ổn định
 * @param validFrom ngày bắt đầu hiệu lực
 * @param validTo ngày hết hiệu lực (không gồm ngày này), {@code null} là vô thời hạn
 * @param code mã đơn vị nội bộ
 * @param name tên đơn vị
 * @param unitType loại đơn vị ({@code TO_CHUC_DANG}, {@code CHI_BO}, {@code CO_QUAN}...)
 * @param partyParentId cấp trên trên cây Đảng tại {@code validFrom}, {@code null} là gốc
 * @param adminParentId cấp trên trên cây chuyên môn tại {@code validFrom}, {@code null} là gốc
 * @param subjectGroupId khối đối tượng, có thể {@code null}
 * @param sourceVersion phiên bản ở nguồn, đơn điệu tăng
 */
public record OrgUnitReplica(
    UUID id,
    LocalDate validFrom,
    LocalDate validTo,
    String code,
    String name,
    String unitType,
    UUID partyParentId,
    UUID adminParentId,
    UUID subjectGroupId,
    long sourceVersion) {

  /** Độ dài tối đa của cột {@code code varchar(64)}. */
  public static final int MAX_CODE = 64;

  /** Độ dài tối đa của cột {@code name varchar(255)}. */
  public static final int MAX_NAME = 255;

  /** Độ dài tối đa của cột {@code unit_type varchar(24)}. */
  public static final int MAX_UNIT_TYPE = 24;

  public OrgUnitReplica {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(validFrom, "validFrom");
    text(code, MAX_CODE, "Mã đơn vị");
    text(name, MAX_NAME, "Tên đơn vị");
    text(unitType, MAX_UNIT_TYPE, "Loại đơn vị");
    if (validTo != null && !validTo.isAfter(validFrom)) {
      throw new IllegalArgumentException("Ngày hết hiệu lực phải sau ngày bắt đầu hiệu lực");
    }
    if (sourceVersion < 0) {
      throw new IllegalArgumentException("Phiên bản nguồn không được âm");
    }
  }

  private static void text(String value, int max, String what) {
    if (value == null || value.isBlank() || value.length() > max) {
      throw new IllegalArgumentException(what + " rỗng hoặc quá " + max + " ký tự");
    }
  }

  /** Cấp trên trực tiếp trên cây {@code tree}, {@code null} là gốc. */
  public UUID parentOn(OrgTree tree) {
    return tree == OrgTree.DANG ? partyParentId : adminParentId;
  }

  /**
   * Phiên bản mở (không có ngày kết thúc) quyết định cây hiện hành, nên chỉ phiên bản này cần dò
   * vòng (suy luận, như bản sao của mission-core).
   */
  public boolean openEnded() {
    return validTo == null;
  }
}
