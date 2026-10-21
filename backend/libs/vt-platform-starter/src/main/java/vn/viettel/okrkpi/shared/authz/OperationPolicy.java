package vn.viettel.okrkpi.shared.authz;

import java.util.List;
import java.util.Objects;

/**
 * Chính sách uỷ quyền gắn với một {@code operationId} trong hợp đồng OpenAPI (mở rộng {@code
 * x-mission}, NEN-014, ADR-21).
 */
public record OperationPolicy(
    String operationId,
    String file,
    String module,
    String errorModule,
    String method,
    String path,
    String owner,
    List<String> qt,
    List<String> scr,
    List<String> roles,
    String scope,
    int aal,
    boolean fourEyes,
    String source) {

  public OperationPolicy {
    Objects.requireNonNull(operationId, "operationId must not be null");
    qt = qt == null ? List.of() : List.copyOf(qt);
    scr = scr == null ? List.of() : List.copyOf(scr);
    roles = roles == null ? List.of() : List.copyOf(roles);
    if (aal < 1) {
      aal = 1;
    }
  }

  /** Thao tác có yêu cầu xác thực bước lên AAL2 ({@code x-mission.aal: 2}) hay không. */
  public boolean requiresStepUp() {
    return aal >= 2;
  }

  /** Thao tác có yêu cầu nguyên tắc bốn mắt ({@code x-mission.fourEyes: true}) hay không. */
  public boolean requiresFourEyes() {
    return fourEyes;
  }
}
