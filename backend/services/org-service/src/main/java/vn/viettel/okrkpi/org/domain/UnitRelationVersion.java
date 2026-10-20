package vn.viettel.okrkpi.org.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Một phiên bản của {@code org.org_unit_relation}. */
public record UnitRelationVersion(
    UUID versionId,
    UUID id,
    TreeKind tree,
    UUID parentUnitId,
    UUID childUnitId,
    String relationType,
    UUID changeOrderId,
    ValidPeriod validPeriod,
    OffsetDateTime recordedFrom,
    OffsetDateTime recordedTo,
    int rowVersion) implements Versioned<UnitRelationVersion> {

  public static final String TRUC_THUOC = "TRUC_THUOC";

  @Override
  public UnitRelationVersion withValidPeriod(ValidPeriod period) {
    return new UnitRelationVersion(versionId, id, tree, parentUnitId, childUnitId, relationType, changeOrderId,
        period, recordedFrom, recordedTo, rowVersion);
  }

  public static UnitRelationVersion draftParent(UUID id, TreeKind tree, UUID parent, UUID child, ValidPeriod period) {
    return new UnitRelationVersion(null, id, tree, parent, child, TRUC_THUOC, null, period, null, null, 0);
  }
}
