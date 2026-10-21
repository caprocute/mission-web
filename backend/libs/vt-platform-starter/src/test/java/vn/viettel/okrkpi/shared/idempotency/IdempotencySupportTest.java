package vn.viettel.okrkpi.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Classified;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class IdempotencySupportTest {

  private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  private static final ActorId ACTOR = ActorId.of(UUID.randomUUID());

  private final RequestHasher hasher = new RequestHasher(new ObjectMapper());

  record Input(int percent, String note) {}

  record Nested(List<Secret> items) {}

  record Secret(@Classified(Classification.C2) String content) {}

  record Plain(String title, Map<String, Integer> counts) {}

  static class Holder {
    @Classified(Classification.C3)
    private String cipher;

    private static String constant;
  }

  static class Child extends Holder {}

  @Classified(Classification.C2)
  record WholeType(String a) {}

  record Recursive(Recursive next, Plain plain) {}

  @Test
  void bamKhongPhuThuocThuTuTruongVaKhoangTrang() {
    byte[] a =
        hasher.hash("post", "/api/v1/x", "{\"a\":1,\"b\":{\"d\":[1,{\"z\":1,\"y\":2}],\"c\":2}}");
    byte[] b =
        hasher.hash(
            "POST", "/api/v1/x", "{ \"b\" : {\"c\":2, \"d\":[1,{\"y\":2,\"z\":1}]}, \"a\" : 1 }");
    assertThat(a).isEqualTo(b).hasSize(32);
    assertThat(hasher.hash("POST", "/api/v1/x", new Input(1, "n")))
        .isEqualTo(hasher.hash("POST", "/api/v1/x", "{\"note\":\"n\",\"percent\":1}"));
    assertThat(hasher.hash("POST", "/api/v1/y", "{\"a\":1}"))
        .isNotEqualTo(hasher.hash("POST", "/api/v1/x", "{\"a\":1}"));
    assertThat(hasher.hash("PUT", "/api/v1/x", "{\"a\":1}"))
        .isNotEqualTo(hasher.hash("POST", "/api/v1/x", "{\"a\":1}"));
    assertThat(hasher.hash("POST", "/api/v1/x", "{\"a\":2}"))
        .isNotEqualTo(hasher.hash("POST", "/api/v1/x", "{\"a\":1}"));
    assertThat(hasher.hash("POST", "/x?b=1", (Object) null))
        .isNotEqualTo(hasher.hash("POST", "/x?b=2", (Object) null));
  }

  @Test
  void thanKhongPhaiJsonBamNguyenByte() {
    assertThat(hasher.canonicalBody("không phải json"))
        .isEqualTo("không phải json".getBytes(StandardCharsets.UTF_8));
    byte[] raw = {(byte) 0xff, 0x00};
    assertThat(hasher.canonicalBody(raw)).isEqualTo(raw);
    assertThat(hasher.canonicalBody("{\"b\":1,\"a\":null}".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("{\"a\":null,\"b\":1}".getBytes(StandardCharsets.UTF_8));
    assertThat(hasher.canonicalBody(null)).isEmpty();
  }

  @Test
  void phatHienThanCoTruongNhayCam() {
    assertThat(SensitivePayloads.containsSensitive(Secret.class)).isTrue();
    assertThat(SensitivePayloads.containsSensitive(Nested.class)).isTrue();
    assertThat(SensitivePayloads.containsSensitive(Child.class)).isTrue();
    assertThat(SensitivePayloads.containsSensitive(WholeType.class)).isTrue();
    assertThat(SensitivePayloads.containsSensitive(Secret[].class)).isTrue();
    assertThat(SensitivePayloads.containsSensitive(Plain.class)).isFalse();
    assertThat(SensitivePayloads.containsSensitive(Recursive.class)).isFalse();
    assertThat(SensitivePayloads.containsSensitive(Input.class)).isFalse();
    assertThat(SensitivePayloads.containsSensitive(String.class)).isFalse();
    assertThat(SensitivePayloads.containsSensitive(null)).isFalse();
    // Lần hai lấy từ bộ đệm.
    assertThat(SensitivePayloads.containsSensitive(Nested.class)).isTrue();
  }

  @Test
  void schemaTheoGoiModule() {
    assertThat(IdempotencyInterceptor.schemaFromPackage("vn.viettel.okrkpi.task.web"))
        .isEqualTo("task");
    assertThat(IdempotencyInterceptor.schemaFromPackage("vn.viettel.okrkpi.appraisal"))
        .isEqualTo("appraisal");
    assertThat(IdempotencyInterceptor.schemaFromPackage("vn.viettel.okrkpi.shared.x")).isNull();
    assertThat(IdempotencyInterceptor.schemaFromPackage("com.other")).isNull();
    assertThat(IdempotencyInterceptor.schemaFromPackage(null)).isNull();
    assertThat(IdempotencyInterceptor.schemaFromPackage("vn.viettel.okrkpi.9bad")).isNull();
  }

  @Test
  void khoaVaPhamViHopLe() {
    assertThat(JdbcIdempotencyStore.isValidKey("01928f3e-7b1a-7c3e-9a51-3f2d9c1b0a77")).isTrue();
    assertThat(JdbcIdempotencyStore.isValidKey("k".repeat(64))).isTrue();
    assertThat(JdbcIdempotencyStore.isValidKey("k".repeat(65))).isFalse();
    assertThat(JdbcIdempotencyStore.isValidKey("")).isFalse();
    assertThat(JdbcIdempotencyStore.isValidKey(null)).isFalse();
    assertThat(JdbcIdempotencyStore.isValidKey("a b")).isFalse();
    assertThat(JdbcIdempotencyStore.isValidKey("ă")).isFalse();
    assertThatThrownBy(() -> JdbcIdempotencyStore.validateKey(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IdempotencyScope("Task; DROP", TENANT, ACTOR))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new IdempotencyScope("task", TENANT, ACTOR).schema()).isEqualTo("task");
  }

  @Test
  void banGhiKhongInThanVaSoBamThoiGianHang() {
    byte[] hash = new byte[32];
    IdempotencyRecord r =
        new IdempotencyRecord(
            "k",
            LocalDate.of(2026, 10, 20),
            hash,
            IdempotencyRecord.Status.XONG,
            201,
            "{\"bi_mat\":1}",
            null,
            null);
    assertThat(r.toString()).doesNotContain("bi_mat");
    assertThat(r.sameRequest(new byte[32])).isTrue();
    assertThat(r.sameRequest(new byte[31])).isFalse();
    hash[0] = 1;
    assertThat(r.requestHash()[0]).isZero();
    IdempotencyRecord same =
        new IdempotencyRecord(
            "k",
            LocalDate.of(2026, 10, 20),
            new byte[32],
            IdempotencyRecord.Status.XONG,
            201,
            "{\"bi_mat\":1}",
            null,
            null);
    assertThat(r).isEqualTo(same).hasSameHashCodeAs(same);
    assertThat(r).isNotEqualTo("k");
  }

  @Test
  void nguoiGoiTuPrincipalVaNguCanhTenant() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    IdempotencyCallerResolver none = IdempotencyCallerResolver.fromPrincipal(r -> Optional.empty());
    assertThat(none.resolve(req)).isEmpty();
    IdempotencyCallerResolver resolver =
        IdempotencyCallerResolver.fromPrincipal(r -> Optional.of(TENANT));
    assertThat(resolver.resolve(req)).isEmpty();
    req.setUserPrincipal(() -> "khong-phai-uuid");
    assertThat(resolver.resolve(req)).isEmpty();
    Principal p = () -> ACTOR.toString();
    req.setUserPrincipal(p);
    assertThat(resolver.resolve(req)).contains(new IdempotencyCallerResolver.Caller(TENANT, ACTOR));
  }

  @Test
  void ngoaiLe() {
    assertThat(new IdempotencyExceptions.KeyRequired(true).present()).isTrue();
    assertThat(new IdempotencyExceptions.InProgress(3).retryAfterSeconds()).isEqualTo(3);
    assertThat(new IdempotencyExceptions.Mismatch().errorCode().status()).isEqualTo(422);
  }
}
