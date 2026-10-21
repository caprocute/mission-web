package vn.viettel.okrkpi.org.application.republish;

import java.util.Locale;

/**
 * Loại thực thể của lệnh {@code org-republish}, theo thứ tự phát: đơn vị trước để bên nhận dựng bảng bao đóng, rồi
 * nhân sự, cuối cùng phân công (suy luận, wiki trang 18).
 */
public enum EntityKind {
  /** EVT-14 DonViDaThayDoi. */
  ORG_UNIT("unit"),
  /** EVT-16 NhanSuDaThayDoi. */
  PERSON("person"),
  /** EVT-15 PhanCongDaThayDoi. */
  ASSIGNMENT("assignment");

  private final String cliName;

  EntityKind(String cliName) {
    this.cliName = cliName;
  }

  /** Tên ở tham số {@code --entity}. */
  public String cliName() {
    return cliName;
  }

  public static EntityKind fromCli(String value) {
    String v = value.trim().toLowerCase(Locale.ROOT);
    for (EntityKind k : values()) {
      if (k.cliName.equals(v)) {
        return k;
      }
    }
    throw new IllegalArgumentException("--entity chỉ nhận unit, assignment hoặc person");
  }
}
