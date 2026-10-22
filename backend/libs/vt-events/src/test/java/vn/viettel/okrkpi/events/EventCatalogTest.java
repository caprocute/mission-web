package vn.viettel.okrkpi.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.avro.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * NEN-007 · Đối chiếu lược đồ Avro với contracts/events/catalog.yaml.
 *
 * <p>Nguồn quy tắc: baseline mục 7; TL-03 "Phong bì sự kiện", "Danh mục lược đồ EVT-01 đến EVT-26";
 * TL-01 "Quy ước topic".
 */
class EventCatalogTest {

  /** Quy ước topic nghiệp vụ, trùng CHECK ck_outbox_event__topic của TL-03. */
  private static final Pattern BUSINESS_TOPIC =
      Pattern.compile("^[a-z]+\\.[a-z]+\\.[a-z]+\\.v[0-9]+$");

  private static final Pattern KEY = Pattern.compile("^tenant:[a-z][A-Za-z]*$");
  private static final Pattern OUTBOX = Pattern.compile("^([a-z_]+|\\*)\\.outbox_event$");

  /**
   * Thuộc tính phong bì CloudEvents (TL-03 "Phong bì sự kiện") và trường bắt buộc của baseline mục
   * 7 nằm ở tiêu đề Kafka, không được lặp trong thân Avro. {@code aggregateVersion} (mẫu EVT-05 của
   * TL-03) và {@code actorId} của EVT-11 (cột "Trường dữ liệu chính") là trường dữ liệu do TL quy
   * định nên không nằm trong danh sách.
   */
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of(
          "id",
          "source",
          "type",
          "time",
          "subject",
          "specversion",
          "datacontenttype",
          "dataschema",
          "tenantid",
          "tenantId",
          "actorid",
          "traceparent",
          "policyversion",
          "policyVersion",
          "classification",
          "aggregateversion",
          "eventId",
          "occurredAt");

  /**
   * Cột C2 của TL-03 (mọi cột C2 đều có hậu tố {@code _enc}), đổi sang camelCase và bỏ hậu tố. Một
   * trường Avro trùng tên hoặc có hậu tố {@code Enc} bị coi là mang dữ liệu C2.
   */
  private static final Set<String> C2_FIELD_BASES =
      Set.of(
          "birthDate",
          "breakthrough",
          "comment",
          "content",
          "currentValue",
          "detail",
          "email",
          "explanation",
          "fileName",
          "findings",
          "nationalId",
          "partyMemberNo",
          "payload",
          "phone",
          "raw",
          "reason",
          "remedialPlan",
          "requestText",
          "reserveOpinion",
          "response",
          "sourceValue",
          "strengths",
          "trainingPlan",
          "weaknesses");

  /**
   * Trường trùng tên gốc C2 nhưng TL-03 xếp C1: {@code reason} của EVT-26 là lý do huỷ khoá kỳ
   * (config.period_lock_order, C1), không phải {@code appraisal.rating_proposal.reason_enc}.
   */
  private static final Set<String> C2_NAME_ALLOWED =
      Set.of("vn.vtokr.config.v1.KyDaHuyKhoa#reason");

  private static Path contractsDir;
  private static List<Map<String, Object>> events;
  private static List<Map<String, Object>> technical;
  private static Map<String, Schema> schemasByFile;

  @BeforeAll
  static void load() throws IOException {
    String dir = System.getProperty("events.contracts.dir", "../../../contracts/events");
    contractsDir = Path.of(dir).toAbsolutePath().normalize();
    Map<String, Object> root;
    try (InputStream in = Files.newInputStream(contractsDir.resolve("catalog.yaml"))) {
      root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
    }
    assertEquals("BACKWARD_TRANSITIVE", root.get("compatibility"));
    events = list(root, "events");
    technical = list(root, "technicalTopics");

    // Phân tích bằng một Parser chung; EVT-14..EVT-16 trước vì org.bootstrap.v1 tham chiếu tới
    // chúng.
    schemasByFile = new HashMap<>();
    Schema.Parser parser = new Schema.Parser();
    List<Map<String, Object>> all = new ArrayList<>(events);
    all.addAll(technical);
    all.sort((a, b) -> Boolean.compare(a.containsKey("references"), b.containsKey("references")));
    for (Map<String, Object> e : all) {
      String rel = str(e, "schema");
      Path file = contractsDir.resolve(rel);
      assertTrue(Files.isRegularFile(file), "Thiếu tệp lược đồ " + rel);
      schemasByFile.put(rel, parser.parse(file.toFile()));
    }
  }

  @Test
  void du26MaEvtTheoThuTuVaKhongTrung() {
    assertEquals(
        27, events.size(), "Danh mục phải có đúng 27 sự kiện nghiệp vụ (EVT-27 cấp theo WEB-058)");
    for (int i = 0; i < events.size(); i++) {
      assertEquals(String.format(Locale.ROOT, "EVT-%02d", i + 1), str(events.get(i), "code"));
    }
    assertUnique(events, "code");
    assertUnique(events, "name");
    List<Map<String, Object>> all = new ArrayList<>(events);
    all.addAll(technical);
    assertUnique(all, "topic");
    assertUnique(all, "name");
    assertUnique(all, "schema");
    assertUnique(all, "artifactId");
  }

