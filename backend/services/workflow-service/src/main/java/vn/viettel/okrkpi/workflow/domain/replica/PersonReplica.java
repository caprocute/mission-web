package vn.viettel.okrkpi.workflow.domain.replica;

import java.util.Objects;
import java.util.UUID;

/**
 * Trạng thái đầy đủ của một nhân sự mang trong EVT-16 {@code org.person.changed.v1} hoặc {@code
 * org.bootstrap.v1} (ECST), áp vào bản sao {@code org_replica.person_v} của workflow-service (TL-03
 * "Bảng org_replica.person_v"). Bản sao không chứa cột C2; khoá là {@code (tenant_id, id)}.
 *
 * @param id mã người (trùng {@code sub} trong Keycloak)
 * @param fullName họ và tên
 * @param partyMember là đảng viên
 * @param employmentStatus tình trạng công tác ({@code DANG_CONG_TAC}, {@code NGHI_HUU}...)
 * @param sourceVersion phiên bản ở nguồn, đơn điệu tăng
 */
public record PersonReplica(
    UUID id, String fullName, boolean partyMember, String employmentStatus, long sourceVersion) {

  /** Độ dài tối đa của cột {@code full_name varchar(200)}. */
  public static final int MAX_FULL_NAME = 200;

  /** Độ dài tối đa của cột {@code employment_status varchar(16)}. */
  public static final int MAX_STATUS = 16;

  public PersonReplica {
    Objects.requireNonNull(id, "id");
    if (fullName == null || fullName.isBlank() || fullName.length() > MAX_FULL_NAME) {
      throw new IllegalArgumentException("Họ và tên rỗng hoặc quá 200 ký tự");
    }
    if (employmentStatus == null
        || employmentStatus.isBlank()
        || employmentStatus.length() > MAX_STATUS) {
      throw new IllegalArgumentException("Tình trạng công tác rỗng hoặc quá 16 ký tự");
    }
    if (sourceVersion < 0) {
      throw new IllegalArgumentException("Phiên bản nguồn không được âm");
    }
  }
}
