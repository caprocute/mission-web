package vn.viettel.okrkpi.integ.application;

import java.util.List;
import vn.viettel.okrkpi.integ.domain.InboundMessage;

/**
 * Translator của lớp chống ô nhiễm: dịch mô hình đối tác sang sự kiện mô hình chuẩn, tra hoặc tạo
 * ánh xạ qua {@link TranslationContext#mappings()}; trả danh sách rỗng khi thông điệp không cần
 * phát.
 */
@FunctionalInterface
public interface Translator {

  List<CanonicalEvent> translate(TranslationContext context, InboundMessage message);
}
