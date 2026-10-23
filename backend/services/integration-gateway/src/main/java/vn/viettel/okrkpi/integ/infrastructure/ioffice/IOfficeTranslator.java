package vn.viettel.okrkpi.integ.infrastructure.ioffice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.application.TranslationRejectedException;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.integ.infrastructure.edxml.Edxml;
import vn.viettel.okrkpi.integ.infrastructure.edxml.EdxmlDocument;
import vn.viettel.okrkpi.integ.infrastructure.voffice.Directive;
import vn.viettel.okrkpi.integ.infrastructure.voffice.DirectiveEvents;

/**
 * Translator văn bản đến của iOffice sang EVT-24 qua bước phát chung {@link DirectiveEvents} của
 * DV-13-03, nên văn bản iOffice và vOffice tương đương cho cùng cấu trúc Avro. Ánh xạ trường:
 * {@code soKyHieu} → {@code docNumber}, {@code ngayBanHanh} ({@code yyyy-MM-dd} hoặc {@code
 * dd/MM/yyyy}) → {@code issuedDate}, {@code coQuanBanHanh} → {@code issuer}, {@code trichYeu} →
 * {@code summary}, nơi nhận đầu tiên đã ánh xạ trong {@code noiNhan[].maDonVi} → đơn vị nhận. Bỏ
 * qua {@code doKhan}, {@code nguoiKy}, {@code tepDinhKem}, {@code soDen}, {@code ngayDen}.
 *
 * <p>Văn bản có {@code doMat} khác {@code THUONG} (mật, tối mật, tuyệt mật) bị từ chối không thử
 * lại: văn bản mật không đi qua kênh tích hợp (suy luận, wiki trang 14).
 */
public class IOfficeTranslator implements Translator {

  public static final String SECRET_DOCUMENT = "VAN_BAN_MAT";
  static final Set<String> OPEN = Set.of("", "THUONG", "BINH_THUONG");
  static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  static final int MAX_EDXML_BYTES = 2 * 1024 * 1024;

  private final ObjectMapper json;

  IOfficeTranslator(ObjectMapper json) {
    this.json = Objects.requireNonNull(json, "json");
  }

  @Override
  public List<CanonicalEvent> translate(TranslationContext ctx, InboundMessage message) {
    String externalId = message.externalMsgId();
    int sep = externalId.lastIndexOf(':');
    String id = sep > 0 ? externalId.substring(0, sep) : externalId;
    int version = sep > 0 ? Integer.parseInt(externalId.substring(sep + 1)) : 1;
    Directive d =
        IOfficeConnector.TYPE_EDXML.equals(message.messageType())
            ? fromEdxml(ctx, message.raw())
            : fromJson(ctx, id, message.raw());
    return DirectiveEvents.emit(ctx, id, version, d);
  }

  private static Directive fromEdxml(TranslationContext ctx, byte[] raw) {
    EdxmlDocument d = Edxml.parse(raw, MAX_EDXML_BYTES);
    String unit = d.firstRecipient();
    for (EdxmlDocument.Organ to : d.to()) {
      if (DirectiveEvents.mapped(ctx, to.id())) {
        unit = to.id();
        break;
      }
    }
    return new Directive(d.docNumber(), d.promulgationDate(), d.from().name(), d.subject(), unit);
  }

  private Directive fromJson(TranslationContext ctx, String id, byte[] raw) {
    JsonNode n;
    try {
      n = json.readTree(raw);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String secrecy = n.path("doMat").asText("").trim().toUpperCase(java.util.Locale.ROOT);
    if (!OPEN.contains(secrecy)) {
      throw new TranslationRejectedException(
          SECRET_DOCUMENT, "Văn bản " + id + " có độ mật, không nhận qua kết nối");
    }
    return new Directive(
        required(n, "soKyHieu"),
        date(required(n, "ngayBanHanh")),
        required(n, "coQuanBanHanh"),
        required(n, "trichYeu"),
        recipient(ctx, n.path("noiNhan")));
  }

  /** Nơi nhận đầu tiên đã ánh xạ; không có thì nơi nhận đầu tiên (bị từ chối vì chưa ánh xạ). */
  private static String recipient(TranslationContext ctx, JsonNode list) {
    String first = null;
    for (JsonNode r : list) {
      String unit = r.path("maDonVi").asText("");
      if (unit.isBlank()) {
        continue;
      }
      if (first == null) {
        first = unit;
      }
      if (DirectiveEvents.mapped(ctx, unit)) {
        return unit;
      }
    }
    return first;
  }

  static LocalDate date(String v) {
    try {
      return v.contains("/") ? LocalDate.parse(v, VN_DATE) : LocalDate.parse(v);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Ngày ban hành iOffice không hợp lệ", e);
    }
  }

  private static String required(JsonNode n, String field) {
    String v = n.path(field).asText("");
    if (v.isBlank()) {
      throw new IllegalArgumentException("Văn bản iOffice thiếu trường " + field);
    }
    return v;
  }
}