  @Test
  void topicKhoaOutboxPhanLoaiDungQuyUoc() {
    for (Map<String, Object> e : events) {
      String code = str(e, "code");
      String topic = str(e, "topic");
      assertTrue(BUSINESS_TOPIC.matcher(topic).matches(), code + ": topic sai quy ước " + topic);
      assertFalse(topic.startsWith("sys."), code + ": tiền tố sys. dành cho topic kỹ thuật");
      checkCommon(code, e);
    }
    for (Map<String, Object> t : technical) {
      checkCommon(str(t, "topic"), t);
    }
  }

  private static void checkCommon(String label, Map<String, Object> e) {
    String topic = str(e, "topic");
    assertTrue(
        KEY.matcher(str(e, "key")).matches(), label + ": khoá phải dạng tenant:<aggregateId>");
    Object outbox = e.get("outbox");
    if (outbox != null) {
      assertTrue(OUTBOX.matcher(outbox.toString()).matches(), label + ": outbox sai " + outbox);
    }
    String classification = str(e, "classification");
    assertTrue(
        classification.equals("C0") || classification.equals("C1"),
        label + ": sự kiện chỉ được mang dữ liệu C0, C1 (TL-03), gặp " + classification);
    assertNotNull(e.get("producer"), label + ": thiếu producer");
    assertTrue(e.get("consumers") instanceof List<?>, label + ": consumers phải là danh sách");
    assertEquals(
        topic + "-value", str(e, "artifactId"), label + ": artifactId theo TopicIdStrategy");
  }

  @Test
  void luocDoKhopDanhMuc() {
    List<Map<String, Object>> all = new ArrayList<>(events);
    all.addAll(technical);
    for (Map<String, Object> e : all) {
      String label = e.containsKey("code") ? str(e, "code") : str(e, "topic");
      String rel = str(e, "schema");
      Schema s = schemasByFile.get(rel);
      String domain = str(e, "topic").substring(0, str(e, "topic").indexOf('.'));
      assertEquals(Schema.Type.RECORD, s.getType(), label);
      assertEquals(str(e, "name"), s.getName(), label + ": tên record phải là tên nghiệp vụ");
      assertEquals("vn.vtokr." + domain + ".v1", s.getNamespace(), label + ": namespace");
      assertEquals("avro/" + domain + "/" + s.getName() + ".avsc", rel, label + ": đường dẫn tệp");
      assertNotNull(s.getDoc(), label + ": record cần doc");

      // Có aggregateVersion đúng khi sự kiện đi qua outbox (mẫu EVT-05 của TL-03).
      boolean viaOutbox = e.get("outbox") != null;
      boolean business = e.containsKey("code");
      if (business) {
        Schema.Field av = s.getField("aggregateVersion");
        if (viaOutbox) {
          assertNotNull(av, label + ": sự kiện qua outbox phải có aggregateVersion");
          assertEquals(Schema.Type.INT, av.schema().getType(), label + ": aggregateVersion là int");
        } else {
          assertEquals(null, av, label + ": sự kiện không qua outbox không có aggregateVersion");
        }
      }

      // Trường khoá (hoặc dạng số nhiều, như recipientIds của EVT-21) phải có trong lược đồ.
      String keyField = str(e, "key").substring("tenant:".length());
      if (business) {
        assertTrue(
            s.getField(keyField) != null || s.getField(keyField + "s") != null,
            label + ": lược đồ thiếu trường khoá " + keyField);
      }
    }
  }

  @Test
  void truongTuyChonLaUnionCoMacDinhNull() {
    for (Schema s : schemasByFile.values()) {
      for (Schema.Field f : s.getFields()) {
        if (f.schema().getType() == Schema.Type.UNION
            && f.schema().getTypes().get(0).getType() == Schema.Type.NULL) {
          assertTrue(
              f.hasDefaultValue() && f.defaultVal() == org.apache.avro.JsonProperties.NULL_VALUE,
              s.getFullName() + "." + f.name() + ": trường tuỳ chọn phải có default null");
        }
      }
    }
  }

  @Test
  void khongCoThuocTinhPhongBiTrongThan() {
    for (Schema s : schemasByFile.values()) {
      for (Schema.Field f : s.getFields()) {
        assertFalse(
            ENVELOPE_FIELDS.contains(f.name()),
            s.getFullName() + "." + f.name() + " là thuộc tính phong bì, phải nằm ở tiêu đề Kafka");
      }
    }
  }

