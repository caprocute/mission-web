package vn.viettel.okrkpi.docgen.application;

import java.util.List;
import vn.viettel.okrkpi.docgen.domain.TemplateItem;
import vn.viettel.okrkpi.docgen.domain.TemplateItemError;
import vn.viettel.okrkpi.docgen.domain.TemplateItemId;
import vn.viettel.okrkpi.shared.kernel.Result;

/** Cổng vào (inbound port) của use case mẫu. */
public interface TemplateItemUseCase {

  Result<TemplateItem, TemplateItemError> create(CreateTemplateItemCommand command);

  Result<TemplateItem, TemplateItemError> activate(TemplateItemId id);

  Result<TemplateItem, TemplateItemError> findById(TemplateItemId id);

  List<TemplateItem> listAll();
}
