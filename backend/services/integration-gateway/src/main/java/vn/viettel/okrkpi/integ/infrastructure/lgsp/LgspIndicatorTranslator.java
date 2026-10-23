package vn.viettel.okrkpi.integ.infrastructure.lgsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import vn.viettel.okrkpi.integ.application.CanonicalEvent;
import vn.viettel.okrkpi.integ.application.TranslationContext;
import vn.viettel.okrkpi.integ.application.TranslationRejectedException;
import vn.viettel.okrkpi.integ.application.Translator;
import vn.viettel.okrkpi.integ.application.port.KtxhValues;
import vn.viettel.okrkpi.integ.domain.InboundMessage;
import vn.viettel.okrkpi.shared.kernel.Ids;

/**
 * Translator chỉ tiêu KT-XH (DV-13-06, BR-16-05): kiểm cấu trúc rồi ghi {@code integ.ktxh_value}
 * theo kỳ và địa bàn. Cờ {@code da_cong_bo} chỉ bật khi LGSP báo {@code daCongBo=true}; số liệu
 * chưa công bố vẫn lưu nhưng không có thời điểm công bố nên không được tính. Không phát sự kiện:
 * kho phân tích đọc bảng qua CDC, MOD-01 đọc giá trị đã công bố qua cổng số liệu nguồn của KR.
 */
class LgspIndicatorTranslator implements Translator {

  static final String BAD_STRUCTURE = "CHI_TIEU_SAI_CAU_TRUC";
  private static final Pattern CODE = Pattern.compile("[\\p{L}\\p{N}._\\-/]{1,64}");
  private static final Pattern PERIOD = Pattern.compile("[0-9A-Z\\-]{4,16}");

  private final KtxhValues values;
  private final ObjectMapper json;
  private final Clock clock;

  LgspIndicatorTranslator(KtxhValues values, ObjectMapper json, Clock clock) {
    this.values = Objects.requireNonNull(values, "values");
    this.json = Objects.requireNonNull(json, "json");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public List<CanonicalEvent> translate(TranslationContext ctx, InboundMessage message) {
    JsonNode item;
    try {
      item = json.readTree(message.raw());
    } catch (IOException e) {
      throw reject("Chỉ tiêu không phải JSON");
    }
    if (item == null || !item.isObject()) {
      throw reject("Chỉ tiêu không phải đối tượng");
    }
    String indicator = text(item, "maChiTieu");
    String period = text(item, "kyBaoCao");
    String area = text(item, "maDiaBan");
    if (indicator == null || !CODE.matcher(indicator).matches()) {
      throw reject("Thiếu hoặc sai mã chỉ tiêu");
    }
    if (period == null || !PERIOD.matcher(period).matches()) {
      throw reject("Thiếu hoặc sai kỳ báo cáo");
    }
    if (area == null || !CODE.matcher(area).matches()) {
      throw reject("Thiếu hoặc sai mã địa bàn");
    }
    JsonNode raw = item.path("giaTri");
    BigDecimal value;
    try {
      value = raw.isNumber() ? raw.decimalValue() : new BigDecimal(raw.asText(""));
    } catch (NumberFormatException e) {
      throw reject("Giá trị chỉ tiêu không phải số");
    }
    if (value.abs().compareTo(new BigDecimal("1e14")) >= 0) {
      throw reject("Giá trị chỉ tiêu vượt miền cho phép");
    }
    Instant updated = LgspIndicatorConnector.instant(text(item, "capNhatLuc"));
    Instant published = null;
    if (item.path("daCongBo").asBoolean(false)) {
      published = LgspIndicatorConnector.instant(text(item, "thoiDiemCongBo"));
      if (published == null) {
        throw reject("Chỉ tiêu đã công bố nhưng thiếu thời điểm công bố");
      }
    }
    String unit = text(item, "donViTinh");
    values.upsert(
        ctx.tenant(),
        Ids.newUuidV7(),
        ctx.connector().id(),
        new KtxhValues.Value(
            indicator,
            period,
            area,
            value,
            unit == null ? null : unit.substring(0, Math.min(unit.length(), 32)),
            published,
            updated),
        clock.instant());
    return List.of();
  }

  private static String text(JsonNode item, String field) {
    JsonNode n = item.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    String s = n.asText("").trim();
    return s.isEmpty() ? null : s;
  }

  private static TranslationRejectedException reject(String message) {
    return new TranslationRejectedException(BAD_STRUCTURE, message);
  }
}
