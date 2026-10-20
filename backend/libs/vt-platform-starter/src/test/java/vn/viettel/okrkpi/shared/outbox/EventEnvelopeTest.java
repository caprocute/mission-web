package vn.viettel.okrkpi.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.viettel.okrkpi.shared.outbox.OutboxFixtures.TENANT;
import static vn.viettel.okrkpi.shared.outbox.OutboxFixtures.tienDo;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Ids;

/** Phong bì sự kiện (TL-01 "Phong bì sự kiện", TL-03 "Bảng outbox_event"). */
class EventEnvelopeTest {

  private static final String TOPIC = EventTopics.EVT_05_TIEN_DO_DA_CAP_NHAT;

  @Test
  void dungPhongBiMacDinhTuThanAvro() {
    UUID task = UUID.randomUUID();
    EventEnvelope e = OutboxFixtures.envelope(task, 3);

    assertThat(Ids.isUuidV7(e.eventId())).isTrue();
    assertThat(e.topic()).isEqualTo("task.task.progressed.v1");
    assertThat(e.eventType()).isEqualTo("TienDoDaCapNhat");
    assertThat(e.eventKey()).isEqualTo(TENANT + ":" + task);
    assertThat(e.eventKey().length()).isLessThanOrEqualTo(80);
    assertThat(e.actorId()).isNull();
    assertThat(e.policyVersion()).isNull();
    assertThat(e.occurredAt()).isNull();
  }

  @Test
  void banSaoGiuMaSuKienVaThemThuocTinh() {
    EventEnvelope e = OutboxFixtures.envelope(UUID.randomUUID(), 1);
    ActorId actor = ActorId.of(UUID.randomUUID());
    Instant at = Instant.parse("2026-10-20T01:02:03Z");

    EventEnvelope full = e.withActor(actor).withPolicyVersion("pp-2026.1").withOccurredAt(at);

    assertThat(full.eventId()).isEqualTo(e.eventId());
    assertThat(full.actorId()).isEqualTo(actor);
    assertThat(full.policyVersion()).isEqualTo("pp-2026.1");
    assertThat(full.occurredAt()).isEqualTo(at);
    assertThat(full.payload()).isSameAs(e.payload());
  }

  @ParameterizedTest
  @EnumSource(
      value = Classification.class,
      names = {"C2", "C3"})
  void tuChoiDuLieuNhayCam(Classification level) {
    UUID task = UUID.randomUUID();
    assertThatThrownBy(
            () -> EventEnvelope.of(TOPIC, TENANT, "Task", task, 1, tienDo(task, 1), level))
        .isInstanceOf(SensitiveEventException.class)
        .hasMessageContaining(level.name())
        .satisfies(
            ex -> assertThat(((SensitiveEventException) ex).classification()).isEqualTo(level));
  }

  @ParameterizedTest
  @EnumSource(
      value = Classification.class,
      names = {"C0", "C1"})
  void nhanDuLieuCongKhaiVaNoiBo(Classification level) {
    UUID task = UUID.randomUUID();
    assertThat(
            EventEnvelope.of(TOPIC, TENANT, "Task", task, 1, tienDo(task, 1), level)
                .classification())
        .isEqualTo(level);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        // Topic kỹ thuật ba đoạn: CHECK ck_outbox_event__topic (NEN-032) không nhận, xem báo cáo.
        "sys.audit.v1",
        "Task.task.progressed.v1",
        "task.task.progressed",
        "task.task-x.progressed.v1",
        "task.task.progressed.v1.retry"
      })
  void tuChoiTopicSaiQuyUoc(String topic) {
    UUID task = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                EventEnvelope.of(
                    topic, TENANT, "Task", task, 1, tienDo(task, 1), Classification.C1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Topic");
  }

  @Test
  void tuChoiPhienBanLechThan() {
    UUID task = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                EventEnvelope.of(
                    TOPIC, TENANT, "Task", task, 4, tienDo(task, 3), Classification.C1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("aggregateVersion");
  }

  @Test
  void tuChoiThamSoSai() {
    UUID task = UUID.randomUUID();
    var payload = tienDo(task, 1);
    assertThatThrownBy(
            () -> EventEnvelope.of(TOPIC, TENANT, " ", task, 1, payload, Classification.C1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                EventEnvelope.of(
                    TOPIC, TENANT, "T".repeat(65), task, 1, payload, Classification.C1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> EventEnvelope.of(TOPIC, TENANT, "Task", task, -1, payload, Classification.C1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> EventEnvelope.of(TOPIC, TENANT, "Task", task, 1, payload, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> EventEnvelope.of(TOPIC, null, "Task", task, 1, payload, Classification.C1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> EventEnvelope.of(TOPIC, TENANT, "Task", task, 1, null, Classification.C1))
        .isInstanceOf(NullPointerException.class);
    EventEnvelope ok = EventEnvelope.of(TOPIC, TENANT, "Task", task, 1, payload, Classification.C1);
    assertThatThrownBy(() -> ok.withPolicyVersion("x".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new EventEnvelope(
                    UUID.randomUUID(),
                    TOPIC,
                    TENANT,
                    "Task",
                    task,
                    1,
                    "TienDoDaCapNhat",
                    payload,
                    Classification.C1,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UUIDv7");
  }
}
