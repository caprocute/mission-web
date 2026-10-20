package vn.viettel.okrkpi.shared.crypto;

import jakarta.persistence.AttributeConverter;
import java.util.List;
import vn.viettel.okrkpi.shared.crypto.EncryptionContextHolder.RowEncryptionContext;

/**
 * Lớp cơ sở cho mọi JPA {@link AttributeConverter} mã hoá trường {@code @Classified(C2)} sang cột
 * {@code <cột>_enc bytea} theo chuẩn phong bì TL-01/TL-03 (NEN-015).
 *
 * <p>Lớp con có thể ghi đè {@link #purpose()} và {@link #columnCoordinate()} cho từng cột cụ thể
 * (ví dụ {@code "appraisal.self_review.comment_enc"}), hoặc dùng {@link C2StringAttributeConverter}
 * kết hợp {@link EncryptionContextHolder}.
 */
public abstract class AbstractC2AttributeConverter implements AttributeConverter<String, byte[]> {

  private final DekPurpose converterPurpose;
  private final String converterColumn;

  protected AbstractC2AttributeConverter() {
    this(null, null);
  }

  protected AbstractC2AttributeConverter(DekPurpose converterPurpose, String converterColumn) {
    this.converterPurpose = converterPurpose;
    this.converterColumn = converterColumn;
  }

  /** Mục đích DEK của cột này; nếu trả {@code null} thì lấy từ {@link RowEncryptionContext}. */
  protected DekPurpose purpose() {
    return converterPurpose;
  }

  /**
   * Toạ độ cột {@code schema.bảng.cột}; nếu trả {@code null} thì lấy từ {@link
   * RowEncryptionContext}.
   */
  protected String columnCoordinate() {
    return converterColumn;
  }

  @Override
  public byte[] convertToDatabaseColumn(String attribute) {
    if (attribute == null) {
      return null;
    }
    RowEncryptionContext ctx = EncryptionContextHolder.require();
    DekPurpose effectivePurpose = purpose() != null ? purpose() : ctx.defaultPurpose();
    if (effectivePurpose == null) {
      throw new CryptoException("Thiếu DekPurpose khi mã hoá thuộc tính JPA @Classified(C2)");
    }
    List<String> aadParts = EncryptionContextHolder.buildAadParts(columnCoordinate());
    FieldEncryptor encryptor = EncryptionContextHolder.requireFieldEncryptor();
    return encryptor.encrypt(ctx.tenantId(), effectivePurpose, aadParts, attribute);
  }

  @Override
  public String convertToEntityAttribute(byte[] dbData) {
    if (dbData == null) {
      return null;
    }
    RowEncryptionContext ctx = EncryptionContextHolder.require();
    DekPurpose effectivePurpose = purpose() != null ? purpose() : ctx.defaultPurpose();
    List<String> aadParts = EncryptionContextHolder.buildAadParts(columnCoordinate());
    FieldEncryptor encryptor = EncryptionContextHolder.requireFieldEncryptor();
    return encryptor.decryptToString(ctx.tenantId(), effectivePurpose, aadParts, dbData);
  }
}
