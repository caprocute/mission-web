package vn.viettel.okrkpi.org.infrastructure.web;

import java.util.Map;
import vn.viettel.okrkpi.org.application.orgimport.ImportColumns;
import vn.viettel.okrkpi.org.application.orgimport.ImportError;
import vn.viettel.okrkpi.org.application.orgimport.OrgImportService.ImportJobView;
import vn.viettel.okrkpi.org.infrastructure.web.model.GetMyOrgProfile401Response;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgImportError;
import vn.viettel.okrkpi.org.infrastructure.web.model.OrgImportReport;
import vn.viettel.okrkpi.org.infrastructure.web.model.ImportOrgExcel202Response;

/**
 * Tác vụ nhập (DV-04-05) sang lược đồ {@code Job}: khi tệp có lỗi, {@code resultLocation} trỏ báo cáo
 * {@code /org/imports/{id}/report} chứa lỗi từng dòng, từng cột kèm lý do tiếng Việt (hợp đồng {@code importOrgExcel}).
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
      // Hợp đồng importOrgExcel: báo cáo lỗi từng dòng nằm ở resultLocation, problem chỉ tóm tắt.
      if (!v.errors().isEmpty()) {
        job.setResultLocation("/api/v1/org/imports/" + v.id() + "/report");
      }
      GetMyOrgProfile401Response p = new GetMyOrgProfile401Response();
      p.setType("https://okrkpi.vn/problems/import-invalid");
      p.setTitle("Tệp nhập chưa hợp lệ");
      p.setStatus(422);
      p.setCode("ORG-422-IMPORT-INVALID");
      p.setDetail(v.message());
      job.setProblem(p);
    }
    return job;
  }

  static OrgImportReport toReport(ImportJobView v) {
    OrgImportReport r = new OrgImportReport();
    r.setJobId(v.id());
    r.setStatus(OrgImportReport.StatusEnum.fromValue(v.status()));
    r.setTotalRows(v.totalRows());
    r.setCreatedUnits(v.createdUnits());
    r.setChangeOrderId(v.changeOrderId());
    for (ImportError e : v.errors()) {
      OrgImportError out = new OrgImportError();
      out.setRow(e.row());
      out.setColumn(e.column());
      out.setField(FIELD.getOrDefault(e.column(), "file"));
      out.setMessage(e.row() > 0 && !e.column().isEmpty()
          ? "Dòng " + e.row() + ", cột " + e.column() + ": " + e.message() : e.message());
      out.setRule(e.rule());
      r.addErrorsItem(out);
    }
    return r;
  }
}
