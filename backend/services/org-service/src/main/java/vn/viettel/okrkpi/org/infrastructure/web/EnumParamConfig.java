package vn.viettel.okrkpi.org.infrastructure.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgDiscrepancyField;

/**
 * Tham số truy vấn có giá trị enum viết thường trong hợp đồng ({@code field=org_unit_id}) phải đọc bằng
 * {@code fromValue}; bộ chuyển mặc định của Spring dùng tên hằng nên trả 400.
 *
 * <p>DV-04-05: hợp đồng khai phần {@code file} của {@code importOrgExcel} bằng {@code contentMediaType} (OpenAPI 3.1)
 * nên mã sinh nhận {@code @RequestParam String file}; phần tệp được đổi thành tên tệp, còn nội dung controller đọc từ
 * {@code Part} (sửa mã, giữ hợp đồng theo YC-PT-09; đề nghị hợp đồng thêm {@code format: binary}, wiki trang 14).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class EnumParamConfig implements WebMvcConfigurer {

  @Override
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverter(String.class, OrgDiscrepancyField.class, OrgDiscrepancyField::fromValue);
    registry.addConverter(MultipartFile.class, String.class,
        f -> f.getOriginalFilename() == null || f.getOriginalFilename().isBlank() ? "tep-nhap.xlsx"
            : f.getOriginalFilename());
  }
}
