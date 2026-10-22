package vn.viettel.okrkpi.dossier.domain.specialcase;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Một phân công trong bản sao tổ chức ({@code org_replica.assignment_v}); {@code kind} theo TL-03
 * {@code CHINH}, {@code KIEM_NHIEM}, {@code DIEU_DONG}, {@code BIET_PHAI}, {@code TAM_GIU}; {@code
 * validTo} rỗng là vô hạn.
 */
public record AssignmentFact(
    UUID id, UUID orgUnitId, String kind, boolean head, LocalDate validFrom, LocalDate validTo) {

  public static final String CHINH = "CHINH";
  public static final String KIEM_NHIEM = "KIEM_NHIEM";
  public static final String DIEU_DONG = "DIEU_DONG";
  public static final String BIET_PHAI = "BIET_PHAI";

  public AssignmentFact {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(orgUnitId, "orgUnitId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(validFrom, "validFrom");
  }

  /** Vị trí công tác chính: phân công chính hoặc điều động (suy luận theo TL-03 {@code kind}). */
  public boolean seat() {
    return CHINH.equals(kind) || DIEU_DONG.equals(kind);
  }

  /** Có hiệu lực tại ngày {@code day}. */
  public boolean covers(LocalDate day) {
    return !validFrom.isAfter(day) && (validTo == null || !validTo.isBefore(day));
  }

  /** Có hiệu lực ít nhất một ngày trong [{@code from}, {@code to}]. */
  public boolean overlaps(LocalDate from, LocalDate to) {
    return !validFrom.isAfter(to) && (validTo == null || !validTo.isBefore(from));
  }
}
