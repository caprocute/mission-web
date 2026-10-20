package vn.viettel.okrkpi.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Clock;
import vn.viettel.okrkpi.shared.kernel.Ids;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class AuditLoggerTest {

  private static final Instant NOW = Instant.parse("2026-10-20T01:02:03Z");
  private static final ActorId ACTOR = ActorId.of(Ids.newUuidV7());

  @AfterEach
  void clear() {
    MDC.clear();
  }

  @Test
  void dienMaThoiDiemMaVetVaCheThuocTinh() {
    List<AuditEvent> sent = new ArrayList<>();
    MDC.put("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736");
    AuditLogger logger = new DefaultAuditLogger(sent::add, Clock.fixed(NOW));

    logger.record(
        AuditEvent.builder(AuditEventType.PERMISSION_CHANGE, ACTOR, "GRANT_ROLE")
            .tenant(TenantId.of(Ids.newUuidV7()))
            .actorRole("AC-10")
            .object("assignment", "a1")
            .outcome(AuditOutcome.SUCCESS)
            .attribute("lienHe", "nguyen.van.a@example.vn")
            .attribute("trong", null)
            .build());

    AuditEvent e = sent.get(0);
    assertThat(Ids.isUuidV7(e.eventId())).isTrue();
    assertThat(e.occurredAt()).isEqualTo(NOW);
    assertThat(e.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(e.attributes())
        .containsEntry("lienHe", "[REDACTED:EMAIL]")
        .containsEntry("trong", "");
    assertThat(e.actorRole()).isEqualTo("AC-10");
    assertThat(e.objectType()).isEqualTo("assignment");
  }

  @Test
  void giuMaDaCo() {
    List<AuditEvent> sent = new ArrayList<>();
    UUID id = Ids.newUuidV7();
    AuditEvent pre =
        new AuditEvent(
            id,
            NOW.minusSeconds(5),
            AuditEventType.ADMIN_LOGIN,
            AuditOutcome.DENIED,
            null,
            ACTOR,
            null,
            "LOGIN",
            null,
            null,
            null,
            "trace",
            null);
    new DefaultAuditLogger(sent::add, Clock.fixed(NOW)).record(pre);
    assertThat(sent.get(0).eventId()).isEqualTo(id);
    assertThat(sent.get(0).occurredAt()).isEqualTo(NOW.minusSeconds(5));
    assertThat(sent.get(0).traceId()).isEqualTo("trace");
    assertThat(sent.get(0).attributes()).isEmpty();
  }

  @Test
  void truyCapDuLieuNhayCamPhaiGhiMuc() {
    assertThatThrownBy(
            () ->
                AuditEvent.builder(AuditEventType.SENSITIVE_DATA_ACCESS, ACTOR, "VIEW")
                    .classification(Classification.C1)
                    .build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AuditEvent.builder(AuditEventType.SENSITIVE_DATA_ACCESS, ACTOR, "VIEW").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AuditEvent.builder(AuditEventType.DATA_EXPORT, ACTOR, " ").build())
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            AuditEvent.builder(AuditEventType.SENSITIVE_DATA_ACCESS, ACTOR, "VIEW")
                .classification(Classification.C3)
                .build()
                .classification())
        .isEqualTo(Classification.C3);
  }

  @Test
  void kenhGhiNhatKyTuanTuHoaJson() {
    AuditEvent e =
        AuditEvent.builder(AuditEventType.DATA_EXPORT, ACTOR, "EXPORT_REPORT")
            .build()
            .complete(Ids.newUuidV7(), NOW, null, java.util.Map.of());
    String json = new LoggingAuditSink().toJson(e);
    assertThat(json)
        .contains("\"topic\":\"sys.audit.v1\"")
        .contains("\"occurredAt\":\"2026-10-20T01:02:03Z\"")
        .contains("\"type\":\"DATA_EXPORT\"")
        .contains("\"tenantId\":null");
    new LoggingAuditSink().publish(e);
  }
}
