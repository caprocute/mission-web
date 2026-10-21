package vn.viettel.okrkpi.replica.application;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.config.api.PeriodApi;
import vn.viettel.okrkpi.config.api.PeriodSnapshot;
import vn.viettel.okrkpi.replica.api.AssignmentBackdatedEvent;
import vn.viettel.okrkpi.replica.domain.AppliedEvent;
import vn.viettel.okrkpi.replica.domain.AssignmentSnapshot;
import vn.viettel.okrkpi.replica.domain.EntityType;
import vn.viettel.okrkpi.replica.domain.OrgReplicaStore;
import vn.viettel.okrkpi.replica.domain.OrgTreeCycleException;
import vn.viettel.okrkpi.replica.domain.PersonSnapshot;
import vn.viettel.okrkpi.replica.domain.UnitSnapshot;

/**
 * Áp dụng EVT-14, EVT-15, EVT-16 và {@code org.bootstrap.v1} vào {@code org_replica} (CORE-00-03).
 * Chạy trong giao dịch của consumer inbox (cùng {@code orgReplicaTransactionManager}), nên upsert,
 * bao đóng và điểm kiểm commit hoặc huỷ cùng nhau.
 */
@Service
public class OrgReplicaService {

  static final String TREE_DANG = "DANG";
  static final String TREE_CHUYEN_MON = "CHUYEN_MON";

  private final OrgReplicaStore store;
  private final PeriodApi periods;
  private final ApplicationEventPublisher events;

  public OrgReplicaService(
      OrgReplicaStore store, PeriodApi periods, ApplicationEventPublisher events) {
    this.store = Objects.requireNonNull(store, "store");
    this.periods = Objects.requireNonNull(periods, "periods");
    this.events = Objects.requireNonNull(events, "events");
  }

  /** EVT-14: upsert đơn vị; nếu là phiên bản mở và cấp trên đổi thì tính lại bao đóng từng cây. */
  public long applyUnit(UnitSnapshot unit, AppliedEvent event) {
    if (store.upsertUnit(unit, event) && unit.openEnded()) {
      relink(TREE_DANG, unit.id(), unit.partyParentId());
      relink(TREE_CHUYEN_MON, unit.id(), unit.adminParentId());
    }
    return store.checkpoint(EntityType.ORG_UNIT, event);
  }

  /** EVT-15: upsert phân công; đính chính lùi vào kỳ trước kỳ hiện hành thì báo MOD-04. */
  public long applyAssignment(AssignmentSnapshot assignment, AppliedEvent event) {
    if (store.upsertAssignment(assignment, event)) {
      periods
          .findCurrent(event.tenant())
          .filter(p -> inLockedPeriod(p, assignment.validFrom()))
          .ifPresent(
              p ->
                  events.publishEvent(
                      new AssignmentBackdatedEvent(
                          event.tenant(),
                          assignment.id(),
                          assignment.personId(),
                          assignment.orgUnitId(),
                          assignment.validFrom(),
                          covers(p, assignment.validFrom()) ? p.id().value() : null,
                          event.eventId())));
    }
    return store.checkpoint(EntityType.ASSIGNMENT, event);
  }

  /** EVT-16: upsert nhân sự. */
  public long applyPerson(PersonSnapshot person, AppliedEvent event) {
    store.upsertPerson(person, event);
    return store.checkpoint(EntityType.PERSON, event);
  }

  private void relink(String tree, UUID unitId, UUID newParent) {
    store.ensureClosureSelf(tree, unitId);
    UUID current = store.closureParent(tree, unitId).orElse(null);
    if (Objects.equals(current, newParent)) {
      return;
    }
    if (newParent != null && store.closureContains(tree, unitId, newParent)) {
      throw new OrgTreeCycleException(tree, unitId, newParent);
    }
    store.detachSubtree(tree, unitId);
    if (newParent != null) {
      store.ensureClosureSelf(tree, newParent);
      store.attachSubtree(tree, unitId, newParent);
    }
  }

  /**
   * Ngày rơi vào kỳ đã khoá: trước đầu kỳ hiện hành, hoặc trong kỳ hiện hành đang chốt hay đã khoá
   * (suy luận: {@code PeriodApi} chưa tra kỳ theo ngày; khi CORE-08 bổ sung thì tra kỳ thật).
   */
  private static boolean inLockedPeriod(PeriodSnapshot p, LocalDate day) {
    return day.isBefore(p.startDate()) || (covers(p, day) && p.isLocked());
  }

  private static boolean covers(PeriodSnapshot p, LocalDate day) {
    return !day.isBefore(p.startDate()) && !day.isAfter(p.endDate());
  }
}
