package vn.viettel.okrkpi.replica.domain;

import java.time.LocalDate;
import java.util.UUID;

/** Trạng thái đơn vị mang trong EVT-14; {@code validTo} rỗng là vô thời hạn. */
public record UnitSnapshot(
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

  /** Phiên bản đang hiệu lực mở (không có ngày kết thúc) quyết định cây hiện hành (suy luận). */
  public boolean openEnded() {
    return validTo == null;
  }
}
