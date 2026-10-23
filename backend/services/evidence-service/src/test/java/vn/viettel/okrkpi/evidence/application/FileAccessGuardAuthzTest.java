package vn.viettel.okrkpi.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.viettel.okrkpi.evidence.domain.StoredFile;
import vn.viettel.okrkpi.shared.audit.AuditLogger;
import vn.viettel.okrkpi.shared.authz.AuthzAutoConfiguration;
import vn.viettel.okrkpi.shared.authz.AuthzSubjectAutoConfiguration;
import vn.viettel.okrkpi.shared.authz.OpaClient;
import vn.viettel.okrkpi.shared.authz.OpaDecision;
import vn.viettel.okrkpi.shared.authz.OpaInput;
import vn.viettel.okrkpi.shared.error.BusinessException;
import vn.viettel.okrkpi.shared.error.ErrorCatalog;

/**
 * {@code @RequiresPermission} của {@link FileAccessGuard} được OPA chặn trong ngữ cảnh Spring của
 * evidence-service, vốn không có AspectJ (chỉ {@code InfrastructureAdvisorAutoProxyCreator}):
 * advisor của thư viện nền mang vai trò hạ tầng nên vẫn được áp. OPA từ chối thì lời gọi bị chặn
 * với lỗi hợp đồng, OPA cho phép thì đi qua.
 */
class FileAccessGuardAuthzTest {

  private static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-0000000e0001");
  private static final UUID USER = UUID.fromString("0192f1a2-0000-7000-8000-0000000e0002");

  private final AtomicReference<OpaDecision> decision = new AtomicReference<>();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  AopAutoConfiguration.class,
                  AuthzSubjectAutoConfiguration.class,
                  AuthzAutoConfiguration.class))
          .withBean(ErrorCatalog.class, ErrorCatalog::standard)
          .withBean(AuditLogger.class, () -> (AuditLogger) e -> {})
          .withBean(OpaClient.class, () -> new StubOpa(decision))
          .withBean(FileAccessGuard.class);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void opaTuChoiThiKhongMoDuocTep() {
    signIn();
    runner.run(
        ctx -> {
          assertThat(ctx).hasNotFailed();
          FileAccessGuard guard = ctx.getBean(FileAccessGuard.class);
          assertThat(AopUtils.isAopProxy(guard)).isTrue();
          FileAccessGuard.FileAccess access = new FileAccessGuard.FileAccess(file());

          decision.set(new OpaDecision(false, Set.of("out_of_scope"), Set.of(), "rev-1", false));
          assertThatThrownBy(() -> guard.checkDownload(access))
              .isInstanceOfSatisfying(
                  BusinessException.class,
                  e -> assertThat(e.errorCode().code()).isEqualTo("COMMON-403-OUT-OF-SCOPE"));
          assertThatThrownBy(() -> guard.checkPreview(access))
              .isInstanceOf(BusinessException.class);
          assertThatThrownBy(() -> guard.checkScanResult(access))
              .isInstanceOf(BusinessException.class);

          decision.set(new OpaDecision(true, Set.of(), Set.of(), "rev-1", false));
          guard.checkDownload(access);
        });
  }

  private static void signIn() {
    Jwt jwt =
        Jwt.withTokenValue("tok")
            .header("alg", "RS256")
            .subject(USER.toString())
            .claim("tenant_id", TENANT.toString())
            .claim("acr", "aal2")
            .claim("auth_time", Instant.now().getEpochSecond())
            .claim("realm_access", Map.of("roles", List.of("member")))
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
  }

  private static StoredFile file() {
    return new StoredFile(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        TENANT,
        UUID.randomUUID(),
        "product",
        UUID.randomUUID(),
        "application/pdf",
        "C1",
        10,
        new byte[32],
        "bucket",
        "key",
        null,
        null,
        "SACH",
        Instant.now());
  }

  /** OPA giả trả quyết định đặt sẵn. */
  record StubOpa(AtomicReference<OpaDecision> decision) implements OpaClient {
    @Override
    public OpaDecision decide(OpaInput input) {
      return decision.get();
    }

    @Override
    public JsonNode compile(String query, Map<String, Object> input, List<String> unknowns) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String currentBundleRevision() {
      return "rev-1";
    }

    @Override
    public void invalidateCache(String newBundleRevision) {}
  }
}
