package vn.viettel.okrkpi.integ.infrastructure.ioffice;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import vn.viettel.okrkpi.integ.application.InboundConnector;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.domain.Connector;
import vn.viettel.okrkpi.integ.domain.InboundMessage;

/**
 * Bộ kết nối iOffice (VNPT) và hệ văn bản cùng mô hình chiều vào (DV-13-04): kéo văn bản đến theo
 * con trỏ thời điểm cập nhật của stream {@code van-ban-den}. Mỗi văn bản thành một thông điệp mã
 * {@code <vanBanId>:<phienBan>}, nên văn bản đổi phiên bản được xử lý lại còn bản trùng thì không.
 * Phần tử có trường {@code goiEdxml} (base64) được lưu nguyên gói EdXML (NĐ 30/2020).
 */
public class IOfficeConnector implements InboundConnector {

  public static final String STREAM = "van-ban-den";
  public static final String TYPE_JSON = "IOFFICE_JSON";
  public static final String TYPE_EDXML = "EDXML";
  static final int PAGE = 200;
  static final String EPOCH = "1970-01-01T00:00:00Z";

  private final String code;
  private final IOfficeClient client;
  private final Duration period;
  private final Translator translator;

  IOfficeConnector(
      String code, IOfficeClient client, Duration period, IOfficeTranslator translator) {
    this.code = Objects.requireNonNull(code, "code");
    this.client = Objects.requireNonNull(client, "client");
    this.period = Objects.requireNonNull(period, "period");
    this.translator = Objects.requireNonNull(translator, "translator");
  }

  @Override
  public String code() {
    return code;
  }

  @Override
  public String stream() {
    return STREAM;
  }

  @Override
  public String initialCursor() {
    return EPOCH;
  }

  @Override
  public Duration period() {
    return period;
  }

  @Override
  public Page fetch(Connector connector, String cursor) throws IOException {
    JsonNode body = client.incoming(connector, cursor, PAGE);
    List<InboundMessage> items = new ArrayList<>();
    Instant newest = null;
    for (JsonNode item : body.path("duLieu")) {
      String id = item.path("vanBanId").asText("");
      long version = item.path("phienBan").asLong(1);
      String externalId = id + ":" + version;
      if (id.isBlank() || externalId.length() > 128) {
        throw new IOException("Văn bản iOffice thiếu mã hoặc mã quá dài");
      }
      String edxml = item.path("goiEdxml").asText("");
      if (!edxml.isBlank()) {
        byte[] pkg;
        try {
          pkg = Base64.getMimeDecoder().decode(edxml);
        } catch (IllegalArgumentException e) {
          throw new IOException("Gói EdXML của văn bản iOffice không giải mã được", e);
        }
        if (pkg.length == 0) {
          throw new IOException("Gói EdXML của văn bản iOffice rỗng");
        }
        items.add(new InboundMessage(externalId, TYPE_EDXML, pkg));
      } else {
        items.add(new InboundMessage(externalId, TYPE_JSON, client.json().writeValueAsBytes(item)));
      }
      Instant updated = instant(item.path("thoiDiemCapNhat").asText(null));
      if (updated != null && (newest == null || updated.isAfter(newest))) {
        newest = updated;
      }
    }
    String next = body.path("thoiDiemTiepTheo").asText(null);
    if ((next == null || next.isBlank()) && newest != null) {
      next = newest.toString();
    }
    return new Page(items, next == null || next.isBlank() ? cursor : next, newest);
  }

  @Override
  public Translator translator() {
    return translator;
  }

  /** iOffice ghi thời điểm có độ lệch múi giờ (ví dụ {@code +07:00}). */
  static Instant instant(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(s).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
