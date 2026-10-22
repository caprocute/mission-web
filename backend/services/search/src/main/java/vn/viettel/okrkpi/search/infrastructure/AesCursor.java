package vn.viettel.okrkpi.search.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import vn.viettel.okrkpi.search.application.CursorPort;

/**
 * Con trỏ trang mã hoá AES-256-GCM: thân là giá trị {@code search_after}, dữ liệu kèm (AAD) là
 * chuỗi gắn người gọi và truy vấn, nên con trỏ không đọc được, không sửa được và không dùng được
 * cho người hay truy vấn khác. Khoá lấy từ {@code SEARCH_CURSOR_KEY} (Base64, 32 byte, cấp từ
 * OpenBao); thiếu khoá thì sinh khoá ngẫu nhiên lúc khởi động, khi đó con trỏ chỉ dùng được trên
 * cùng pod (chỉ hợp cho môi trường dev).
 */
public final class AesCursor implements CursorPort {

  private static final TypeReference<List<Object>> LIST = new TypeReference<>() {};
  private final SecretKey key;
  private final ObjectMapper json;
  private final SecureRandom random = new SecureRandom();

  public AesCursor(String base64Key, ObjectMapper json) {
    this.json = json;
    this.key =
        base64Key == null || base64Key.isBlank()
            ? generate()
            : new SecretKeySpec(Base64.getDecoder().decode(base64Key.strip()), "AES");
  }

  private static SecretKey generate() {
    try {
      KeyGenerator g = KeyGenerator.getInstance("AES");
      g.init(256);
      return g.generateKey();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String seal(String binding, List<Object> sort) {
    try {
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      c.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
      byte[] ct = c.doFinal(json.writeValueAsBytes(sort));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array());
    } catch (GeneralSecurityException | com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Không mã hoá được con trỏ", e);
    }
  }

  @Override
  public Optional<List<Object>> open(String binding, String cursor) {
    try {
      byte[] raw = Base64.getUrlDecoder().decode(cursor);
      if (raw.length < 12 + 16) {
        return Optional.empty();
      }
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, raw, 0, 12));
      c.updateAAD(binding.getBytes(StandardCharsets.UTF_8));
      byte[] plain = c.doFinal(raw, 12, raw.length - 12);
      return Optional.of(json.readValue(plain, LIST));
    } catch (IllegalArgumentException | GeneralSecurityException | java.io.IOException e) {
      return Optional.empty();
    }
  }
}
