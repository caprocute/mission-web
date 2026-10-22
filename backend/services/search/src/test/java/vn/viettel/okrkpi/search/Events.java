package vn.viettel.okrkpi.search;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import vn.viettel.okrkpi.events.EventTopics;
import vn.vtokr.integ.v1.VanBanChiDaoDaNhan;
import vn.vtokr.sys.v1.TenantProvisioned;
import vn.vtokr.task.v1.NhiemVuDaGiao;
import vn.vtokr.task.v1.TrangThaiNhiemVuDoi;

/** Dựng sự kiện Avro và bản ghi Kafka theo phong bì NEN-012 cho kiểm thử. */
final class Events {

  private Events() {}

  static NhiemVuDaGiao assigned(UUID task, String title, int version, boolean secret) {
    return NhiemVuDaGiao.newBuilder()
        .setTaskId(task)
        .setPlanItemId(null)
        .setTitle(title)
        .setLeadAssigneeId(UUID.randomUUID())
        .setAssigneeIds(List.of(UUID.randomUUID()))
        .setDueDate(LocalDate.parse("2026-12-31"))
        .setWeight(null)
        .setIsKeyTask(true)
        .setIsSecret(secret)
        .setAggregateVersion(version)
        .build();
  }

  static TrangThaiNhiemVuDoi status(UUID task, String to, int version) {
    return TrangThaiNhiemVuDoi.newBuilder()
        .setTaskId(task)
        .setFromStatus("DA_GIAO")
        .setToStatus(to)
        .setIsOverdue(false)
        .setReasonCode("R01")
        .setChangedBy(UUID.randomUUID())
        .setAggregateVersion(version)
        .build();
  }

  static VanBanChiDaoDaNhan document(UUID doc, String summary, int version) {
    return VanBanChiDaoDaNhan.newBuilder()
        .setDocumentId(doc)
        .setConnectorCode("VOFFICE")
        .setExternalId("EXT-1")
        .setDocNumber("12/CT-UBND")
        .setIssuedDate(LocalDate.parse("2026-10-05"))
        .setIssuer("Ủy ban nhân dân tỉnh")
        .setSummary(summary)
        .setObjectKey("docs/secret-key.pdf")
        .setAggregateVersion(version)
        .build();
  }

  static TenantProvisioned tenant(String model) {
    return TenantProvisioned.newBuilder()
        .setTenantCode("tct")
        .setCellCode("CELL-HN")
        .setTenancyModel(model)
        .setDatabaseName("td_tct")
        .setProvisionedAt(Instant.now())
        .build();
  }

  static ProducerRecord<String, byte[]> record(String tenant, SpecificRecord event) {
    return record(tenant, event, Instant.now());
  }

  static ProducerRecord<String, byte[]> record(String tenant, SpecificRecord event, Instant at) {
    String topic =
        switch (event) {
          case NhiemVuDaGiao e -> EventTopics.EVT_04_NHIEM_VU_DA_GIAO;
          case TrangThaiNhiemVuDoi e -> EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI;
          case VanBanChiDaoDaNhan e -> EventTopics.EVT_24_VAN_BAN_CHI_DAO_DA_NHAN;
          case TenantProvisioned e -> EventTopics.SYS_TENANT_PROVISIONED_V1;
          default -> throw new IllegalArgumentException(event.getClass().getName());
        };
    var r = new ProducerRecord<String, byte[]>(topic, tenant, encode(event));
    r.headers().add("tenantid", tenant.getBytes(StandardCharsets.UTF_8));
    r.headers().add("occurredat", at.toString().getBytes(StandardCharsets.UTF_8));
    return r;
  }

  @SuppressWarnings("unchecked")
  static byte[] encode(SpecificRecord event) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      BinaryEncoder enc = EncoderFactory.get().binaryEncoder(out, null);
      new SpecificDatumWriter<>((Class<SpecificRecord>) event.getClass()).write(event, enc);
      enc.flush();
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
