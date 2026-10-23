package vn.viettel.okrkpi.integ.application.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.outbound.OutboundRejectedException.Reason;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs;
import vn.viettel.okrkpi.integ.application.port.PayloadSigner;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.viettel.okrkpi.shared.outbox.OutboxHeaders;

/** Chuyển sự kiện sang CloudEvents ra ngoài và chặn theo chính sách chia sẻ (DV-13-02). */
public class CloudEventsMapperTest {

  static final ObjectMapper JSON = new ObjectMapper();
  static final Instant NOW = Instant.parse("2027-01-15T09:30:12.512Z");
  public static final String TOPIC = "appraisal.rating.decided.v1";
  public static final UUID EVENT = UUID.fromString("0192a3f4-7c1e-7b2a-9d41-5f0c8e7a1b23");
  static final UUID TENANT = UUID.fromString("0191f000-aaaa-7bbb-8ccc-dddd0000eeee");
  static final String TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  final CloudEventsMapper mapper =
      new CloudEventsMapper(
          "cell-hcm",
          "https://api.okr.viettel.vn/schemas/",
          JSON,
          Clock.fixed(NOW, ZoneOffset.UTC));
  final OutboundPolicy cbccvc = OutboundPolicy.defaults("CSDL_CBCCVC");

  /** Dữ liệu EVT-12 của ví dụ TL-01 sau khi đã đổi sang mã ngoài. */
  public static ObjectNode evt12Data() throws Exception {
    return (ObjectNode)
        JSON.readTree(
            """
            {"periodCode":"2026-Q4",
             "subjectRef":{"scheme":"cbccvc","id":"079085001234"},
             "flow":"NHA_NUOC","rating":"HTTNV","score":84.25,
             "decidedBy":{"scheme":"cbccvc","id":"079078004321"},
             "signatureRef":"esign:0192a3f4-0000-7000-8000-000000000001"}
            """);
  }

  public static Map<String, String> headers() {
    Map<String, String> h = new HashMap<>();
    h.put(OutboxHeaders.EVENT_ID, EVENT.toString());
    h.put(OutboxHeaders.TENANT_ID, TENANT.toString());
    h.put(OutboxHeaders.TRACEPARENT, TRACE);
    h.put(OutboxHeaders.CLASSIFICATION, "C1");
    h.put(OutboxHeaders.POLICY_VERSION, "khoi-dang/quy-dinh-moi@2.1.0");
    h.put(OutboxHeaders.OCCURRED_AT, NOW.toString());
    return h;
  }

  OutboundEvent evt12(Classification dataClassification) throws Exception {
    return mapper.fromHeaders(
        TOPIC,
        headers(),
        "appraisal-profile/0192a3e0-1111-7aaa-8bbb-222233334444",
        evt12Data(),
        dataClassification);
  }

  @Test
  void evt12MatchesTl01ExampleWithChannelClassification() throws Exception {
    JsonNode actual = JSON.readTree(mapper.toJson(evt12(Classification.C1), cbccvc));
    // Ví dụ TL-01 ghi classification C2; kênh liên thông chỉ mang C0, C1 (AsyncAPI), wiki 14.
    JsonNode expected =
        JSON.readTree(
            """
            {"specversion":"1.0",
             "id":"0192a3f4-7c1e-7b2a-9d41-5f0c8e7a1b23",
             "source":"urn:vt-okrkpi:cell-hcm:appraisal",
             "type":"vn.viettel.okrkpi.appraisal.rating.decided.v1",
             "subject":"appraisal-profile/0192a3e0-1111-7aaa-8bbb-222233334444",
             "time":"2027-01-15T09:30:12.512Z",
             "datacontenttype":"application/json",
             "dataschema":"https://api.okr.viettel.vn/schemas/appraisal.rating.decided.v1.json",
             "traceparent":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
             "tenantid":"0191f000-aaaa-7bbb-8ccc-dddd0000eeee",
             "classification":"C1",
             "policyversion":"khoi-dang/quy-dinh-moi@2.1.0",
             "data":%s}
            """
                .formatted(evt12Data()));
    assertThat(actual).isEqualTo(expected);
    assertThat(iterable(actual.fieldNames()))
        .containsExactly(
            "specversion",
            "id",
            "source",
            "type",
            "subject",
            "time",
            "datacontenttype",
            "dataschema",
            "traceparent",
            "tenantid",
            "classification",
            "policyversion",
            "data");
  }

