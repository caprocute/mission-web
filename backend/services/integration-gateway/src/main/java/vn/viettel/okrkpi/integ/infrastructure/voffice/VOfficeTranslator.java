package vn.viettel.okrkpi.integ.infrastructure.voffice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.domain.InboundMessage;

/**
 * Translator văn bản chỉ đạo vOffice sang EVT-24 {@code integ.document.received.v1} (mô hình chuẩn
 * nhóm 5, TL-01 "Lớp chống ô nhiễm"). Mã văn bản nội bộ ổn định qua {@code mapping_xref} {@code
 * VAN_BAN} theo mã vOffice, phiên bản vOffice là {@code aggregateVersion}. Đơn vị nhận phải đã ánh
 * xạ ({@code DON_VI}); chưa ánh xạ thì từ chối không thử lại để quản trị tenant xử lý (tiêu chí
 * DV-13-03). Tệp đính kèm chưa chuyển sang evidence-service nên {@code objectKey} để trống.
 */
public class VOfficeTranslator implements Translator {

  public static final String TOPIC = DirectiveEvents.TOPIC;
  public static final String AGGREGATE = DirectiveEvents.AGGREGATE;
  public static final String UNMAPPED_UNIT = DirectiveEvents.UNMAPPED_UNIT;
  public static final String MISSING_UNIT = DirectiveEvents.MISSING_UNIT;

  private final ObjectMapper json;

  public VOfficeTranslator(ObjectMapper json) {
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
    return DirectiveEvents.emit(ctx, id, version, d);
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
