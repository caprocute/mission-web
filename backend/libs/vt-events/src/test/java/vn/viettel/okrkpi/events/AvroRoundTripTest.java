package vn.viettel.okrkpi.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.avro.util.ClassSecurityValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import vn.vtokr.esign.v1.KySoDaHoanTat;
import vn.vtokr.esign.v1.SignedItem;
import vn.vtokr.org.v1.DonViDaThayDoi;
import vn.vtokr.org.v1.OrgBootstrap;
import vn.vtokr.task.v1.TienDoDaCapNhat;

/** NEN-007 · Lớp sinh tuần tự hoá và đọc lại đúng kiểu logic (decimal, uuid, timestamp, date). */
class AvroRoundTripTest {

  @BeforeAll
  static void trust() {
    EventContracts.trustContractPackages();
    EventContracts.trustContractPackages();
  }

  @Test
  void chiTinCayLopHopDong() {
    assertTrue(ClassSecurityValidator.getGlobal().isTrusted(TienDoDaCapNhat.class));
    assertTrue(EventContracts.CONTRACT_CLASSES.isTrusted(SignedItem.class));
    assertFalse(EventContracts.CONTRACT_CLASSES.isTrusted(EventContracts.class));
  }

  @Test
  void evt05TheoMauTl03() throws IOException {
    TienDoDaCapNhat evt =
        TienDoDaCapNhat.newBuilder()
            .setTaskId(UUID.randomUUID())
            .setPeriodId(UUID.randomUUID())
            .setProgressId(UUID.randomUUID())
            .setReporterId(UUID.randomUUID())
            .setProgressPct(new BigDecimal("75.500"))
            .setReportedAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
            .setAggregateVersion(3)
            .build();

    TienDoDaCapNhat back = roundTrip(evt, TienDoDaCapNhat.class);

    assertEquals(evt, back);
    assertEquals(new BigDecimal("75.500"), back.getProgressPct());
    assertNull(back.getActualValue(), "actualValue tuỳ chọn, mặc định null");
    assertEquals(0, back.getEvidenceCount(), "evidenceCount mặc định 0");
  }

  @Test
  void evt18CoBanGhiLong() throws IOException {
    KySoDaHoanTat evt =
        KySoDaHoanTat.newBuilder()
            .setSignRequestId(UUID.randomUUID())
            .setPurpose("QUYET_DINH")
            .setSourceModule("appraisal")
            .setSourceRefId(UUID.randomUUID())
            .setItems(
                List.of(
                    SignedItem.newBuilder()
                        .setDocumentSha256(ByteBuffer.wrap(new byte[32]))
                        .setSignedObjectKey("t/x/sha256/ab/abcd")
                        .build()))
            .setCertSha256(ByteBuffer.wrap(new byte[32]))
            .setSignedAt(Instant.parse("2027-01-15T09:30:12.512Z"))
            .setAggregateVersion(1)
            .build();

    assertEquals(evt, roundTrip(evt, KySoDaHoanTat.class));
  }

  @Test
  void orgBootstrapBocEvt14() throws IOException {
    DonViDaThayDoi unit =
        DonViDaThayDoi.newBuilder()
            .setUnitId(UUID.randomUUID())
            .setVersionId(UUID.randomUUID())
            .setSourceVersion(42L)
            .setCode("DV001")
            .setName("Văn phòng Thành uỷ")
            .setUnitType("CO_QUAN")
            .setLevel("TINH")
            .setFlows(List.of("DANG"))
            .setValidFrom(LocalDate.of(2026, 1, 1))
            .setAggregateVersion(5)
            .build();
    OrgBootstrap evt =
        OrgBootstrap.newBuilder().setBatchId(UUID.randomUUID()).setSeq(1L).setEntity(unit).build();

    OrgBootstrap back = roundTrip(evt, OrgBootstrap.class);

    assertEquals(evt, back);
    assertEquals(unit, back.getEntity());
    assertNull(unit.getValidTo(), "validTo null nghĩa là vô thời hạn");
  }

  private static <T extends SpecificRecord> T roundTrip(T value, Class<T> type) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
    new SpecificDatumWriter<T>(type).write(value, enc);
    enc.flush();
    BinaryDecoder dec = DecoderFactory.get().binaryDecoder(out.toByteArray(), null);
    return new SpecificDatumReader<T>(type).read(null, dec);
  }
}