  static <T> Iterable<T> iterable(java.util.Iterator<T> it) {
    return () -> it;
  }

  @Test
  void optionalAttributesAreOmittedAndDefaultsApplied() throws Exception {
    Map<String, String> h = new HashMap<>();
    h.put(OutboxHeaders.EVENT_ID, EVENT.toString());
    h.put(OutboxHeaders.TENANT_ID, TENANT.toString());
    OutboundEvent e =
        mapper.fromHeaders("ledger.seal.created.v1", h, null, JSON.createObjectNode(), null);
    assertThat(e.time()).isEqualTo(NOW);
    assertThat(e.classification()).isEqualTo(Classification.C1);
    assertThat(e.dataClassification()).isEqualTo(Classification.C1);
    assertThat(e.toString()).doesNotContain("data=");
    ObjectNode ce = mapper.toCloudEvent(e, cbccvc);
    assertThat(ce.has("subject")).isFalse();
    assertThat(ce.has("traceparent")).isFalse();
    assertThat(ce.has("policyversion")).isFalse();
    assertThat(ce.path("source").asText()).isEqualTo("urn:vt-okrkpi:cell-hcm:ledger");
  }

  @Test
  void c2OrC3DataNeverLeaves() throws Exception {
    assertRejected(
        () -> mapper.toJson(evt12(Classification.C2), cbccvc), Reason.CLASSIFICATION_NOT_ALLOWED);
    assertRejected(
        () -> mapper.toJson(evt12(Classification.C3), cbccvc), Reason.CLASSIFICATION_NOT_ALLOWED);
    Map<String, String> h = headers();
    h.put(OutboxHeaders.CLASSIFICATION, "c2");
    OutboundEvent labelled = mapper.fromHeaders(TOPIC, h, null, evt12Data(), Classification.C0);
    assertThat(labelled.effectiveClassification()).isEqualTo(Classification.C2);
    assertRejected(() -> mapper.toJson(labelled, cbccvc), Reason.CLASSIFICATION_NOT_ALLOWED);
    OutboundPolicy publicOnly =
        new OutboundPolicy("CONG_KHAI", Set.of(Classification.C0), JwsMode.COMPACT, false);
    assertRejected(
        () -> mapper.toJson(evt12(Classification.C1), publicOnly),
        Reason.CLASSIFICATION_NOT_ALLOWED);
  }

  @Test
  void sealedValuesAreRejectedWherever() throws Exception {
    for (String data :
        new String[] {
          "{\"nhanXet\":{\"c2\":\"AQID\"}}",
          "{\"list\":[1,{\"c3\":\"AQID\"}]}",
          "{\"hoTen_enc\":\"x\"}",
          "{\"commentEnc\":\"x\"}"
        }) {
      OutboundEvent e =
          mapper.fromHeaders(TOPIC, headers(), null, (ObjectNode) JSON.readTree(data), null);
      assertRejected(() -> mapper.toJson(e, cbccvc), Reason.SEALED_VALUE);
    }
    ObjectNode binary = JSON.createObjectNode();
    binary.put("blob", new byte[] {1, 2, 3});
    OutboundEvent e = mapper.fromHeaders(TOPIC, headers(), null, binary, null);
    assertRejected(() -> mapper.toJson(e, cbccvc), Reason.SEALED_VALUE);
    ObjectNode twoKeys = (ObjectNode) JSON.readTree("{\"x\":{\"c2\":1,\"y\":2}}");
    assertThat(
            mapper
                .toCloudEvent(mapper.fromHeaders(TOPIC, headers(), null, twoKeys, null), cbccvc)
                .path("data"))
        .isEqualTo(twoKeys);
  }

