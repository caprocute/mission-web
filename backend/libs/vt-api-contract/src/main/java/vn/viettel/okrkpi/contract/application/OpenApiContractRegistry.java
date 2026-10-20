package vn.viettel.okrkpi.contract.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import vn.viettel.okrkpi.contract.domain.ContractModule;

/**
 * Dịch vụ tra cứu giao diện Spring MVC đã sinh và định vị tệp hợp đồng OpenAPI 3.1 (ADR-32,
 * NEN-006).
 */
public final class OpenApiContractRegistry {

  private OpenApiContractRegistry() {}

  /**
   * Nạp toàn bộ giao diện API đã sinh của một mô-đun hợp đồng từ classpath.
   *
   * @param module mô-đun hợp đồng cần nạp
   * @return danh sách lớp giao diện {@code *Api}
   */
  public static List<Class<?>> loadApiInterfaces(ContractModule module) {
    Objects.requireNonNull(module, "module must not be null");
    List<Class<?>> classes = new ArrayList<>(module.apiInterfaceNames().size());
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    if (classLoader == null) {
      classLoader = OpenApiContractRegistry.class.getClassLoader();
    }
    for (String fqcn : module.fullyQualifiedApiInterfaces()) {
      try {
        Class<?> apiClass = Class.forName(fqcn, false, classLoader);
        if (!apiClass.isInterface()) {
          throw new IllegalStateException("Lớp hợp đồng không phải interface: " + fqcn);
        }
        classes.add(apiClass);
      } catch (ClassNotFoundException ex) {
        throw new IllegalStateException("Không tìm thấy giao diện hợp đồng đã sinh: " + fqcn, ex);
      }
    }
    return List.copyOf(classes);
  }

  /**
   * Nạp toàn bộ giao diện API của tất cả 11 mô-đun hợp đồng.
   *
   * @return danh sách 24 lớp giao diện {@code *Api}
   */
  public static List<Class<?>> loadAllApiInterfaces() {
    return Arrays.stream(ContractModule.values())
        .flatMap(m -> loadApiInterfaces(m).stream())
        .toList();
  }

  /**
   * Tìm đường dẫn tệp hợp đồng OpenAPI đã gộp ({@code contracts/dist/<module>.yaml}) hoặc tệp gốc
   * ({@code contracts/openapi/modules/<module>.yaml}) tính từ thư mục làm việc hiện tại.
   *
   * @param module mô-đun hợp đồng
   * @param workingDir thư mục gốc tìm kiếm
   * @return đường dẫn tới tệp YAML hợp đồng
   */
  public static Path resolveContractSpecPath(ContractModule module, Path workingDir) {
    Objects.requireNonNull(module, "module must not be null");
    Objects.requireNonNull(workingDir, "workingDir must not be null");
    Path current = workingDir.toAbsolutePath().normalize();
    for (int depth = 0; depth < 6 && current != null; depth++) {
      Path bundled = current.resolve("contracts/dist").resolve(module.specFileName());
      if (Files.isRegularFile(bundled)) {
        return bundled;
      }
      Path raw = current.resolve("contracts/openapi/modules").resolve(module.specFileName());
      if (Files.isRegularFile(raw)) {
        return raw;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "Không tìm thấy tệp hợp đồng OpenAPI cho mô-đun "
            + module.moduleId()
            + " từ thư mục "
            + workingDir);
  }
}
