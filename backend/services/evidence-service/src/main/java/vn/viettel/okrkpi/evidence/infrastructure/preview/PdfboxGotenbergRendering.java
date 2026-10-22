package vn.viettel.okrkpi.evidence.infrastructure.preview;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import vn.viettel.okrkpi.evidence.application.PreviewRendering;

/**
 * Dựng xem trước: DOCX, XLSX sang PDF qua Gotenberg 8 dùng chung của cell ({@code POST
 * /forms/libreoffice/convert}), PDF dựng ảnh từng trang bằng PDFBox (bộ nhớ đệm trên tệp tạm, một
 * trang mỗi lần), ảnh gốc thu nhỏ bằng ImageIO. Mọi ảnh ra là PNG mã hoá lại nên không mang siêu dữ
 * liệu EXIF của tệp gốc.
 */
public class PdfboxGotenbergRendering implements PreviewRendering {

  static final String CONVERT = "/forms/libreoffice/convert";

  private final RestClient gotenberg;

  public PdfboxGotenbergRendering(RestClient gotenberg) {
    this.gotenberg = gotenberg;
  }

  @Override
  public void toPdf(Path source, String fileName, Path target) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add(
        "files",
        new FileSystemResource(source) {
          @Override
          public String getFilename() {
            return fileName;
          }
        });
    try {
      gotenberg
          .post()
          .uri(CONVERT)
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(form)
          .exchange(
              (req, res) -> {
                if (res.getStatusCode().is4xxClientError()) {
                  boolean busy = res.getStatusCode().value() == 429;
                  throw new RenderFailure(
                      "Gotenberg không chuyển được tệp: " + res.getStatusCode(), busy, null);
                }
                if (!res.getStatusCode().is2xxSuccessful()) {
                  throw new RenderFailure("Gotenberg lỗi: " + res.getStatusCode(), true, null);
                }
                try (InputStream in = res.getBody()) {
                  Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return null;
              });
    } catch (HttpClientErrorException e) {
      throw new RenderFailure("Gotenberg không chuyển được tệp", false, e);
    } catch (RestClientException e) {
      throw new RenderFailure("Không gọi được Gotenberg: " + e.getMessage(), true, e);
    }
  }

  @Override
  public int rasterizePdf(Path pdf, float dpi, int maxPages, PageSink sink) {
    try (PDDocument doc = Loader.loadPDF(pdf.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
      PDFRenderer renderer = new PDFRenderer(doc);
      int pages = Math.min(doc.getNumberOfPages(), maxPages);
      for (int i = 0; i < pages; i++) {
        BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
        sink.accept(i + 1, png(img), img.getWidth(), img.getHeight());
      }
      return pages;
    } catch (IOException e) {
      throw new RenderFailure("Tệp PDF hỏng hoặc có mật khẩu", false, e);
    }
  }

  @Override
  public void scaleImage(Path image, int maxWidth, PageSink sink) {
    try {
      BufferedImage src = ImageIO.read(image.toFile());
      if (src == null) {
        throw new RenderFailure("Ảnh không đọc được", false, null);
      }
      int w = Math.min(src.getWidth(), maxWidth);
      int h = Math.max(1, (int) Math.round(src.getHeight() * (w / (double) src.getWidth())));
      BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
      Graphics2D g = out.createGraphics();
      try {
        g.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
      } finally {
        g.dispose();
      }
      sink.accept(1, png(out), w, h);
    } catch (IOException e) {
      throw new RenderFailure("Ảnh không đọc được", false, e);
    }
  }

  private static byte[] png(BufferedImage img) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }
}
