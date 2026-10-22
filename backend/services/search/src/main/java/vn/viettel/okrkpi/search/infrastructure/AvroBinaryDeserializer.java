package vn.viettel.okrkpi.search.infrastructure;

import java.io.IOException;
import java.util.Map;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.header.Headers;
import vn.viettel.okrkpi.events.EventContracts;
import vn.viettel.okrkpi.events.EventTopics;
import vn.viettel.okrkpi.shared.inbox.InboxPayloadDeserializer;
import vn.viettel.okrkpi.shared.inbox.PermanentInboxException;
import vn.vtokr.integ.v1.VanBanChiDaoDaNhan;
import vn.vtokr.sys.v1.TenantProvisioned;
import vn.vtokr.task.v1.NhiemVuDaGiao;
import vn.vtokr.task.v1.TrangThaiNhiemVuDoi;

/**
 * Giải mã thân Avro nhị phân bằng lược đồ đọc của {@code vt-events}, cho môi trường không có
 * Apicurio Registry (lược đồ ghi phải cùng phiên bản). Môi trường chuẩn dùng {@code
 * ApicurioPayloadDeserializer} của NEN-012.
 */
public final class AvroBinaryDeserializer implements InboxPayloadDeserializer {

  private final Map<String, Class<? extends SpecificRecord>> types =
      Map.of(
          EventTopics.EVT_04_NHIEM_VU_DA_GIAO, NhiemVuDaGiao.class,
          EventTopics.EVT_06_TRANG_THAI_NHIEM_VU_DOI, TrangThaiNhiemVuDoi.class,
          EventTopics.EVT_24_VAN_BAN_CHI_DAO_DA_NHAN, VanBanChiDaoDaNhan.class,
          EventTopics.SYS_TENANT_PROVISIONED_V1, TenantProvisioned.class);

  public AvroBinaryDeserializer() {
    EventContracts.trustContractPackages();
  }

  @Override
  public SpecificRecord deserialize(String topic, Headers headers, byte[] data) {
    Class<? extends SpecificRecord> type = types.get(topic);
    if (type == null || data == null || data.length == 0) {
      throw new PermanentInboxException(
          PermanentInboxException.DESERIALIZATION_ERROR_CODE,
          "Không có lược đồ cho topic " + topic);
    }
    try {
      return new SpecificDatumReader<>(type)
          .read(null, DecoderFactory.get().binaryDecoder(data, null));
    } catch (IOException | RuntimeException e) {
      throw new PermanentInboxException(
          PermanentInboxException.DESERIALIZATION_ERROR_CODE,
          "Không giải mã được thân Avro trên topic " + topic,
          e);
    }
  }
}
