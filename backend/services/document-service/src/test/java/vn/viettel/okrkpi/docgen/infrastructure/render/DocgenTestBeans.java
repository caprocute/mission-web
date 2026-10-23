package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.TransientFailure;
import vn.viettel.okrkpi.docgen.application.render.RenderInputPort;
import vn.viettel.okrkpi.docgen.application.render.SealedValueOpener;
import vn.viettel.okrkpi.shared.outbox.OutboxPayloadSerializer;

/**
 * Bean giả lập cho kiểm thử tích hợp của SVC-11, nạp bằng {@code @Import} (lớp thường, không phải
 * {@code @TestConfiguration} lồng, để không bị quét sang kiểm thử khác): cổng MOD-06 đọc từ các
 * bảng tĩnh dưới đây, bộ giải mã C2 giả lập và bộ tuần tự hoá outbox rỗng.
 *
 * <p>Phong bì C2 giả lập là Base64 của {@code AAD|giá trị}: bộ giải mã kiểm AAD đúng trường như
 * {@code FieldEncryptor}, nên kiểm thử phát hiện được trường bị giải mã sai chỗ.
 */
public class DocgenTestBeans {

  static final Map<UUID, List<RenderInputPort.FormRef>> PACKAGES = new ConcurrentHashMap<>();
  static final Map<UUID, byte[]> DATA = new ConcurrentHashMap<>();
  static final Map<UUID, List<RenderInputPort.FormRef>> DRAFTS = new ConcurrentHashMap<>();
  static final Map<UUID, RenderInputPort.PreviewData> PREVIEWS = new ConcurrentHashMap<>();

  /** Biểu mẫu mà cổng MOD-06 giả lập lỗi tạm thời khi lấy dữ liệu (DV-11-04). */
  static final Set<UUID> FAILING = ConcurrentHashMap.newKeySet();

  /** Phong bì C2 giả lập của một giá trị. */
  static String seal(List<String> aad, String value) {
    return Base64.getEncoder()
        .encodeToString((String.join("/", aad) + "|" + value).getBytes(StandardCharsets.UTF_8));
  }

  @Bean
  OutboxPayloadSerializer fakeSerializer() {
    return (topic, payload) -> new OutboxPayloadSerializer.SerializedPayload(new byte[] {1}, 1L, 1);
  }

  @Bean
  SealedValueOpener fakeOpener() {
    return (tenantId, aad, envelope) -> {
      String text = new String(envelope, StandardCharsets.UTF_8);
      String prefix = String.join("/", aad) + "|";
      if (!text.startsWith(prefix)) {
        throw new RejectedDocument("Không giải mã được một trường dữ liệu của biểu mẫu.");
      }
      return text.substring(prefix.length());
    };
  }

  @Bean
  @Primary
  RenderInputPort fakeDossier() {
    return new RenderInputPort() {
      @Override
      public Optional<List<FormRef>> forms(
          UUID tenantId, UUID actor, UUID packageId, List<String> formCodes) {
        return Optional.ofNullable(PACKAGES.get(packageId));
      }

      @Override
      public Optional<FormData> data(UUID tenantId, UUID formInstanceId) {
        if (FAILING.contains(formInstanceId)) {
          throw new TransientFailure("Mô-đun hồ sơ tạm thời không trả lời", null);
        }
        return Optional.ofNullable(DATA.get(formInstanceId))
            .map(d -> new FormData(formInstanceId, null, d));
      }

      @Override
      public Optional<List<FormRef>> draftForms(
          UUID tenantId, UUID actor, String formCode, UUID periodId, List<UUID> profileIds) {
        return Optional.ofNullable(DRAFTS.get(periodId));
      }

      @Override
      public Optional<PreviewData> preview(
          UUID tenantId, UUID actor, String formCode, String sourceModule, UUID sourceRefId) {
        return Optional.ofNullable(PREVIEWS.get(sourceRefId));
      }
    };
  }
}
