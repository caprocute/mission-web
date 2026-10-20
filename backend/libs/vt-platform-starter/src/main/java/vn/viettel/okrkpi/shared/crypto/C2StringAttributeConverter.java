package vn.viettel.okrkpi.shared.crypto;

import jakarta.persistence.Converter;

/**
 * {@link jakarta.persistence.AttributeConverter} mặc định cho thuộc tính chuỗi
 * {@code @Classified(C2)}, dùng kèm {@code @Convert(converter = C2StringAttributeConverter.class)}
 * và {@link EncryptionContextHolder} (NEN-015).
 */
@Converter
public class C2StringAttributeConverter extends AbstractC2AttributeConverter {

  public C2StringAttributeConverter() {
    super();
  }
}
