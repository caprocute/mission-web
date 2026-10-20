package vn.viettel.okrkpi.org.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgChangeOrder;

/**
 * {@code OrgChangeOrder} của hợp đồng cộng hai trường mở rộng không phá tương thích (lược đồ không cấm thuộc tính
 * thêm): {@code movedAssignmentCount} cho MSG-01-11 và {@code warnings} cho MSG-01-10. Hợp đồng chưa có hai trường
 * này; đề nghị bổ sung bằng task sửa hợp đồng riêng (ghi trong báo cáo, wiki trang 14).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrgChangeOrderResponse extends OrgChangeOrder {

  private Integer movedAssignmentCount;
  private List<UUID> createdUnitIds = new ArrayList<>();
  private List<Warning> warnings = new ArrayList<>();

  public Integer getMovedAssignmentCount() {
    return movedAssignmentCount;
  }

  public void setMovedAssignmentCount(Integer movedAssignmentCount) {
    this.movedAssignmentCount = movedAssignmentCount;
  }

  public List<UUID> getCreatedUnitIds() {
    return createdUnitIds;
  }

  public void setCreatedUnitIds(List<UUID> createdUnitIds) {
    this.createdUnitIds = createdUnitIds;
  }

  public List<Warning> getWarnings() {
    return warnings;
  }

  public void setWarnings(List<Warning> warnings) {
    this.warnings = warnings;
  }

  /** Cảnh báo không chặn: {@code msg} là mã thông điệp TL-02, {@code period} điền vào {kỳ}. */
  public record Warning(String msg, String period, String detail) {
  }
}
