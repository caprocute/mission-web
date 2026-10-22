package vn.viettel.okrkpi.evidence.infrastructure.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;
import vn.viettel.okrkpi.evidence.application.PreviewRendering.RenderFailure;

class PdfboxGotenbergRenderingTest {

  @TempDir Path dir;

  final PdfboxGotenbergRendering rendering =
      new PdfboxGotenbergRendering(RestClient.builder().baseUrl("http://127.0.0.1:1").build());

  @Test
  void dungAnhTrangPdfA4O150Dpi() throws Exception {
    Path pdf = dir.resolve("a.pdf");
    try (PDDocument doc = new PDDocument()) {
      for (int i = 0; i < 3; i++) {
        doc.addPage(new PDPage(PDRectangle.A4));
      }
      doc.save(pdf.toFile());
    }
    List<int[]> got = new ArrayList<>();
    int n = rendering.rasterizePdf(pdf, 150f, 2, (p, png, w, h) -> got.add(new int[] {p, w, h}));
    assertThat(n).isEqualTo(2);
    assertThat(got.get(0)[1]).isEqualTo(1240);
    assertThat(got.get(0)[2]).isBetween(1753, 1754);
    assertThat(got.get(1)[0]).isEqualTo(2);

    Path broken = Files.write(dir.resolve("b.pdf"), new byte[] {1, 2, 3});
    assertThatThrownBy(() -> rendering.rasterizePdf(broken, 150f, 5, (p, png, w, h) -> {}))
        .isInstanceOf(RenderFailure.class)
        .matches(e -> !((RenderFailure) e).transientFailure());
  }

  @Test
  void anhLonThuNhoVeChieuRongToiDa() throws Exception {
    Path img = dir.resolve("a.png");
    ImageIO.write(new BufferedImage(2480, 1000, BufferedImage.TYPE_INT_RGB), "png", img.toFile());
    List<int[]> got = new ArrayList<>();
    rendering.scaleImage(img, 1240, (p, png, w, h) -> got.add(new int[] {p, w, h}));
    assertThat(got.get(0)).containsExactly(1, 1240, 500);

    Path junk = Files.write(dir.resolve("c.png"), new byte[] {1, 2, 3});
    assertThatThrownBy(() -> rendering.scaleImage(junk, 1240, (p, png, w, h) -> {}))
        .isInstanceOf(RenderFailure.class);
  }

  @Test
  void gotenbergKhongToiDuocLaLoiTamThoi() throws Exception {
    Path src = Files.write(dir.resolve("a.docx"), new byte[] {1});
    assertThatThrownBy(() -> rendering.toPdf(src, "tep.docx", dir.resolve("o.pdf")))
        .isInstanceOf(RenderFailure.class)
        .matches(e -> ((RenderFailure) e).transientFailure());
  }
}
