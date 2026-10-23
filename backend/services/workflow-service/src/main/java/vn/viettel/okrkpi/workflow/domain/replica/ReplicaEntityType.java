package vn.viettel.okrkpi.workflow.domain.replica;

/**
 * Loại thực thể của bản sao tổ chức, giá trị cột {@code entity_type} của {@code
 * org_replica.replica_checkpoint} và nhãn {@code entity_type} của số đo {@code org_replica_lag_ms}
 * (TL-03 "Bảng org_replica.replica_checkpoint").
 */
public enum ReplicaEntityType {
  /** Đơn vị, EVT-14 {@code org.unit.changed.v1}. */
  ORG_UNIT,
  /** Phân công, EVT-15 {@code org.assignment.changed.v1}. */
  ASSIGNMENT,
  /** Nhân sự, EVT-16 {@code org.person.changed.v1}. */
  PERSON
}