  @Test
  void khongCoTruongC2() {
    for (Schema s : schemasByFile.values()) {
      for (String path : fieldPaths(s, s.getFullName())) {
        String name = path.substring(path.lastIndexOf('#') + 1);
        assertFalse(name.endsWith("Enc"), path + ": trường bản mã C2 không được vào sự kiện");
        if (C2_FIELD_BASES.contains(name) && !C2_NAME_ALLOWED.contains(path)) {
          fail(path + ": trùng cột C2 của TL-03; sự kiện chỉ mang mã tham chiếu");
        }
      }
    }
  }

  @Test
  void moiTepAvscDeuCoTrongDanhMuc() throws IOException {
    Set<String> inCatalog = new TreeSet<>(schemasByFile.keySet());
    Set<String> onDisk = new TreeSet<>();
    try (Stream<Path> files = Files.walk(contractsDir.resolve("avro"))) {
      files
          .filter(p -> p.toString().endsWith(".avsc"))
          .forEach(p -> onDisk.add(contractsDir.relativize(p).toString().replace('\\', '/')));
    }
    assertEquals(inCatalog, onDisk, "Tệp .avsc và catalog.yaml phải khớp một-một");
  }

  @Test
  void eventTopicsSinhDungTuDanhMuc() throws IllegalAccessException {
    Map<String, String> constants = new HashMap<>();
    for (Field f : EventTopics.class.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
        constants.put(f.getName(), (String) f.get(null));
      }
    }
    List<String> business = new ArrayList<>();
    for (Map<String, Object> e : events) {
      business.add(str(e, "topic"));
      assertEquals(str(e, "topic"), EventTopics.topicOf(str(e, "code")).orElseThrow());
    }
    assertEquals(business, EventTopics.businessTopics());
    List<String> tech = technical.stream().map(t -> str(t, "topic")).toList();
    assertEquals(tech, EventTopics.technicalTopics());
    assertEquals(business.size() + tech.size(), constants.size());
    assertEquals("task.task.progressed.v1", EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT);
    assertEquals("period.drain.marker", EventTopics.PERIOD_DRAIN_MARKER);
    assertEquals("config.access.changed.v1", EventTopics.EVT_27_VAI_TRO_DA_THAY_DOI);
    assertTrue(EventTopics.topicOf("EVT-28").isEmpty());
  }

  @Test
  void profileSchemaCompatLietKeDuLuocDo() throws IOException {
    String pom =
        Files.readString(
            Path.of(System.getProperty("events.pom", "pom.xml")), StandardCharsets.UTF_8);
    String profile = pom.substring(pom.indexOf("<id>schema-compat</id>"));
    String register = section(profile, "<id>schema-register</id>");
    String testUpdate = section(profile, "<id>schema-test-update</id>");
    List<Map<String, Object>> all = new ArrayList<>(events);
    all.addAll(technical);
    for (Map<String, Object> e : all) {
      String rel = str(e, "schema").substring("avro/".length());
      String artifact = "<artifactId>" + str(e, "artifactId") + "</artifactId>";
      assertTrue(register.contains(artifact), "register thiếu " + artifact);
      assertTrue(register.contains("${schema.compat.baselineDir}/" + rel), "register thiếu " + rel);
      if (!e.containsKey("references")) {
        assertTrue(testUpdate.contains(artifact), "test-update thiếu " + artifact);
        assertTrue(
            testUpdate.contains("${schema.compat.candidateDir}/" + rel),
            "test-update thiếu " + rel);
      }
    }
  }

  private static String section(String text, String marker) {
    int start = text.indexOf(marker);
    assertTrue(start >= 0, "Không thấy " + marker);
    int end = text.indexOf("</execution>", start);
    return text.substring(start, end);
  }

  private static List<String> fieldPaths(Schema record, String owner) {
    List<String> out = new ArrayList<>();
    for (Schema.Field f : record.getFields()) {
      out.add(owner + "#" + f.name());
      for (Schema nested : nestedRecords(f.schema())) {
        if (nested.getNamespace() != null && !nested.getFullName().equals(owner)) {
          // Bản ghi lồng (như SignedItem của EVT-18) được kiểm cùng bản ghi chứa nó.
          out.addAll(fieldPaths(nested, nested.getFullName()));
        }
      }
    }
    return out;
  }

  private static List<Schema> nestedRecords(Schema s) {
    return switch (s.getType()) {
      case RECORD -> List.of(s);
      case ARRAY -> nestedRecords(s.getElementType());
      case MAP -> nestedRecords(s.getValueType());
      case UNION -> s.getTypes().stream().flatMap(t -> nestedRecords(t).stream()).toList();
      default -> List.of();
    };
  }

  private static void assertUnique(List<Map<String, Object>> rows, String key) {
    Set<String> seen = new HashSet<>();
    for (Map<String, Object> r : rows) {
      assertTrue(seen.add(str(r, key)), "Trùng " + key + ": " + r.get(key));
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
    return (List<Map<String, Object>>) m.get(key);
  }

  private static String str(Map<String, Object> m, String key) {
    Object v = m.get(key);
    assertNotNull(v, "Thiếu '" + key + "' ở " + m);
    return v.toString();
  }
}
