package vn.viettel.okrkpi.workflow.application.replica;

import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vn.viettel.okrkpi.workflow.application.port.OrgUnitReplicaStore;
import vn.viettel.okrkpi.workflow.application.port.PersonReplicaStore;
import vn.viettel.okrkpi.workflow.domain.replica.OrgTree;
import vn.viettel.okrkpi.workflow.domain.replica.OrgTreeCycleException;
import vn.viettel.okrkpi.workflow.domain.replica.OrgUnitReplica;
import vn.viettel.okrkpi.workflow.domain.replica.PersonReplica;

/**
 * Áp EVT-14 {@code org.unit.changed.v1}, EVT-16 {@code org.person.changed.v1} và phần đơn vị, nhân
 * sự của {@code org.bootstrap.v1} vào bản sao {@code org_replica.org_unit_v}, {@code
 * org_replica.person_v} của workflow-service (DV-06-10), theo mẫu {@code OrgReplicaService} của
 * mission-core: sự kiện mang trạng thái đầy đủ (ECST) nên chỉ cần upsert có điều kiện theo {@code
 * source_version}; chống trùng, thứ tự phiên bản, thử lại và thư chết do inbox của thư viện nền lo.
 *
 * <p>Không mở giao dịch riêng: chạy trong giao dịch của consumer inbox, nên dấu vết {@code
 * inbox_processed} và bản sao commit hoặc huỷ cùng nhau. Đổi cấp trên của phiên bản mở mà tạo vòng
 * thì ném {@link OrgTreeCycleException}; giao dịch huỷ cả dòng vừa ghi và consumer đưa sự kiện vào
 * thư chết, vì vòng trên cây làm phép đi lên tìm cấp uỷ của {@code ApprovalLevelResolver} sai.
 */
@Service
public class OrgReplicaService {

  private static final Logger log = LoggerFactory.getLogger(OrgReplicaService.class);

  private final OrgUnitReplicaStore units;
  private final PersonReplicaStore persons;

  public OrgReplicaService(OrgUnitReplicaStore units, PersonReplicaStore persons) {
    this.units = Objects.requireNonNull(units, "units");
    this.persons = Objects.requireNonNull(persons, "persons");
  }

  /**
   * Áp một lát hiệu lực đơn vị.
   *
   * @return {@code true} khi bản sao đổi, {@code false} khi sự kiện cũ hơn bản đang có
   * @throws OrgTreeCycleException khi cấp trên mới làm cây hiện hành có vòng
   */
  public boolean applyUnit(UUID tenantId, OrgUnitReplica unit, UUID eventId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(eventId, "eventId");
    for (OrgTree tree : OrgTree.values()) {
      if (unit.id().equals(unit.parentOn(tree))) {
        throw new OrgTreeCycleException(tree, unit.id(), unit.id());
      }
    }
    boolean applied = units.upsert(tenantId, unit, eventId);
    if (!applied) {
      log.debug(
          "Bỏ qua đơn vị {} phiên bản nguồn {}: bản sao đã mới hơn",
          unit.id(),
          unit.sourceVersion());
      return false;
    }
    if (unit.openEnded()) {
      for (OrgTree tree : OrgTree.values()) {
        UUID parent = unit.parentOn(tree);
        if (parent != null && units.reachesUpward(tenantId, tree, parent, unit.id())) {
          throw new OrgTreeCycleException(tree, unit.id(), parent);
        }
      }
    }
    return true;
  }

  /**
   * Áp trạng thái một nhân sự.
   *
   * @return {@code true} khi bản sao đổi, {@code false} khi sự kiện cũ hơn bản đang có
   */
  public boolean applyPerson(UUID tenantId, PersonReplica person, UUID eventId) {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(person, "person");
    Objects.requireNonNull(eventId, "eventId");
    boolean applied = persons.upsert(tenantId, person, eventId);
    if (!applied) {
      log.debug(
          "Bỏ qua nhân sự {} phiên bản nguồn {}: bản sao đã mới hơn",
          person.id(),
          person.sourceVersion());
    }
    return applied;
  }
}
