package vn.viettel.okrkpi.archfixture.bad;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;
import vn.viettel.okrkpi.shared.kernel.Classification;
import vn.viettel.okrkpi.shared.kernel.Classified;

/** Thực thể mẫu vi phạm cả luật thiếu @Convert C2 lẫn luật lộ trường C2 trong toString(). */
@Entity
public class UnconvertedC2Entity {

  @Id private UUID id;

  @Classified(Classification.C2)
  private String nhanXet;

  public UnconvertedC2Entity(UUID id, String nhanXet) {
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
    return "UnconvertedC2Entity[id=" + id + ", nhanXet=" + nhanXet + "]";
  }
}
