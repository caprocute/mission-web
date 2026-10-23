package vn.viettel.okrkpi.integ.application.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.MappingXrefService;
import vn.viettel.okrkpi.integ.application.outbound.CloudEventsBinding.Converted;
import vn.viettel.okrkpi.integ.application.outbound.ExternalRefs;
import vn.viettel.okrkpi.integ.application.outbound.OutboundRejectedException;
import vn.viettel.okrkpi.integ.application.outbound.OutboundRejectedException.Reason;
import vn.viettel.okrkpi.integ.application.port.Connectors;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs.Mapping;
import vn.viettel.okrkpi.integ.application.rating.SubjectChains.Subject;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.Direction;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.Protocol;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class RatingShareConverterTest {

  static final ObjectMapper JSON = new ObjectMapper();
  static final TenantId TENANT =
      TenantId.of(UUID.fromString("0192f1a2-0000-7000-8000-0000001309a1"));
  static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
  static final UUID PERSON = UUID.randomUUID();
  static final UUID DECIDER = UUID.randomUUID();
  static final Connector CBCCVC =
      new Connector(
          UUID.randomUUID(),
          TENANT.value(),
          "CBCCVC",
          Protocol.REST,
          "https://ndxp.invalid",
          "kv/integ/cbccvc",
          Direction.HAI_CHIEU,
          null,
          true);

  final Connectors connectors = mock(Connectors.class);
  final MappingXrefs xrefs = mock(MappingXrefs.class);
  final SubjectChains chains = mock(SubjectChains.class);
  final MappingXrefService mappings = new MappingXrefService(xrefs);
  final RatingShareConverter converter =
      new RatingShareConverter(
          "CBCCVC",
          connectors,
          new ExternalRefs(mappings, JSON),
          mappings,
          chains,
          JSON,
          TransactionOperations.withoutTransaction(),
          Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneOffset.UTC));

  {
    when(connectors.findByCode(TENANT, "CBCCVC")).thenReturn(Optional.of(CBCCVC));
    mapped(PERSON, "079085001234");
  }

  void mapped(UUID internal, String external) {
    when(xrefs.byInternal(TENANT, CBCCVC.id(), EntityType.NHAN_SU, internal, TODAY))
        .thenReturn(Optional.of(new Mapping(UUID.randomUUID(), external, internal, TODAY)));
  }

  static RatingDecided rating(UUID profile, UUID decider, UUID sign, int version) {
    return new RatingDecided(profile, PERSON, "NHA_NUOC", "HTTNV", decider, sign, version);
  }

  @Test
  void firstDecisionUsesExternalCodesAndStartsChain() {
    mapped(DECIDER, "079078004321");
    UUID profile = UUID.randomUUID();
    UUID event = UUID.randomUUID();
    UUID sign = UUID.randomUUID();
    when(chains.lock(TENANT, CBCCVC.id(), "appraisal-profile/" + profile))
        .thenReturn(Optional.empty());

    Converted c = converter.convert(TENANT, event, rating(profile, DECIDER, sign, 1));

    assertThat(converter.connectorCode()).isEqualTo("CBCCVC");
    assertThat(c.subject()).isEqualTo("appraisal-profile/" + profile);
    assertThat(c.dataClassification()).isEqualTo(Classification.C1);
    assertThat(c.data().toString())
        .isEqualTo(
            "{\"subjectRef\":{\"scheme\":\"cbccvc\",\"id\":\"079085001234\"},\"flow\":\"NHA_NUOC\","
                + "\"rating\":\"HTTNV\",\"decidedBy\":{\"scheme\":\"cbccvc\",\"id\":\"079078004321\"},"
                + "\"signatureRef\":\"esign:"
                + sign
                + "\"}");
    verify(chains).insert(eq(TENANT), any(), eq(CBCCVC.id()), eq(c.subject()), eq(event), eq(1));
  }

  @Test
  void redeliveryKeepsSameDataAndReplacementPointsToPrevious() {
    UUID profile = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID row = UUID.randomUUID();
    String key = "appraisal-profile/" + profile;

    when(chains.lock(TENANT, CBCCVC.id(), key))
        .thenReturn(Optional.of(new Subject(row, first, null, 1)));
    Converted again = converter.convert(TENANT, first, rating(profile, null, null, 1));
    assertThat(again.data().has("supersedes")).isFalse();
    assertThat(again.data().has("decidedBy")).isFalse();
    assertThat(again.data().has("signatureRef")).isFalse();

    Converted replaced = converter.convert(TENANT, second, rating(profile, DECIDER, null, 2));
    assertThat(replaced.data().path("supersedes").asText()).isEqualTo(first.toString());
    assertThat(replaced.data().has("decidedBy")).as("người quyết định chưa có mã ngoài").isFalse();
    verify(chains).advance(TENANT, row, second, first, 2);

    when(chains.lock(TENANT, CBCCVC.id(), key))
        .thenReturn(Optional.of(new Subject(row, second, first, 2)));
    assertThat(
            converter
                .convert(TENANT, second, rating(profile, null, null, 2))
                .data()
                .path("supersedes")
                .asText())
        .isEqualTo(first.toString());
  }

  @Test
  void staleDecisionIsRejected() {
    UUID profile = UUID.randomUUID();
    when(chains.lock(TENANT, CBCCVC.id(), "appraisal-profile/" + profile))
        .thenReturn(Optional.of(new Subject(UUID.randomUUID(), UUID.randomUUID(), null, 3)));

    assertThatThrownBy(
            () -> converter.convert(TENANT, UUID.randomUUID(), rating(profile, null, null, 3)))
        .isInstanceOfSatisfying(
            OutboundRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.OUT_OF_ORDER));
    verify(chains, never())
        .advance(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void unmappedPersonOrConnectorIsRefused() {
    RatingDecided other =
        new RatingDecided(UUID.randomUUID(), UUID.randomUUID(), "DANG", "HTNV", null, null, 1);
    assertThatThrownBy(() -> converter.convert(TENANT, UUID.randomUUID(), other))
        .isInstanceOfSatisfying(
            OutboundRejectedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.UNMAPPED_REFERENCE));

    TenantId stranger = TenantId.of(UUID.randomUUID());
    when(connectors.findByCode(stranger, "CBCCVC")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> converter.convert(stranger, UUID.randomUUID(), other))
        .isInstanceOf(NoSuchElementException.class);
    assertThatThrownBy(() -> new RatingDecided(null, PERSON, "DANG", "HTNV", null, null, 1))
        .isInstanceOf(NullPointerException.class);
  }
}
