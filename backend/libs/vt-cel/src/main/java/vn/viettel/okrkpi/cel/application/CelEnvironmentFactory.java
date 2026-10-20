package vn.viettel.okrkpi.cel.application;

import dev.cel.common.CelOptions;
import dev.cel.common.types.CelType;
import dev.cel.common.types.ListType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.extensions.CelExtensions;
import dev.cel.parser.CelStandardMacro;
import dev.cel.runtime.CelFunctionBinding;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeBuilder;
import dev.cel.runtime.CelRuntimeFactory;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import vn.viettel.okrkpi.cel.domain.CelSandboxOptions;
import vn.viettel.okrkpi.cel.domain.CelValueType;
import vn.viettel.okrkpi.cel.domain.MissionCelFunctions;
import vn.viettel.okrkpi.cel.domain.WorkingCalendar;

/**
 * Nhà máy khởi tạo môi trường biên dịch ({@link CelCompiler}) và thực thi ({@link CelRuntime}) cho
 * biểu thức CEL trong nền tảng Mission (TL-01 §4.3, ADR-10).
 *
 * <p>Môi trường được đóng hộp (sandbox) chặt chẽ:
 *
 * <ul>
 *   <li>Chỉ bật các toán tử chuẩn, macro danh sách chuẩn ({@code all}, {@code exists}, {@code
 *       exists_one}, {@code filter}, {@code map}, {@code has}), các hàm chuỗi/danh sách/toán học
 *       tất định và 3 hàm nghiệp vụ {@code sum}, {@code ngay_lam_viec} (kèm bí danh {@code
 *       workdays}), {@code min_muc}.
 *   <li>Tuyệt đối không đăng ký hàm lấy thời gian hiện tại ({@code now}) hay hàm sinh số ngẫu nhiên
 *       để bảo đảm tính tất định 100% giữa máy chủ và trình duyệt.
 * </ul>
 */
public final class CelEnvironmentFactory {

  public static final String FN_SUM = "sum";
  public static final String FN_NGAY_LAM_VIEC = "ngay_lam_viec";
  public static final String FN_WORKDAYS = "workdays";
  public static final String FN_MIN_MUC = "min_muc";

  private CelEnvironmentFactory() {}

  /** Tạo {@link CelOptions} theo cấu hình giới hạn an toàn {@link CelSandboxOptions}. */
  public static CelOptions createCelOptions(CelSandboxOptions sandboxOptions) {
    Objects.requireNonNull(sandboxOptions, "sandboxOptions must not be null");
    return CelOptions.current()
        .maxExpressionCodePointSize(sandboxOptions.maxExpressionLength())
        .maxParseRecursionDepth(Math.max(sandboxOptions.maxAstDepth() * 4, 32))
        .maxParseExpressionNodeCount(sandboxOptions.maxAstNodeCount())
        .comprehensionMaxIterations(sandboxOptions.maxComprehensionIterations())
        .enableHeterogeneousNumericComparisons(true)
        .populateMacroCalls(true)
        .build();
  }

