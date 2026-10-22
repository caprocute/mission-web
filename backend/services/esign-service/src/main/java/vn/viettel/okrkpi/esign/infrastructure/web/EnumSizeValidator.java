package vn.viettel.okrkpi.esign.infrastructure.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.constraints.Size;

/**
 * Cho {@code @Size} kiểm được trường kiểu enum mà openapi-generator sinh ra khi hợp đồng khai cả
 * {@code enum} lẫn {@code maxLength} (ví dụ {@code EsignRequestCreate.sourceModule}); thiếu
 * validator này Hibernate Validator ném HV000030 và yêu cầu hợp lệ thành lỗi 500. Đăng ký qua
 * {@code META-INF/services}; độ dài tính trên giá trị chuỗi của hằng enum.
 */
public class EnumSizeValidator implements ConstraintValidator<Size, Enum<?>> {

  private int min;
  private int max;

  @Override
  public void initialize(Size size) {
    min = size.min();
    max = size.max();
  }

  @Override
  public boolean isValid(Enum<?> value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    int length = value.toString().length();
    return length >= min && length <= max;
  }
}
