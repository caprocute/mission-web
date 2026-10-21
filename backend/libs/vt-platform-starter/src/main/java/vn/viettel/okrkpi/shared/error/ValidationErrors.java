package vn.viettel.okrkpi.shared.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

/**
 * Chuyển lỗi Jakarta Validation và lỗi ràng buộc dữ liệu của Spring thành {@code errors[]} của hợp
 * đồng. Thông điệp lấy từ annotation khi lập trình viên ghi thông điệp tiếng Việt riêng; khi chỉ có
 * thông điệp mặc định (tiếng Anh) của Jakarta/Hibernate Validator thì thay bằng câu tiếng Việt
 * chung theo loại ràng buộc. Thông điệp kỹ thuật của Spring (tên lớp Java, lỗi chuyển kiểu) không
 * bao giờ được trả ra.
 */
final class ValidationErrors {

  static final String GENERIC = "Giá trị không hợp lệ";
  static final String TYPE_MISMATCH = "Giá trị không đúng kiểu dữ liệu";
  static final String REQUIRED = "Trường này là bắt buộc";

  private ValidationErrors() {}

  static List<ProblemFieldError> fromBindingResult(Errors result, String prefix) {
    List<ProblemFieldError> out = new ArrayList<>();
    for (FieldError fe : result.getFieldErrors()) {
      out.add(ProblemFieldError.of(join(prefix, fe.getField()), message(fe)));
    }
    for (ObjectError ge : result.getGlobalErrors()) {
      out.add(ProblemFieldError.of(prefix == null ? "" : prefix, message(ge)));
    }
    return out;
  }

  static List<ProblemFieldError> fromViolations(Set<? extends ConstraintViolation<?>> violations) {
    List<ProblemFieldError> out = new ArrayList<>();
    for (ConstraintViolation<?> cv : violations) {
      out.add(ProblemFieldError.of(path(cv.getPropertyPath()), message(constraintName(cv), cv)));
    }
    out.sort((a, b) -> a.field().compareTo(b.field()));
    return out;
  }

  /** Thông điệp cho một lỗi của Spring (có thể bọc một {@link ConstraintViolation}). */
  static String message(ObjectError error) {
    ConstraintViolation<?> cv =
        error.contains(ConstraintViolation.class) ? error.unwrap(ConstraintViolation.class) : null;
    if (cv != null) {
      return message(constraintName(cv), cv);
    }
    return nonConstraintMessage(error);
  }

  static String message(String constraint, ConstraintViolation<?> cv) {
    if (isDefaultTemplate(cv.getMessageTemplate())) {
      return defaultMessage(constraint, cv.getConstraintDescriptor().getAttributes());
    }
    String m = cv.getMessage();
    return m == null || m.isBlank() ? GENERIC : m;
  }

  static String nonConstraintMessage(MessageSourceResolvable error) {
    String[] codes = error.getCodes();
    if (codes != null) {
      for (String c : codes) {
        if (c.startsWith("typeMismatch")) {
          return TYPE_MISMATCH;
        }
      }
    }
    String m = error.getDefaultMessage();
    if (m == null || m.isBlank() || looksTechnical(m)) {
      return GENERIC;
    }
    return m;
  }

  static boolean looksTechnical(String message) {
    return message.contains("java.")
        || message.contains("Exception")
        || message.startsWith("Failed to")
        || message.contains("{");
  }

  static boolean isDefaultTemplate(String template) {
    return template != null
        && (template.startsWith("{jakarta.validation.constraints.")
            || template.startsWith("{org.hibernate.validator.constraints."));
  }

  static String constraintName(ConstraintViolation<?> cv) {
    return cv.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
  }

  /** Câu tiếng Việt chung theo loại ràng buộc chuẩn. */
  static String defaultMessage(String constraint, Map<String, Object> attrs) {
    if (constraint == null) {
      return GENERIC;
    }
    return switch (constraint) {
      case "NotNull", "NotBlank", "NotEmpty" -> REQUIRED;
      case "Size", "Length" -> sizeMessage(attrs.get("min"), attrs.get("max"));
      case "Min", "DecimalMin" -> "Giá trị tối thiểu là " + attrs.get("value");
      case "Max", "DecimalMax" -> "Giá trị tối đa là " + attrs.get("value");
      case "Range" -> "Giá trị phải từ " + attrs.get("min") + " đến " + attrs.get("max");
      case "Positive" -> "Giá trị phải lớn hơn 0";
      case "PositiveOrZero" -> "Giá trị không được âm";
      case "Negative" -> "Giá trị phải nhỏ hơn 0";
      case "NegativeOrZero" -> "Giá trị không được dương";
      case "Pattern" -> "Giá trị không đúng định dạng";
      case "Email" -> "Địa chỉ thư điện tử không hợp lệ";
      case "Past", "PastOrPresent" -> "Thời điểm không được ở tương lai";
      case "Future", "FutureOrPresent" -> "Thời điểm không được ở quá khứ";
      case "Digits" -> "Số chữ số không hợp lệ";
      default -> GENERIC;
    };
  }

  static String sizeMessage(Object min, Object max) {
    int lo = min instanceof Number n ? n.intValue() : 0;
    int hi = max instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
    if (hi == Integer.MAX_VALUE) {
      return lo <= 0 ? GENERIC : "Độ dài tối thiểu là " + lo;
    }
    if (lo <= 0) {
      return "Độ dài tối đa là " + hi;
    }
    return "Độ dài phải từ " + lo + " đến " + hi;
  }

  /**
   * Đường dẫn chấm theo JSON của thân yêu cầu: bỏ nút phương thức và nút tham số (khi phía sau còn
   * thuộc tính), giữ chỉ số phần tử dạng {@code items[0].percent}.
   */
  static String path(Path propertyPath) {
    List<Path.Node> nodes = new ArrayList<>();
    propertyPath.forEach(nodes::add);
    StringBuilder sb = new StringBuilder();
    Iterator<Path.Node> it = nodes.iterator();
    int index = 0;
    while (it.hasNext()) {
      Path.Node node = it.next();
      index++;
      ElementKind kind = node.getKind();
      if (kind == ElementKind.METHOD
          || kind == ElementKind.CONSTRUCTOR
          || kind == ElementKind.CROSS_PARAMETER
          || kind == ElementKind.RETURN_VALUE) {
        continue;
      }
      if (kind == ElementKind.PARAMETER && index < nodes.size()) {
        continue;
      }
      if (node.isInIterable()) {
        Object key = node.getIndex() != null ? node.getIndex() : node.getKey();
        sb.append('[').append(key == null ? "" : key).append(']');
      }
      String name = node.getName();
      // Nút phần tử chứa ("<list element>", "<map value>"…) chỉ mang chỉ số, không có tên JSON.
      if (name != null && !name.isEmpty() && name.charAt(0) != '<') {
        if (!sb.isEmpty()) {
          sb.append('.');
        }
        sb.append(name);
      }
    }
    return sb.toString();
  }

  static String join(String prefix, String field) {
    if (prefix == null || prefix.isEmpty()) {
      return field;
    }
    return field == null || field.isEmpty() ? prefix : prefix + "." + field;
  }
}
