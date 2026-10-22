package vn.viettel.okrkpi.analytics.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class TenantPseudonymizeTest {

  private static final String KEY = "khoa-chu-kiem-thu-khong-dung-that-0123456789";
  private static final String A = "0192f000-0000-7000-8000-00000000000a";
  private static final String B = "0192f000-0000-7000-8000-00000000000b";
  private static final String PERSON = "0192f2c4-5d6e-7f80-91a2-b3c4d5e6f701";

  private static final Schema KEY_SCHEMA =
      SchemaBuilder.struct().field("tenant_id", Schema.STRING_SCHEMA).field("id", Schema.STRING_SCHEMA).build();
  private static final Schema VALUE_SCHEMA =
      SchemaBuilder.struct()
          .field("tenant_id", Schema.STRING_SCHEMA)
          .field("id", Schema.STRING_SCHEMA)
          .field("created_by", Schema.STRING_SCHEMA)
          .field("progress_pct", Schema.OPTIONAL_STRING_SCHEMA)
          .build();

  private static TenantPseudonymize<SourceRecord> smt() {
    TenantPseudonymize<SourceRecord> t = new TenantPseudonymize<>();
    t.configure(
        Map.of(
            "topic.prefix", "cdc.tenant_data",
            "fields", "task.task_progress:created_by, org_replica.person_v:id",
            "key", KEY));
    return t;
  }

  private static SourceRecord record(String topic, String tenant, String id, String createdBy) {
    Struct k = new Struct(KEY_SCHEMA).put("tenant_id", tenant).put("id", id);
    Struct v =
        new Struct(VALUE_SCHEMA)
            .put("tenant_id", tenant)
            .put("id", id)
            .put("created_by", createdBy)
            .put("progress_pct", "0.5000");
    return new SourceRecord(Map.of(), Map.of(), topic, 0, KEY_SCHEMA, k, VALUE_SCHEMA, v);
  }

  static String expected(String tenant, String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] k = mac.doFinal((TenantPseudonymize.CONTEXT + tenant).getBytes(StandardCharsets.UTF_8));
    mac.init(new SecretKeySpec(k, "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void hashesConfiguredValueFieldPerTenant() throws Exception {
    TenantPseudonymize<SourceRecord> t = smt();
    Struct a = (Struct) t.apply(record("cdc.tenant_data.task.task_progress", A, "p1", PERSON)).value();
    Struct b = (Struct) t.apply(record("cdc.tenant_data.task.task_progress", B, "p2", PERSON)).value();
    assertEquals(expected(A, PERSON), a.getString("created_by"));
    assertEquals(64, a.getString("created_by").length());
    assertNotEquals(a.getString("created_by"), b.getString("created_by"));
    assertEquals("p1", a.getString("id"));
    assertEquals("0.5000", a.getString("progress_pct"));
  }

  @Test
  void hashesKeyFieldsToo() throws Exception {
    SourceRecord out = smt().apply(record("cdc.tenant_data.org_replica.person_v", A, PERSON, PERSON));
    assertEquals(expected(A, PERSON), ((Struct) out.key()).getString("id"));
    assertEquals(expected(A, PERSON), ((Struct) out.value()).getString("id"));
    assertEquals(PERSON, ((Struct) out.value()).getString("created_by"), "cột không cấu hình giữ nguyên");
  }

  @Test
  void leavesOtherTopicsUntouched() {
    SourceRecord in = record("cdc.tenant_data.task.task", A, "t1", PERSON);
    assertSame(in, smt().apply(in));
    SourceRecord other = record("outbox.task", A, "t1", PERSON);
    assertSame(other, smt().apply(other));
  }

  @Test
  void refusesToPassClearIdWithoutTenant() {
    Schema s = SchemaBuilder.struct().field("created_by", Schema.STRING_SCHEMA).build();
    SourceRecord in =
        new SourceRecord(
            Map.of(), Map.of(), "cdc.tenant_data.task.task_progress", 0, null, null, s,
            new Struct(s).put("created_by", PERSON));
    assertThrows(DataException.class, () -> smt().apply(in));
  }

  @Test
  void rejectsShortKeyAndBadFields() {
    TenantPseudonymize<SourceRecord> t = new TenantPseudonymize<>();
    assertThrows(
        ConfigException.class,
        () -> t.configure(Map.of("topic.prefix", "cdc.x", "fields", "a.b:c", "key", "ngan")));
    assertThrows(
        ConfigException.class,
        () -> t.configure(Map.of("topic.prefix", "cdc.x", "fields", "a.b", "key", KEY)));
    assertTrue(smt().fields().containsKey("task.task_progress"));
  }
}
