package vn.viettel.okrkpi.analytics.domain;

import java.util.List;

/**
 * Nội dung một tệp xuất (báo cáo bảng điều hành, chuỗi bằng chứng) ở dạng bảng, độc lập với định
 * dạng PDF hay XLSX. Mọi chuỗi đã là nhãn tiếng Việt cho người đọc, không có mã trạng thái thô.
 */
public record ReportDoc(String title, List<String> notes, List<Section> sections) {

  public ReportDoc {
    notes = List.copyOf(notes);
    sections = List.copyOf(sections);
  }

  public record Section(String title, List<String> headers, List<List<String>> rows) {

    public Section {
      headers = List.copyOf(headers);
      rows = rows.stream().map(List::copyOf).toList();
    }
  }
}
