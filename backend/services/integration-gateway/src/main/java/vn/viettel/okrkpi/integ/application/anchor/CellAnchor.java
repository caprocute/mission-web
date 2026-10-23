package vn.viettel.okrkpi.integ.application.anchor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Gói neo cấp cell của một ngày do ledger-service cấp (DV-09-03, {@code ledger.cell_seal}): gốc
 * cell 32 byte, gốc ngày trước, chữ ký của cell, token TSA RFC 3161. Mọi giá trị là băm, chữ ký
 * hoặc token, không có dữ liệu nghiệp vụ (C1).
 */
public record CellAnchor(
    UUID cellSealId,
    String cellCode,
    Instant windowStart,
    Instant windowEnd,
    byte[] cellRoot,
    byte[] prevCellRoot,
    byte[] signature,
    String signerCertSha256,
    byte[] tsaToken,
    Instant tsaGenTime) {

  public CellAnchor {
    Objects.requireNonNull(cellSealId, "cellSealId");
    Objects.requireNonNull(cellCode, "cellCode");
    Objects.requireNonNull(windowEnd, "windowEnd");
    Objects.requireNonNull(signature, "signature");
    Objects.requireNonNull(tsaToken, "tsaToken");
    if (cellRoot == null || cellRoot.length != 32) {
      throw new IllegalArgumentException("Gốc cell phải đúng 32 byte");
    }
    if (prevCellRoot != null && prevCellRoot.length != 32) {
      throw new IllegalArgumentException("Gốc cell ngày trước phải đúng 32 byte");
    }
  }
}
