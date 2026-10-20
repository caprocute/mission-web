package vn.viettel.okrkpi.org.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Cây đơn vị theo hai trục; {@code readOnly} khi xem quá khứ (MSG-01-08). */
public record TreeView(LocalDate asOf, OffsetDateTime recordedAsOf, boolean readOnly, List<Node> roots) {

  public record Node(UUID id, String code, String name, String unitType, boolean hasChildren, List<Node> children) {
  }
}
