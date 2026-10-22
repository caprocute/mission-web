package vn.viettel.okrkpi.docgen.application.render;

import vn.viettel.okrkpi.docgen.domain.render.RenderJob;
import vn.viettel.okrkpi.docgen.domain.render.RenderOutput;
import vn.viettel.okrkpi.docgen.domain.render.Template;

/** Phát EVT-17 {@code dossier.form.generated.v1} qua outbox trong cùng giao dịch. */
public interface RenderEvents {

  /**
   * @param version {@code row_version} của tác vụ sau khi chuyển {@code XONG}
   */
  void formGenerated(RenderJob job, Template template, RenderOutput output, long version);
}
