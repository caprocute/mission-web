package vn.viettel.okrkpi.docgen.domain.render;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Kho mẫu {@code docgen.template}, đọc trong ngữ cảnh tenant (RLS). */
public interface TemplateRepository {

  Optional<Template> findById(UUID id);

  /** Các phiên bản của một mẫu, phiên bản mới nhất trước. */
  List<Template> findByFormCode(String formCode);

  void insert(Template template, UUID actor);
}
