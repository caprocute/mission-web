package vn.viettel.okrkpi.replica.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Cổng ghi bản sao (hiện thực JDBC trên {@code orgReplicaDataSource}). Mọi phương thức chạy trong
 * giao dịch inbox đã đặt {@code app.tenant_id}.
 */
public interface OrgReplicaStore {

  /** Upsert theo {@code source_version} lớn hơn; trả {@code false} khi bản ghi cũ hơn bị bỏ qua. */
  boolean upsertUnit(UnitSnapshot unit, AppliedEvent event);

  boolean upsertAssignment(AssignmentSnapshot assignment, AppliedEvent event);

  boolean upsertPerson(PersonSnapshot person, AppliedEvent event);

  /** Cấp trên hiện hành (độ sâu 1) trong bao đóng của cây. */
  Optional<UUID> closureParent(String tree, UUID unitId);

  /** Đảm bảo có dòng tự thân (độ sâu 0) của đơn vị. */
  void ensureClosureSelf(String tree, UUID unitId);

  /** {@code candidate} là hậu duệ hoặc chính {@code ancestor}. */
  boolean closureContains(String tree, UUID ancestor, UUID candidate);

  /** Gỡ cây con của {@code unitId} khỏi mọi tổ tiên bên ngoài cây con. */
  void detachSubtree(String tree, UUID unitId);

  /** Nối cây con của {@code unitId} dưới {@code parentId}. */
  void attachSubtree(String tree, UUID unitId, UUID parentId);

  /** Ghi điểm kiểm và trả độ trễ (ms) từ lúc phát tới lúc áp dụng. */
  long checkpoint(EntityType type, AppliedEvent event);
}
