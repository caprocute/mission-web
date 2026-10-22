package vn.viettel.okrkpi.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Khoá được ghi bằng kho và trong giao dịch của schema khi tiến trình khai {@link
 * IdempotencyTargets}.
 */
class IdempotencyTargetsTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final ActorId ACTOR = ActorId.of(UUID.randomUUID());

  @AfterEach
  void clear() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void schemaCoDichRiengDungKhoVaGiaoDichCuaSchema() {
    RecordingStore fallback = new RecordingStore();
    RecordingTx fallbackTx = new RecordingTx();
    RecordingStore taskStore = new RecordingStore();
    RecordingTx taskTx = new RecordingTx();
    IdempotencyTargets targets =
        schema ->
            "task".equals(schema)
                ? Optional.of(new IdempotencyTargets.Target(taskStore, taskTx))
                : Optional.empty();
    Controller c = proxy(fallback, fallbackTx, () -> targets);

    request();
    assertThat(c.task("a").getStatusCode().value()).isEqualTo(201);
    assertThat(taskStore.calls).containsExactly("begin task", "complete 201");
    assertThat(taskTx.commits).isEqualTo(1);
    assertThat(fallback.calls).isEmpty();
    assertThat(fallbackTx.commits).isZero();

    request();
    c.okr("b");
    assertThat(fallback.calls).containsExactly("begin okr", "complete 200");
    assertThat(fallbackTx.commits).isEqualTo(1);
  }

  @Test
  void khongKhaiDichThiDungMacDinh() {
    RecordingStore fallback = new RecordingStore();
    RecordingTx fallbackTx = new RecordingTx();
    Controller c = proxy(fallback, fallbackTx, () -> null);
    request();
    c.task("a");
    assertThat(fallback.calls).containsExactly("begin task", "complete 201");
    assertThat(fallbackTx.commits).isEqualTo(1);
  }

  private static Controller proxy(
      IdempotencyStore store,
      PlatformTransactionManager tx,
      java.util.function.Supplier<IdempotencyTargets> targets) {
    IdempotencyInterceptor interceptor =
        new IdempotencyInterceptor(
            () -> store,
            () -> new TransactionTemplate(tx),
            ObjectMapper::new,
            () -> r -> Optional.of(new IdempotencyCallerResolver.Caller(TENANT, ACTOR)),
            null,
            targets);
    ProxyFactory pf = new ProxyFactory(new Controller());
    pf.setProxyTargetClass(true);
    pf.addAdvice(interceptor);
    return (Controller) pf.getProxy();
  }

  private static void request() {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/task/tasks");
    req.addHeader(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString());
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
  }

  static class Controller {
    @Idempotent(schema = "task")
    public ResponseEntity<String> task(@RequestBody String body) {
      return ResponseEntity.status(201).body("\"ok\"");
    }

    @Idempotent(schema = "okr")
    public String okr(@RequestBody String body) {
      return "ok";
    }
  }

  static final class RecordingStore implements IdempotencyStore {
    final List<String> calls = new ArrayList<>();

    @Override
    public Optional<IdempotencyRecord> find(IdempotencyScope scope, String key) {
      return Optional.empty();
    }

    @Override
    public BeginResult begin(IdempotencyScope scope, String key, byte[] requestHash) {
      calls.add("begin " + scope.schema());
      return new BeginResult.Started(scope, key, LocalDate.now());
    }

    @Override
    public void complete(
        BeginResult.Started started, int responseCode, String responseBody, String resourceRef) {
      calls.add("complete " + responseCode);
    }

    @Override
    public void abandon(BeginResult.Started started) {
      calls.add("abandon");
    }
  }

  static final class RecordingTx implements PlatformTransactionManager {
    int commits;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commits++;
    }

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
