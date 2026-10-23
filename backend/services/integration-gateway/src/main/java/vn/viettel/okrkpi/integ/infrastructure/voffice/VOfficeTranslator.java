package vn.viettel.okrkpi.integ.infrastructure.voffice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.application.TranslationRejectedException;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.domain.EntityType;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.vtokr.integ.v1.VanBanChiDaoDaNhan;

/**
 * Translator văn bản chỉ đạo vOffice sang EVT-24 {@code integ.document.received.v1} (mô hình chuẩn
 * nhóm 5, TL-01 "Lớp chống ô nhiễm"). Mã văn bản nội bộ ổn định qua {@code mapping_xref} {@code
 * VAN_BAN} theo mã vOffice, phiên bản vOffice là {@code aggregateVersion}. Đơn vị nhận phải đã ánh
 * xạ ({@code DON_VI}); chưa ánh xạ thì từ chối không thử lại để quản trị tenant xử lý (tiêu chí
 * DV-13-03). Tệp đính kèm chưa chuyển sang evidence-service nên {@code objectKey} để trống.
 */
public class VOfficeTranslator implements Translator {

  public static final String TOPIC = "integ.document.received.v1";
  public static final String AGGREGATE = "VAN_BAN";
  public static final String UNMAPPED_UNIT = "DON_VI_CHUA_ANH_XA";
  public static final String MISSING_UNIT = "THIEU_DON_VI_NHAN";

  private final ObjectMapper json;

  VOfficeTranslator(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public List<CanonicalEvent> translate(TranslationContext ctx, InboundMessage message) {
    String externalId = message.externalMsgId();
    int sep = externalId.lastIndexOf(':');
    String id = sep > 0 ? externalId.substring(0, sep) : externalId;
    int version = sep > 0 ? Integer.parseInt(externalId.substring(sep + 1)) : 1;
    Directive d =
        VOfficeConnector.TYPE_EDXML.equals(message.messageType())
            ? EdxmlParser.parse(message.raw())
            : fromJson(message.raw());
    if (d.recipientUnitId() == null || d.recipientUnitId().isBlank()) {
      throw new TranslationRejectedException(
          MISSING_UNIT, "Văn bản " + id + " không ghi đơn vị nhận");
    }
    if (ctx.mappings()
        .resolve(
            ctx.tenant(), ctx.connector().id(), EntityType.DON_VI, d.recipientUnitId(), ctx.today())
        .isEmpty()) {
      throw new TranslationRejectedException(
          UNMAPPED_UNIT, "Đơn vị nhận " + d.recipientUnitId() + " chưa được ánh xạ");
    }
    UUID documentId = ctx.internalId(EntityType.VAN_BAN, id);
    VanBanChiDaoDaNhan payload =
        VanBanChiDaoDaNhan.newBuilder()
            .setDocumentId(documentId)
            .setConnectorCode(ctx.connector().code())
            .setExternalId(id)
            .setDocNumber(d.docNumber())
            .setIssuedDate(d.issuedDate())
            .setIssuer(d.issuer())
            .setSummary(d.summary())
            .setObjectKey(null)
            .setAggregateVersion(version)
            .build();
    return List.of(
        new CanonicalEvent(TOPIC, AGGREGATE, documentId, version, payload, Classification.C1));
  }

  private Directive fromJson(byte[] raw) {
    JsonNode n;
    try {
      n = json.readTree(raw);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String docNumber = required(n, "docNumber");
    String issuer = required(n, "issuer");
    String summary = required(n, "summary");
    LocalDate issued;
    try {
      issued = LocalDate.parse(required(n, "issuedDate"));
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Ngày ban hành vOffice không hợp lệ", e);
    }
    String unit = n.path("recipientUnitId").asText(null);
    return new Directive(docNumber, issued, issuer, summary, unit);
  }

  private static String required(JsonNode n, String field) {
    String v = n.path(field).asText("");
    if (v.isBlank()) {
      throw new IllegalArgumentException("Văn bản vOffice thiếu trường " + field);
    }
    return v;
  }
}
