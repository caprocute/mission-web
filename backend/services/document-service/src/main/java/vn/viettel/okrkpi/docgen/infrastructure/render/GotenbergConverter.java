package vn.viettel.okrkpi.docgen.infrastructure.render;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.PdfConverter;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.TransientFailure;

/**
 * Chuyển DOCX sang PDF/A-2b qua Gotenberg 8 ({@code POST /forms/libreoffice/convert}, trường {@code
 * pdfa=PDF/A-2b}). Lỗi 4xx là tệp hỏng (không thử lại); lỗi mạng, 5xx, 503 khi Gotenberg quá tải là
 * lỗi tạm thời.
 *
 * <p>Tắt {@code exportFormFields}: LibreOffice mặc định xuất content control của DOCX (vùng trộn
 * của mẫu DL-05) thành ô nhập liệu PDF vẽ bằng phông Times chuẩn không nhúng, bảng mã WinAnsi, nên
 * mất dấu tiếng Việt và người nhận sửa được ô trên tệp. Xuất như văn bản thường thì giá trị in đúng
 * phông của mẫu và tệp không còn ô sửa được (DV-11-02).
 */
public class GotenbergConverter implements PdfConverter {

  static final String PATH = "/forms/libreoffice/convert";
  static final String PDFA_2B = "PDF/A-2b";

  private final RestClient client;

  public GotenbergConverter(RestClient client) {
    this.client = client;
  }

  @Override
  public byte[] toPdfA2b(byte[] docx, String fileName) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "files",
        new ByteArrayResource(docx) {
          @Override
          public String getFilename() {
            return fileName;
          }
        });
    form.add("pdfa", PDFA_2B);
    form.add("exportFormFields", "false");
    try {
      byte[] pdf =
          client
              .post()
              .uri(PATH)
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(form)
              .retrieve()
              .body(byte[].class);
      if (pdf == null || pdf.length == 0) {
        throw new TransientFailure("Gotenberg trả tệp rỗng", null);
      }
      return pdf;
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode().value() == 429) {
        throw new TransientFailure("Gotenberg đang quá tải", e);
      }
      throw new RejectedDocument("Gotenberg không chuyển được tệp: " + e.getStatusCode());
    } catch (RestClientException e) {
      throw new TransientFailure("Không gọi được Gotenberg: " + e.getMessage(), e);
    }
  }
}
