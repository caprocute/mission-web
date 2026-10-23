package vn.viettel.okrkpi.dossier.application.dossierpackage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.Pack;
import vn.viettel.okrkpi.appraisal.api.DossierProfilesApi.UnitProfile;

/**
 * Tài liệu JSON chuẩn tắc của một biểu mẫu (dữ liệu đã quyết định, BR-11-02) mà SVC-11 trộn vào mẫu
 * và băm SHA-256 của nó ({@code form_instance.data_snapshot_hash}). Khoá xếp theo thứ tự từ điển,
 * không khoảng trắng, điểm ghi dạng chuỗi số thập phân nên cùng dữ liệu luôn cho cùng băm; dữ liệu
 * đổi (quyết định lại) thì băm đổi và SVC-11 từ chối dữ liệu lệch băm. Trạng thái hồ sơ không có
 * trong tài liệu vì ký số quyết định không đổi nội dung biểu mẫu (suy luận, wiki trang 14).
 */
public final class FormDocuments {

  /** Phiên bản cấu trúc tài liệu, để SVC-11 nhận biết khi cấu trúc đổi. */
  static final String SCHEMA = "ho-so-so.bieu-mau/1";

  private static final ObjectMapper CANONICAL =
      new ObjectMapper()
          .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
          .configure(SerializationFeature.INDENT_OUTPUT, false);

  private FormDocuments() {}

  /** Tài liệu và băm. */
  public record Document(byte[] canonicalJson, byte[] sha256) {}

  /** Biểu mẫu cá nhân (bảng chấm điểm, Mẫu 1B–1D theo Policy Pack) của một hồ sơ. */
  public static Document personal(String formCode, UnitProfile p) {
    Map<String, Object> doc = head(formCode, p.periodId(), p.orgUnitId(), p.pack());
    doc.put("hoSo", profile(p));
    return document(doc);
  }

  /** Biểu mẫu của đơn vị (Mẫu 3, Mẫu 2, Mẫu 1A theo Policy Pack): mọi hồ sơ đã quyết định. */
  public static Document unit(
      String formCode, UUID periodId, UUID orgUnitId, Pack pack, List<UnitProfile> profiles) {
    Map<String, Object> doc = head(formCode, periodId, orgUnitId, pack);
    List<Map<String, Object>> rows = new ArrayList<>();
    profiles.stream()
        .filter(UnitProfile::decided)
        .sorted(Comparator.comparing(UnitProfile::id))
        .forEach(p -> rows.add(profile(p)));
    doc.put("danhSachHoSo", rows);
    return document(doc);
  }

  private static Map<String, Object> head(
      String formCode, UUID periodId, UUID orgUnitId, Pack pack) {
    Map<String, Object> doc = new TreeMap<>();
    doc.put("cauTruc", SCHEMA);
    doc.put("maMau", formCode);
    doc.put("kyId", periodId.toString());
    doc.put("donViId", orgUnitId.toString());
    Map<String, Object> rules = new TreeMap<>();
    rules.put("ma", pack == null ? null : pack.code());
    rules.put("phienBan", pack == null ? null : pack.version());
    doc.put("boQuyTac", rules);
    return doc;
  }

  private static Map<String, Object> profile(UnitProfile p) {
    Map<String, Object> row = new TreeMap<>();
    row.put("hoSoId", p.id().toString());
    row.put("nguoiId", p.personId().toString());
    row.put("luong", p.flow());
    row.put("mucXepLoai", p.ratingCode());
    row.put("tongDiem", decimal(p.score()));
    row.put("anhChupDiemId", p.scoreSnapshotId() == null ? null : p.scoreSnapshotId().toString());
    return row;
  }

  private static String decimal(BigDecimal v) {
    if (v == null) {
      return null;
    }
    BigDecimal s = v.stripTrailingZeros();
    return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
  }

  private static Document document(Map<String, Object> doc) {
    try {
      byte[] bytes = CANONICAL.writeValueAsBytes(doc);
      return new Document(bytes, MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Không lập được dữ liệu biểu mẫu", e);
    }
  }
}
