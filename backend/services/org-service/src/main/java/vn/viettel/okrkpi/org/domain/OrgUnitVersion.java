package vn.viettel.okrkpi.org.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Một phiên bản dòng của {@code org.org_unit}. {@code recordedTo == null} là hiểu biết hiện tại. */
public record OrgUnitVersion(
    UUID versionId,
    UUID id,
    OrgUnitContent content,
    UUID changeOrderId,
    ValidPeriod validPeriod,
    OffsetDateTime recordedFrom,
    OffsetDateTime recordedTo,
    UUID createdBy,
    int rowVersion) implements Versioned<OrgUnitVersion> {

  @Override
  public OrgUnitVersion withValidPeriod(ValidPeriod period) {
    return new OrgUnitVersion(versionId, id, content, changeOrderId, period, recordedFrom, recordedTo, createdBy,
        rowVersion);
  }

  public OrgUnitVersion withContent(OrgUnitContent newContent) {
    return new OrgUnitVersion(versionId, id, newContent, changeOrderId, validPeriod, recordedFrom, recordedTo,
        createdBy, rowVersion);
  }

  public OrgUnitVersion withChangeOrder(UUID orderId) {
    return new OrgUnitVersion(versionId, id, content, orderId, validPeriod, recordedFrom, recordedTo, createdBy,
        rowVersion);
  }

  /** Phiên bản mới (chưa có version_id) của một đơn vị. */
  public static OrgUnitVersion draft(UUID id, OrgUnitContent content, ValidPeriod period) {
    return new OrgUnitVersion(null, id, content, null, period, null, null, null, 0);
  }
}
