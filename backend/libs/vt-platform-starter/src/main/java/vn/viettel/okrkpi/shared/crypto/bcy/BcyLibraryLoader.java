package vn.viettel.okrkpi.shared.crypto.bcy;

import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Nạp và mở thư viện Ban Cơ yếu theo cấu hình (DV-10-05). Mọi lỗi đều thành {@link
 * BcyUnavailableException} với lý do tiếng Việt; không bao giờ lùi về mật mã tiêu chuẩn.
 */
public final class BcyLibraryLoader {

  private BcyLibraryLoader() {}

  public static BcyCryptoLibrary load(BcyCryptoProperties properties) {
    String className = properties.libraryClass();
    if (className == null || className.isBlank()) {
      throw new BcyUnavailableException(
          "Chế độ mật mã bcy: chưa khai lớp thư viện Ban Cơ yếu (okrkpi.crypto.bcy.library-class)");
    }
    if (properties.allowedAlgorithms().isEmpty()) {
      throw new BcyUnavailableException(
          "Chế độ mật mã bcy: chưa khai danh sách thuật toán được phép"
              + " (okrkpi.crypto.bcy.allowed-algorithms)");
    }
    ClassLoader loader = classLoader(properties.libraryPath());
    BcyCryptoLibrary library = instantiate(loader, className.trim());
    try {
      library.open(properties.params());
    } catch (RuntimeException e) {
      closeQuietly(library);
      throw new BcyUnavailableException(
          "Chế độ mật mã bcy: không mở được thiết bị mật mã Ban Cơ yếu ("
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage()
              + ")",
          e);
    }
    try {
      checkAlgorithms(library.algorithms(), properties.allowedAlgorithms());
    } catch (BcyUnavailableException e) {
      closeQuietly(library);
      throw e;
    }
    return library;
  }

  static void checkAlgorithms(
      Map<BcyCryptoLibrary.Role, String> declared, Set<String> allowedAlgorithms) {
    Set<BcyCryptoLibrary.Role> missing = new TreeSet<>(BcyCryptoLibrary.REQUIRED_ROLES);
    missing.removeAll(declared.keySet());
    if (!missing.isEmpty()) {
      throw new BcyUnavailableException(
          "Chế độ mật mã bcy: thư viện không khai thuật toán cho vai trò " + missing);
    }
    Set<String> outside = new TreeSet<>();
    declared.values().stream().filter(a -> !allowedAlgorithms.contains(a)).forEach(outside::add);
    if (!outside.isEmpty()) {
      throw new BcyUnavailableException(
          "Chế độ mật mã bcy: thuật toán " + outside + " không thuộc danh sách được phép");
    }
  }

  private static ClassLoader classLoader(List<Path> paths) {
    ClassLoader parent = BcyLibraryLoader.class.getClassLoader();
    if (paths.isEmpty()) {
      return parent;
    }
    List<URL> urls = new ArrayList<>();
    for (Path path : paths) {
      if (!Files.isReadable(path)) {
        throw new BcyUnavailableException(
            "Chế độ mật mã bcy: không tìm thấy thư viện Ban Cơ yếu tại " + path);
      }
      try {
        urls.add(path.toUri().toURL());
      } catch (MalformedURLException e) {
        throw new BcyUnavailableException(
            "Chế độ mật mã bcy: đường dẫn thư viện không hợp lệ " + path, e);
      }
    }
    return new URLClassLoader("bcy-crypto", urls.toArray(URL[]::new), parent);
  }

  private static BcyCryptoLibrary instantiate(ClassLoader loader, String className) {
    Class<?> type;
    try {
      type = Class.forName(className, true, loader);
    } catch (ClassNotFoundException | LinkageError e) {
      throw new BcyUnavailableException(
          "Chế độ mật mã bcy: không nạp được lớp thư viện Ban Cơ yếu " + className, e);
    }
    if (!BcyCryptoLibrary.class.isAssignableFrom(type)) {
      throw new BcyUnavailableException(
          "Chế độ mật mã bcy: lớp "
              + className
              + " không hiện thực "
              + BcyCryptoLibrary.class.getName());
    }
    try {
      return (BcyCryptoLibrary) type.getDeclaredConstructor().newInstance();
    } catch (InvocationTargetException e) {
      throw new BcyUnavailableException(
          "Chế độ mật mã bcy: khởi tạo thư viện Ban Cơ yếu lỗi", e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new BcyUnavailableException(
          "Chế độ mật mã bcy: thư viện " + className + " cần hàm dựng không tham số", e);
    }
  }

  private static void closeQuietly(BcyCryptoLibrary library) {
    try {
      library.close();
    } catch (RuntimeException ignored) {
      // Đang báo lỗi gốc; lỗi đóng phiên không thay lý do.
    }
  }
}
