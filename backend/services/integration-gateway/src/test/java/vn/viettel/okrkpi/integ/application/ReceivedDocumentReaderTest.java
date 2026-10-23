package vn.viettel.okrkpi.integ.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import vn.viettel.okrkpi.integ.application.port.InboxMessages;
import vn.viettel.okrkpi.integ.application.port.InboxMessages.Raw;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs.Located;
import vn.viettel.okrkpi.integ.application.port.MappingXrefs.Mapping;
import vn.viettel.okrkpi.integ.application.port.PayloadCipher;
import vn.viettel.okrkpi.integ.application.port.ReceivedDocumentParser;
import vn.viettel.okrkpi.integ.application.port.ReceivedDocumentParser.Parsed;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.InboxStatus;
import vn.viettel.okrkpi.shared.kernel.TenantId;

class ReceivedDocumentReaderTest {

  static final TenantId TENANT = TenantId.of(UUID.randomUUID());
  static final UUID DOC = UUID.randomUUID();
  static final UUID UNIT = UUID.randomUUID();
  static final UUID GONE = UUID.randomUUID();
  static final UUID VOFFICE = UUID.randomUUID();
  static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

  private final MappingXrefs mappings = mock(MappingXrefs.class);
  private final InboxMessages inbox = mock(InboxMessages.class);
  private final PayloadCipher cipher = mock(PayloadCipher.class);
  private final ReceivedDocumentParser parser = mock(ReceivedDocumentParser.class);
  private final ReceivedDocumentReader reader =
      new ReceivedDocumentReader(
          mappings,
          inbox,
          cipher,
          parser,
          TransactionOperations.withoutTransaction(),
          Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneOffset.UTC));

  static Located located(UUID connector, String external) {
    return new Located(connector, new Mapping(UUID.randomUUID(), external, DOC, TODAY));
  }

  static Parsed parsed(String unit) {
    return new Parsed("EdXML", unit, "H", "T", "", List.of(), "", "C1");
  }

  @Test
  void decryptsLatestMessageAndMapsRecipient() {
    Raw raw =
        new Raw(
            UUID.randomUUID(),
            TODAY,
            VOFFICE,
            "VO-9:2",
            "EDXML",
            new byte[] {9},
            InboxStatus.DA_PHAT);
    when(mappings.byInternalAnyConnector(TENANT, EntityType.VAN_BAN, DOC, TODAY))
        .thenReturn(List.of(located(GONE, "TRUC-1"), located(VOFFICE, "VO-9")));
    when(inbox.latestEmitted(TENANT, GONE, "TRUC-1")).thenReturn(Optional.empty());
    when(inbox.latestEmitted(TENANT, VOFFICE, "VO-9")).thenReturn(Optional.of(raw));
    when(cipher.open(TENANT, InboundPipeline.RAW_COLUMN, raw.id(), raw.rawEnc()))
        .thenReturn(new byte[] {1});
    when(parser.parse(eq("EDXML"), any())).thenReturn(parsed("000.12"));
    when(mappings.byExternal(TENANT, VOFFICE, EntityType.DON_VI, "000.12", TODAY))
        .thenReturn(Optional.of(new Mapping(UUID.randomUUID(), "000.12", UNIT, TODAY)));

    ReceivedDocumentReader.ReceivedDocument d = reader.read(TENANT, DOC).orElseThrow();

    assertThat(d.recipientUnitId()).isEqualTo(UNIT);
    assertThat(d.content().title()).isEqualTo("T");
  }

  @Test
  void unknownDocumentOrMissingRecipientMapping() {
    when(mappings.byInternalAnyConnector(TENANT, EntityType.VAN_BAN, DOC, TODAY))
        .thenReturn(List.of());
    assertThat(reader.read(TENANT, DOC)).isEmpty();

    Raw raw =
        new Raw(
            UUID.randomUUID(),
            TODAY,
            VOFFICE,
            "VO-9",
            "VOFFICE_JSON",
            new byte[] {9},
            InboxStatus.DA_PHAT);
    when(mappings.byInternalAnyConnector(TENANT, EntityType.VAN_BAN, DOC, TODAY))
        .thenReturn(List.of(located(VOFFICE, "VO-9")));
    when(inbox.latestEmitted(TENANT, VOFFICE, "VO-9")).thenReturn(Optional.of(raw));
    when(cipher.open(any(), any(), any(), any())).thenReturn(new byte[] {1});
    when(parser.parse(eq("VOFFICE_JSON"), any())).thenReturn(parsed(" "));

    assertThat(reader.read(TENANT, DOC).orElseThrow().recipientUnitId()).isNull();
    verify(mappings, never()).byExternal(any(), any(), any(), any(), any());

    when(parser.parse(eq("VOFFICE_JSON"), any())).thenReturn(parsed(null));
    assertThat(reader.read(TENANT, DOC).orElseThrow().recipientUnitId()).isNull();
    when(parser.parse(eq("VOFFICE_JSON"), any())).thenReturn(parsed("X"));
    when(mappings.byExternal(TENANT, VOFFICE, EntityType.DON_VI, "X", TODAY))
        .thenReturn(Optional.empty());
    assertThat(reader.read(TENANT, DOC).orElseThrow().recipientUnitId()).isNull();
  }
}
