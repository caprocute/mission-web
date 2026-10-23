package vn.viettel.okrkpi.dossier.application.dossierpackage.port;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import vn.viettel.okrkpi.shared.kernel.TenantId;

/**
 * Tệp của gói hồ sơ trên kho đối tượng của SVC-08 (Ceph RGW) cho xuất ZIP và gửi gói con
 * (CORE-06-08): đọc tệp theo luồng, ghi tệp theo luồng (đóng luồng là hoàn tất tệp; lỗi giữa chừng
 * thì huỷ, không để lại tệp dở) và URL tải ký có hạn. Không phương thức nào nạp cả tệp vào bộ nhớ.
 */
public interface PackageFiles {

  /** Kho đối tượng chưa cấu hình. */
  final class UnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnavailableException() {
      super("Kho lưu trữ hồ sơ chưa sẵn sàng.");
    }
  }

  /** Luồng đọc tệp; tệp không có thì {@link java.io.FileNotFoundException}. */
  InputStream open(TenantId tenant, String objectKey) throws IOException;

  /** Kích thước tệp, -1 khi không biết. */
  long size(TenantId tenant, String objectKey) throws IOException;

  /** Luồng ghi tệp mới; {@code close()} hoàn tất tệp. */
  WritableFile create(TenantId tenant, String objectKey, String contentType) throws IOException;

  /** URL tải ký có hạn, gợi ý tên tệp khi lưu. */
  String downloadUrl(TenantId tenant, String objectKey, String fileName, Duration ttl);

  /** Luồng ghi có thể huỷ: {@link #abort()} bỏ phần đã ghi. */
  abstract class WritableFile extends OutputStream {

    /** Bỏ tệp đang ghi; gọi sau {@code close()} thì không có tác dụng. */
    public abstract void abort();
  }

  /** Không có kho đối tượng: mọi thao tác ném {@link UnavailableException}. */
  PackageFiles NONE =
      new PackageFiles() {
        @Override
        public InputStream open(TenantId tenant, String objectKey) {
          throw new UnavailableException();
        }

        @Override
        public long size(TenantId tenant, String objectKey) {
          throw new UnavailableException();
        }

        @Override
        public WritableFile create(TenantId tenant, String objectKey, String contentType) {
          throw new UnavailableException();
        }

        @Override
        public String downloadUrl(
            TenantId tenant, String objectKey, String fileName, Duration ttl) {
          throw new UnavailableException();
        }
      };
}
