package vn.viettel.okrkpi.dossier.infrastructure.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import vn.viettel.okrkpi.dossier.application.meeting.MeetingProceedingsService;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxEnvelope;
import vn.viettel.okrkpi.shared.inbox.InboxMessageProcessor;
import vn.viettel.okrkpi.shared.kernel.ActorId;
import vn.viettel.okrkpi.shared.kernel.TenantId;
import vn.vtokr.dossier.v1.HoSoSoDaSinh;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.esign.v1.SignedItem;

/** Kiểm thử đơn vị consumer biên bản (CORE-06-04): lọc mục đích, nguồn và ánh xạ trường. */
class MeetingMinutesConsumerTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());

  private final MeetingProceedingsService service = mock(MeetingProceedingsService.class);

  @SuppressWarnings({"unchecked", "rawtypes"})
  private final MeetingMinutesConsumer consumer =
      new MeetingMinutesConsumer(
          service,
          mock(InboxMessageProcessor.class),
          (ObjectProvider)
              new StaticListableBeanFactory()
                  .getBeanProvider(ConcurrentKafkaListenerContainerFactory.class),
          false);

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static <T extends org.apache.avro.specific.SpecificRecord> InboxEnvelope<T> envelope(
      T payload, UUID actor) {
    InboxEnvelope e = mock(InboxEnvelope.class);
    org.mockito.Mockito.when(e.payload()).thenReturn(payload);
    org.mockito.Mockito.when(e.tenantId()).thenReturn(TENANT);
    org.mockito.Mockito.when(e.actorId()).thenReturn(actor == null ? null : ActorId.of(actor));
    return e;
  }

  @Test
  void dangKyHaiTopicVaChuyenEvt17() {
    assertThat(consumer.generatedEndpoint().topic()).isEqualTo(EventTopics.EVT_17_HO_SO_SO_DA_SINH);
    assertThat(consumer.signedEndpoint().topic()).isEqualTo(EventTopics.EVT_18_KY_SO_DA_HOAN_TAT);
    assertThat(consumer.isAutoStartup()).isFalse();
    assertThat(consumer.processor()).isNotNull();
    UUID job = UUID.randomUUID();
    UUID meeting = UUID.randomUUID();
    byte[] sha = new byte[32];
    sha[1] = 3;
    consumer.onGenerated(
        envelope(
            HoSoSoDaSinh.newBuilder()
                .setRenderJobId(job)
                .setFormInstanceId(meeting)
                .setFormCode("BIEN_BAN")
                .setObjectKey("k")
                .setPdfSha256(ByteBuffer.wrap(sha))
                .setAggregateVersion(1)
                .build(),
            null));
    ArgumentCaptor<MeetingProceedingsService.Generated> g =
        ArgumentCaptor.forClass(MeetingProceedingsService.Generated.class);
    verify(service).onMinutesGenerated(eq(TENANT), g.capture());
    assertThat(g.getValue().renderJobId()).isEqualTo(job);
    assertThat(g.getValue().meetingId()).isEqualTo(meeting);
    assertThat(g.getValue().sha256()).isEqualTo(sha);
  }

  @Test
  void evt18ChiNhanBienBanCuaDossier() {
    UUID signer = UUID.randomUUID();
    consumer.onSigned(envelope(signed("TU_DANH_GIA", "dossier"), signer));
    consumer.onSigned(envelope(signed("BIEN_BAN", "appraisal"), signer));
    verify(service, never()).onSigned(any(), any());
    consumer.onSigned(envelope(signed("BIEN_BAN", "dossier"), signer));
    ArgumentCaptor<MeetingProceedingsService.Signed> s =
        ArgumentCaptor.forClass(MeetingProceedingsService.Signed.class);
    verify(service).onSigned(eq(TENANT), s.capture());
    assertThat(s.getValue().signer()).isEqualTo(signer);
    assertThat(s.getValue().hashes()).hasSize(1);
    consumer.onSigned(envelope(signed("BIEN_BAN", "dossier"), null));
    verify(service, org.mockito.Mockito.times(2)).onSigned(eq(TENANT), any());
  }

  private static KySoDaHoanTat signed(String purpose, String module) {
    return KySoDaHoanTat.newBuilder()
        .setSignRequestId(UUID.randomUUID())
        .setPurpose(purpose)
        .setSourceModule(module)
        .setSourceRefId(UUID.randomUUID())
        .setItems(List.of(new SignedItem(ByteBuffer.wrap(new byte[32]), null)))
        .setCertSha256(ByteBuffer.wrap(new byte[32]))
        .setSignedAt(Instant.parse("2027-01-15T02:00:00Z"))
        .setAggregateVersion(1)
        .build();
  }
}