  /**
   * Tạo trình biên dịch {@link CelCompiler} với danh sách biến được phép khai báo trước.
   *
   * @param variableTypes ánh xạ tên biến sang kiểu {@link CelValueType}
   * @param sandboxOptions cấu hình giới hạn an toàn
   * @return {@link CelCompiler} đã cấu hình kiểm kiểu tĩnh
   */
  public static CelCompiler createCompiler(
      Map<String, CelValueType> variableTypes, CelSandboxOptions sandboxOptions) {
    Objects.requireNonNull(variableTypes, "variableTypes must not be null");
    Objects.requireNonNull(sandboxOptions, "sandboxOptions must not be null");

    CelOptions celOptions = createCelOptions(sandboxOptions);
    CelCompilerBuilder builder =
        CelCompilerFactory.standardCelCompilerBuilder()
            .setOptions(celOptions)
            .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
            .addLibraries(
                CelExtensions.math(celOptions), CelExtensions.lists(), CelExtensions.strings())
            .addFunctionDeclarations(
                dev.cel.common.CelFunctionDecl.newFunctionDeclaration(
                    FN_SUM,
                    dev.cel.common.CelOverloadDecl.newGlobalOverload(
                        "sum_list_double", SimpleType.DOUBLE, ListType.create(SimpleType.DOUBLE)),
                    dev.cel.common.CelOverloadDecl.newGlobalOverload(
                        "sum_list_int", SimpleType.DOUBLE, ListType.create(SimpleType.INT))),
                dev.cel.common.CelFunctionDecl.newFunctionDeclaration(
                    FN_NGAY_LAM_VIEC,
                    dev.cel.common.CelOverloadDecl.newGlobalOverload(
                        "ngay_lam_viec_string_string",
                        SimpleType.INT,
                        SimpleType.STRING,
                        SimpleType.STRING)),
                dev.cel.common.CelFunctionDecl.newFunctionDeclaration(
                    FN_WORKDAYS,
                    dev.cel.common.CelOverloadDecl.newGlobalOverload(
                        "workdays_string_string",
                        SimpleType.INT,
                        SimpleType.STRING,
                        SimpleType.STRING)),
                dev.cel.common.CelFunctionDecl.newFunctionDeclaration(
                    FN_MIN_MUC,
                    dev.cel.common.CelOverloadDecl.newGlobalOverload(
                        "min_muc_string_string",
                        SimpleType.STRING,
                        SimpleType.STRING,
                        SimpleType.STRING)));

    for (Map.Entry<String, CelValueType> entry : variableTypes.entrySet()) {
      builder.addVar(entry.getKey(), toCelType(entry.getValue()));
    }

    return builder.build();
  }

  /**
   * Tạo môi trường thực thi {@link CelRuntime} gắn với lịch làm việc {@link WorkingCalendar} và
   * giới hạn an toàn {@link CelSandboxOptions}.
   */
  @SuppressWarnings("rawtypes")
  public static CelRuntime createRuntime(
      WorkingCalendar workingCalendar, CelSandboxOptions sandboxOptions) {
    Objects.requireNonNull(workingCalendar, "workingCalendar must not be null");
    Objects.requireNonNull(sandboxOptions, "sandboxOptions must not be null");

    CelOptions celOptions = createCelOptions(sandboxOptions);
    CelRuntimeBuilder builder =
        CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(celOptions)
            .addLibraries(
                CelExtensions.math(celOptions), CelExtensions.lists(), CelExtensions.strings())
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    FN_SUM,
                    CelFunctionBinding.from(
                        "sum_list_double",
                        Collection.class,
                        (Collection list) -> MissionCelFunctions.sum(list))))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    FN_NGAY_LAM_VIEC,
                    CelFunctionBinding.from(
                        "ngay_lam_viec_string_string",
                        String.class,
                        String.class,
                        (String from, String to) ->
                            MissionCelFunctions.ngayLamViec(from, to, workingCalendar))))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    FN_WORKDAYS,
                    CelFunctionBinding.from(
                        "workdays_string_string",
                        String.class,
                        String.class,
                        (String from, String to) ->
                            MissionCelFunctions.ngayLamViec(from, to, workingCalendar))))
            .addFunctionBindings(
                CelFunctionBinding.fromOverloads(
                    FN_MIN_MUC,
                    CelFunctionBinding.from(
                        "min_muc_string_string",
                        String.class,
                        String.class,
                        MissionCelFunctions::minMuc)));

    return builder.build();
  }

  /** Ánh xạ {@link CelValueType} của miền nghiệp vụ sang {@link CelType} của {@code cel-java}. */
  public static CelType toCelType(CelValueType valueType) {
    Objects.requireNonNull(valueType, "valueType must not be null");
    return valueType.toCelType();
  }
}
