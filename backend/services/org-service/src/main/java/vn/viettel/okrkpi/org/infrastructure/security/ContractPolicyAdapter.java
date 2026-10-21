package vn.viettel.okrkpi.org.infrastructure.security;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import vn.viettel.okrkpi.org.application.Actor;
import vn.viettel.okrkpi.org.application.ForbiddenOperationException;
import vn.viettel.okrkpi.org.application.StepUpRequiredException;
import vn.viettel.okrkpi.org.application.port.PolicyPort;

/**
 * Hiện thực tạm của {@link PolicyPort} khi chưa có OPA (NEN-014): vai trò được phép và mức AAL của từng thao tác đọc
 * từ {@code x-mission} trong hợp đồng {@code org.yaml} đóng gói kèm service (khớp ma trận {@code wiki/15a}), không tự
 * đặt danh sách vai trò trong mã. Phạm vi dữ liệu theo cây đơn vị (ABAC: Đv, ĐvD) chưa kiểm ở đây; tenant được cô lập
 * bằng RLS. Thao tác không có trong hợp đồng bị từ chối.
 */
@Component
public class ContractPolicyAdapter implements PolicyPort {

  private final Map<String, Rule> rules;
  private final Set<String> tenantScopeRoles;
  private final Duration stepUpWindow;
  private final Clock clock;

  public ContractPolicyAdapter(Clock clock,
      @Value("${okrkpi.org.step-up-window:PT5M}") Duration stepUpWindow) {
    this.clock = clock;
    this.stepUpWindow = stepUpWindow;
    this.rules = load();
    this.tenantScopeRoles = rules.values().stream()
        .filter(r -> "Tn".equals(r.scope()) && r.roles().size() == 1)
        .flatMap(r -> r.roles().stream())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  @Override
  public void authorize(Actor actor, String operationId) {
    Rule rule = rules.get(operationId);
    if (rule == null || actor.roles().stream().noneMatch(rule.roles()::contains)) {
      throw new ForbiddenOperationException("Không có quyền thực hiện thao tác này.");
    }
    if (rule.aal() >= 2) {
      boolean aal2 = "aal2".equals(actor.acr());
      boolean fresh = actor.authTime() != null
          && !actor.authTime().isBefore(Instant.now(clock).minus(stepUpWindow));
      if (!aal2 || !fresh) {
        throw new StepUpRequiredException(stepUpWindow.toSeconds());
      }
    }
  }

  @Override
  public boolean hasTenantScope(Actor actor) {
    return actor != null && actor.roles() != null && actor.roles().stream().anyMatch(tenantScopeRoles::contains);
  }

  Map<String, Rule> rules() {
    return rules;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Rule> load() {
    Map<String, Rule> out = new HashMap<>();
    try (InputStream in = new ClassPathResource("contract/modules/org.yaml").getInputStream()) {
      Map<String, Object> doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
      Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
      for (Object item : paths.values()) {
        for (Object op : ((Map<String, Object>) item).values()) {
          if (!(op instanceof Map<?, ?> operation) || operation.get("operationId") == null) {
            continue;
          }
          Map<String, Object> mission = (Map<String, Object>) operation.get("x-mission");
          if (mission == null) {
            continue;
          }
          List<String> roles = (List<String>) mission.getOrDefault("roles", List.of());
          Number aal = (Number) mission.getOrDefault("aal", 1);
          String scope = (String) mission.get("scope");
          out.put(operation.get("operationId").toString(), new Rule(Set.copyOf(roles), aal.intValue(), scope));
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("Không đọc được hợp đồng org.yaml", e);
    }
    return Map.copyOf(out);
  }

  record Rule(Set<String> roles, int aal, String scope) {
  }
}
