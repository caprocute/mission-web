package vn.viettel.okrkpi.dossier.application.dossierpackage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import vn.viettel.okrkpi.dossier.application.dossierpackage.port.DossierPackageRepository.FormRow;

/**
 * Manifest của gói (TL-03 {@code dossier_package.manifest}, QT-11 bước 12): mảng các tệp đã sinh,
 * sắp theo mã biểu mẫu, mỗi phần tử gồm {@code bieuMauId}, {@code maMau}, {@code hoSoId}, {@code
 * khoaDoiTuong}, {@code sha256} (hex chữ thường). Bản chuẩn hoá theo JCS (RFC 8785): khoá sắp theo
 * thứ tự, không khoảng trắng; mọi giá trị là chuỗi ASCII nên sắp theo đơn vị UTF-16 trùng sắp theo
 * byte. {@code manifest_hash} là SHA-256 của bản chuẩn hoá (suy luận, wiki trang 14).
 */
public final class PackageManifest {

  private static final ObjectMapper CANONICAL =
      new ObjectMapper()
          .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
          .configure(SerializationFeature.INDENT_OUTPUT, false);

  private PackageManifest() {}

  /** Manifest đã chuẩn hoá và băm. */
  public record Built(String canonicalJson, byte[] sha256) {}

  public static Built of(List<FormRow> forms) {
    List<Map<String, String>> entries = new ArrayList<>();
    forms.stream()
        .sorted(Comparator.comparing((FormRow f) -> f.id().toString()))
        .forEach(
            f -> {
              Map<String, String> e = new TreeMap<>();
              e.put("bieuMauId", f.id().toString());
              e.put("maMau", f.formCode());
              e.put("hoSoId", f.profileId().toString());
              e.put("khoaDoiTuong", f.objectKey());
              e.put("sha256", HexFormat.of().formatHex(f.pdfSha256()));
              entries.add(e);
            });
    try {
      byte[] bytes = CANONICAL.writeValueAsBytes(entries);
      return new Built(
          new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
          MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Không lập được manifest gói hồ sơ", e);
    }
  }

  /** Băm hex theo mã biểu mẫu của manifest đã chốt; manifest hỏng hoặc rỗng thì bảng rỗng. */
  public static Map<UUID, String> hashes(String manifestJson) {
    Map<UUID, String> out = new LinkedHashMap<>();
    if (manifestJson == null || manifestJson.isBlank()) {
      return out;
    }
    try {
      List<Map<String, String>> entries =
          CANONICAL.readValue(manifestJson, new TypeReference<List<Map<String, String>>>() {});
      for (Map<String, String> e : entries) {
        out.put(UUID.fromString(e.get("bieuMauId")), e.get("sha256"));
      }
    } catch (JsonProcessingException | RuntimeException e) {
      out.clear();
    }
    return out;
  }
}
