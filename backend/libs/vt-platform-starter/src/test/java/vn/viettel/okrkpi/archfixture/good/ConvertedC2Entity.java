package vn.viettel.okrkpi.archfixture.good;

import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;
import vn.viettel.okrkpi.shared.crypto.C2StringAttributeConverter;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Classified;

/** Thực thể mẫu tuân thủ: thuộc tính C2 có @Convert mã hoá và toString() đã che dữ liệu. */
@Entity
public class ConvertedC2Entity {

  @Id private UUID id;

  @Classified(Classification.C2)
  @Convert(converter = C2StringAttributeConverter.class)
  private String nhanXet;

  public ConvertedC2Entity(UUID id, String nhanXet) {
    this.id = id;
    this.nhanXet = nhanXet;
  }

  public UUID getId() {
    return id;
  }

  public String getNhanXet() {
    return nhanXet;
  }

  @Override
  public String toString() {
    return "ConvertedC2Entity[id=" + id + ", nhanXet=[REDACTED]]";
  }
}
