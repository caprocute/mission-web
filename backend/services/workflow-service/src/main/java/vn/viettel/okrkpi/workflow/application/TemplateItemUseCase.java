package vn.viettel.okrkpi.workflow.application;

import java.util.List;
import vn.viettel.okrkpi.shared.kernel.Result;
import vn.viettel.okrkpi.workflow.domain.TemplateItem;
import vn.viettel.okrkpi.workflow.domain.TemplateItemError;
import vn.viettel.okrkpi.workflow.domain.TemplateItemId;

/** Cổng vào (inbound port) của use case mẫu. */
public interface TemplateItemUseCase {

  Result<TemplateItem, TemplateItemError> create(CreateTemplateItemCommand command);

  Result<TemplateItem, TemplateItemError> activate(TemplateItemId id);

  Result<TemplateItem, TemplateItemError> findById(TemplateItemId id);

  List<TemplateItem> listAll();
}
