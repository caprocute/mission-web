package vn.viettel.okrkpi.org.infrastructure.web;

import java.util.Map;
import vn.viettel.okrkpi.org.application.orgimport.ImportColumns;
import vn.viettel.okrkpi.org.application.orgimport.ImportError;
import vn.viettel.okrkpi.org.application.orgimport.OrgImportService.ImportJobView;
import vn.viettel.okrkpi.org.infrastructure.web.model.GetMyOrgProfile401Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.GetMyOrgProfile401ResponseErrorsInner;
import vn.viettel.okrkpi.org.infrastructure.web.model.ImportOrgExcel202Response;

/**
 * Tác vụ nhập (DV-04-05) sang lược đồ {@code Job}: báo cáo lỗi từng dòng nằm ở {@code problem.errors[]} với
 * {@code field = rows.<số dòng>.<cột>} và thông điệp tiếng Việt ghi rõ dòng, cột, lý do.
 */
final class JobWebMapper {

  private static final Map<String, String> FIELD = Map.of(
      ImportColumns.CODE, "code",
      ImportColumns.NAME, "name",
      ImportColumns.SHORT_NAME, "shortName",
      ImportColumns.UNIT_TYPE, "unitType",
      ImportColumns.PARENT, "parentCode",
      ImportColumns.GROUP, "subjectGroup",
      ImportColumns.FLOWS, "flows",
      ImportColumns.LEVEL, "level",
      ImportColumns.VALID_FROM, "validFrom");

  private JobWebMapper() {
  }

  static ImportOrgExcel202Response toDto(ImportJobView v) {
    ImportOrgExcel202Response job = new ImportOrgExcel202Response();
    job.setId(v.id().toString());
    job.setType(v.type());
    job.setStatus(ImportOrgExcel202Response.StatusEnum.fromValue(v.status()));
    job.setProgress(v.progress());
    job.setMessage(v.message());
    job.setUpdatedAt(v.updatedAt());
    if ("HOAN_TAT".equals(v.status())) {
      job.setResultLocation("/api/v1/org/tree");
    }
    if ("THAT_BAI".equals(v.status())) {
      GetMyOrgProfile401Response p = new GetMyOrgProfile401Response();
      p.setType("https://okrkpi.vn/problems/import-invalid");
      p.setTitle("Tệp nhập chưa hợp lệ");
      p.setStatus(422);
      p.setCode("ORG-422-IMPORT-INVALID");
      p.setDetail(v.message());
      for (ImportError e : v.errors()) {
        GetMyOrgProfile401ResponseErrorsInner fe = new GetMyOrgProfile401ResponseErrorsInner();
        fe.setField("rows." + e.row() + "." + FIELD.getOrDefault(e.column(), "file"));
        fe.setMessage(e.row() > 0 && !e.column().isEmpty()
            ? "Dòng " + e.row() + ", cột " + e.column() + ": " + e.message() : e.message());
        fe.setRule(e.rule());
        p.addErrorsItem(fe);
      }
      job.setProblem(p);
    }
    return job;
  }
}
