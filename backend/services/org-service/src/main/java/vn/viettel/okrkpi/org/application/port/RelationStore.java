package vn.viettel.okrkpi.org.application.port;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.TemporalQuery;
import vn.viettel.okrkpi.org.domain.TreeKind;
import vn.viettel.okrkpi.org.domain.UnitRelationVersion;
import vn.viettel.okrkpi.org.domain.VersionPlan;

/** Truy cập {@code org.org_unit_relation}. */
public interface RelationStore {

  Optional<UnitRelationVersion> parentAt(UUID child, TreeKind tree, TemporalQuery query);

  /** Các cây mà đơn vị có cấp trên trực thuộc tại thời điểm hỏi. */
  Set<TreeKind> parentTreesAt(UUID child, TemporalQuery query);

  /** Mọi phiên bản quan hệ trực thuộc của đơn vị con (lịch sử). */
  List<UnitRelationVersion> parentHistory(UUID child);

  /** Quan hệ hiện hành (trục ghi nhận) mà đơn vị là cha hoặc con, khoá dòng để ghi. */
  List<UnitRelationVersion> currentTouchingForUpdate(Collection<UUID> unitIds);

  /** {@code newParent} thuộc cây con của {@code child} trên cây {@code tree} tại thời điểm hiệu lực giao nhau. */
  boolean wouldCreateCycle(TreeKind tree, UUID child, UUID newParent, LocalDate from, LocalDate to);

  /** Đơn vị có đơn vị trực thuộc còn hiệu lực từ ngày {@code from}. */
  boolean hasChildrenFrom(UUID parent, LocalDate from);

  void apply(VersionPlan<UnitRelationVersion> plan, UUID changeOrderId, Actor actor);
}
