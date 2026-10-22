package vn.viettel.okrkpi.dossier.domain.meeting;

import java.util.Objects;
import java.util.UUID;

/**
 * Một người trong thành phần hội nghị ({@code participants_json}): vai trò và trạng thái trên thẻ
 * thành phần của SCR-1101. Mã vai trò và trạng thái là suy luận của hợp đồng {@code dossiers.yaml}
 * vì TL-03 không quy định giá trị.
 */
public record Participant(UUID personId, String role, String status) {

  public static final String CHU_TRI = "CHU_TRI";
  public static final String THU_KY = "THU_KY";
  public static final String THANH_VIEN = "THANH_VIEN";
  public static final String NGUOI_DUOC_KIEM_DIEM = "NGUOI_DUOC_KIEM_DIEM";

  /** Trạng thái đầu của thành viên khi được mời (chưa mở dự thảo). */
  public static final String CHUA_MO = "CHUA_MO";

  public Participant {
    Objects.requireNonNull(personId, "personId");
    Objects.requireNonNull(role, "role");
  }
}
