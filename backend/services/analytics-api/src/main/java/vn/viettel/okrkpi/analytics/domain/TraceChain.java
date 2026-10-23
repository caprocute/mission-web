package vn.viettel.okrkpi.analytics.domain;

import java.util.List;
import java.util.UUID;

/** Chuỗi truy vết quanh một đối tượng ({@code TraceChain}). */
public record TraceChain(
    TraceObjectType focusType, UUID focusId, List<TraceLink> links, String ledgerRef) {

  public TraceChain {
    links = List.copyOf(links);
  }
}