  @Test
  void invalidEnvelopeInputsAreRejected() throws Exception {
    assertRejected(
        () -> mapper.fromHeaders(TOPIC, Map.of(), null, evt12Data(), null),
        Reason.INVALID_ENVELOPE);
    Map<String, String> bad = headers();
    bad.put(OutboxHeaders.TENANT_ID, "khong-phai-uuid");
    assertRejected(
        () -> mapper.fromHeaders(TOPIC, bad, null, evt12Data(), null), Reason.INVALID_ENVELOPE);
    Map<String, String> badTime = headers();
    badTime.put(OutboxHeaders.OCCURRED_AT, "hôm qua");
    assertRejected(
        () -> mapper.fromHeaders(TOPIC, badTime, null, evt12Data(), null), Reason.INVALID_ENVELOPE);
    Map<String, String> badLevel = headers();
    badLevel.put(OutboxHeaders.CLASSIFICATION, "C9");
    assertRejected(
        () -> mapper.fromHeaders(TOPIC, badLevel, null, evt12Data(), null),
        Reason.INVALID_ENVELOPE);

    assertRejected(
        () ->
            mapper.toJson(
                mapper.fromHeaders("Sai_Topic", headers(), null, evt12Data(), null), cbccvc),
        Reason.INVALID_ENVELOPE);
    Map<String, String> badTrace = headers();
    badTrace.put(OutboxHeaders.TRACEPARENT, "01-xyz");
    assertRejected(
        () -> mapper.toJson(mapper.fromHeaders(TOPIC, badTrace, null, evt12Data(), null), cbccvc),
        Reason.INVALID_ENVELOPE);
    assertRejected(
        () ->
            mapper.toJson(
                mapper.fromHeaders(TOPIC, headers(), "s".repeat(257), evt12Data(), null), cbccvc),
        Reason.INVALID_ENVELOPE);
    Map<String, String> longPolicy = headers();
    longPolicy.put(OutboxHeaders.POLICY_VERSION, "v".repeat(129));
    assertRejected(
        () -> mapper.toJson(mapper.fromHeaders(TOPIC, longPolicy, null, evt12Data(), null), cbccvc),
        Reason.INVALID_ENVELOPE);
    assertThatThrownBy(
            () -> new CloudEventsMapper("Cell HCM", "https://x", JSON, Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recheckBeforeSendingCatchesNarrowedPolicyAndBadPayload() throws Exception {
    byte[] ce = mapper.toJson(evt12(Classification.C1), cbccvc);
    mapper.recheck(ce, cbccvc);
    OutboundPolicy narrowed =
        new OutboundPolicy("CSDL_CBCCVC", Set.of(Classification.C0), JwsMode.DETACHED, true);
    assertRejected(() -> mapper.recheck(ce, narrowed), Reason.CLASSIFICATION_NOT_ALLOWED);
    assertRejected(
        () -> mapper.recheck("không phải json".getBytes(StandardCharsets.UTF_8), cbccvc),
        Reason.INVALID_ENVELOPE);
    assertRejected(
        () -> mapper.recheck("{\"specversion\":\"0.3\"}".getBytes(StandardCharsets.UTF_8), cbccvc),
        Reason.INVALID_ENVELOPE);
    assertRejected(
        () ->
            mapper.recheck(
                "{\"specversion\":\"1.0\",\"classification\":\"X\"}"
                    .getBytes(StandardCharsets.UTF_8),
                cbccvc),
        Reason.INVALID_ENVELOPE);
    assertRejected(
        () ->
            mapper.recheck(
                "{\"specversion\":\"1.0\",\"classification\":\"C1\",\"data\":{\"a\":{\"c2\":\"x\"}}}"
                    .getBytes(StandardCharsets.UTF_8),
                cbccvc),
        Reason.SEALED_VALUE);
  }

  @Test
  void policyNeverAllowsSensitiveLevels() {
    assertThatThrownBy(
            () -> new OutboundPolicy("X", Set.of(Classification.C2), JwsMode.DETACHED, true))
        .isInstanceOf(IllegalArgumentException.class);
    OutboundPolicy p = new OutboundPolicy("X", Set.of(), null, false);
    assertThat(p.allowed()).containsExactlyInAnyOrder(Classification.C0, Classification.C1);
    assertThat(p.jwsMode()).isEqualTo(JwsMode.DETACHED);
    assertThat(p.permits(Classification.C3)).isFalse();
  }

  @Test
  void signedMessageCarriesDetachedHeaderOrCompactBody() {
    PayloadSigner signer =
        new PayloadSigner() {
          @Override
          public String signDetached(byte[] payload) {
            return "h..s";
          }

          @Override
          public String signCompact(byte[] payload) {
            return "h.p.s";
          }

          @Override
          public String publicJwks() {
            return "{\"keys\":[]}";
          }
        };
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    SignedMessage detached = SignedMessage.sign(body, JwsMode.DETACHED, signer);
    assertThat(detached.body()).isEqualTo(body);
    assertThat(detached.contentType()).isEqualTo(SignedMessage.CLOUDEVENTS_JSON);
    assertThat(detached.headers()).containsEntry(SignedMessage.SIGNATURE_HEADER, "h..s");
    SignedMessage compact = SignedMessage.sign(body, JwsMode.COMPACT, signer);
    assertThat(new String(compact.body(), StandardCharsets.US_ASCII)).isEqualTo("h.p.s");
    assertThat(compact.contentType()).isEqualTo(SignedMessage.JOSE);
    assertThat(compact.headers()).isEmpty();
  }

  @Test
  void externalRefsUseMappingOrRefuse() {
    MappingXrefs xrefs = mock(MappingXrefs.class);
    TenantId tenant = TenantId.of(TENANT);
    UUID connector = UUID.randomUUID();
    UUID person = UUID.randomUUID();
    LocalDate day = LocalDate.of(2027, 1, 15);
    when(xrefs.byInternal(any(), eq(connector), eq(EntityType.NHAN_SU), eq(person), eq(day)))
        .thenReturn(
            Optional.of(new MappingXrefs.Mapping(UUID.randomUUID(), "079085001234", person, day)));
    ExternalRefs refs = new ExternalRefs(new MappingXrefService(xrefs), JSON);
    ObjectNode ref = refs.require(tenant, connector, EntityType.NHAN_SU, person, "cbccvc", day);
    assertThat(ref.toString()).isEqualTo("{\"scheme\":\"cbccvc\",\"id\":\"079085001234\"}");
    UUID other = UUID.randomUUID();
    assertRejected(
        () -> refs.require(tenant, connector, EntityType.NHAN_SU, other, "cbccvc", day),
        Reason.UNMAPPED_REFERENCE);
    assertThatThrownBy(() -> refs.require(tenant, connector, EntityType.NHAN_SU, person, " ", day))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bindingBuildsEnvelopeFromHeaders() throws Exception {
    CloudEventsBinding binding =
        new CloudEventsBinding(mapper, cbccvc) {
          @Override
          public String topic() {
            return TOPIC;
          }

          @Override
          public String messageType() {
            return "XEP_LOAI";
          }

          @Override
          protected Converted convert(byte[] eventPayload, Map<String, String> headers) {
            try {
              return new Converted("appraisal-profile/1", evt12Data(), Classification.C1);
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          }
        };
    assertThat(binding.connectorCode()).isEqualTo("CSDL_CBCCVC");
    JsonNode ce = JSON.readTree(binding.format(new byte[0], headers()));
    assertThat(ce.path("type").asText()).isEqualTo("vn.viettel.okrkpi." + TOPIC);
    assertThat(ce.path("subject").asText()).isEqualTo("appraisal-profile/1");
  }

  static void assertRejected(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Reason reason) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            OutboundRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason))
        .hasMessageNotContaining("079085001234");
  }
}
