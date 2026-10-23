package vn.viettel.okrkpi.docgen.infrastructure.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import vn.viettel.okrkpi.docgen.application.render.BangChamDiemRenderer;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.ObjectStore;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.application.render.FormComposer;
import vn.viettel.okrkpi.docgen.application.render.FormModelBuilder;
import vn.viettel.okrkpi.docgen.application.render.FormRenderer;
import vn.viettel.okrkpi.docgen.application.render.Hashes;
import vn.viettel.okrkpi.docgen.application.render.Mau1Renderer;
import vn.viettel.okrkpi.docgen.application.render.Mau2Renderer;
import vn.viettel.okrkpi.docgen.application.render.Mau3Renderer;
import vn.viettel.okrkpi.docgen.application.render.RenderContext;
import vn.viettel.okrkpi.docgen.domain.render.Template;
import vn.viettel.okrkpi.docgen.domain.render.TemplateStatus;

/**
 * Trộn bảy mẫu giả lập DL-05 bằng dữ liệu vàng (DV-11-02, DV-11-03), không cần Gotenberg: mọi vùng
 * trộn được điền (không còn chữ giữ chỗ), nhãn lấy theo bảng nhãn của mẫu (BR-11-04), vùng số liệu
 * khoá {@code sdtContentLocked} (BR-11-02), dòng tiêu đề bảng lặp lại và dòng không ngắt trang.
 */
class ManifestMergeTest {

  static final UUID TENANT = UUID.fromString("0192f1a2-0000-7000-8000-00000000d001");
  static final ObjectMapper JSON = new ObjectMapper();

  static List<String> codes() {
    return GoldenForms.CODES;
  }

  static List<FormRenderer> renderers() {
    List<FormRenderer> out = new java.util.ArrayList<>(Mau1Renderer.all());
    out.add(new Mau2Renderer());
    out.add(new Mau3Renderer());
    out.add(new BangChamDiemRenderer());
    return out;
  }

  /** Kho trong bộ nhớ chứa mẫu và kê khai của một mã. */
  static final class Store implements ObjectStore {
    final Map<String, byte[]> objects = new HashMap<>();

    @Override
    public byte[] get(String bucket, String key) {
      return objects.get(bucket + "/" + key);
    }

    @Override
    public void put(String bucket, String key, byte[] content, String contentType) {
      objects.put(bucket + "/" + key, content);
    }
  }

  static Template template(Store store, String code) {
    byte[] docx = GoldenForms.docx(code);
    byte[] manifest = GoldenForms.manifest(code);
    store.put("templates", code + "/v1.docx", docx, null);
    store.put("templates", code + "/v1.json", manifest, null);
    return new Template(
        UUID.randomUUID(),
        TENANT,
        code,
        1,
        List.of(),
        code + "/v1.docx",
        Hashes.sha256(docx),
        TemplateStatus.HIEU_LUC,
        code + "/v1.json",
        Hashes.sha256(manifest));
  }

  static FormComposer composer(Store store) {
    return new FormComposer(
        store,
        renderers(),
        new Docx4jMerger(),
        JSON,
        new FormModelBuilder(new DocgenTestBeans().fakeOpener()));
  }

  static String documentXml(byte[] docx, String part) throws Exception {
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
      for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
        if (e.getName().equals(part)) {
          return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
    }
    throw new IllegalStateException(part);
  }

  /** Chữ của mọi {@code w:t}, nối theo đoạn. */
  static String text(String xml) {
    StringBuilder out = new StringBuilder();
    Matcher m = Pattern.compile("<w:t(?: [^>]*)?>([^<]*)</w:t>|</w:p>|<w:br/>").matcher(xml);
    while (m.find()) {
      out.append(m.group(1) != null ? m.group(1) : "\n");
    }
    return out.toString().replace("&amp;", "&");
  }

  static byte[] merge(String code, boolean draft) {
    Store store = new Store();
    Template template = template(store, code);
    UUID form = UUID.randomUUID();
    return composer(store)
        .compose(
            template,
            GoldenForms.sealedInput(code, form),
            new RenderContext(TENANT, form, code, 1, "KHOI_DANG", "2.0.0", draft),
            "Tem thử");
  }

  @ParameterizedTest
  @MethodSource("codes")
  void moiVungTronDuocDienVaVungSoLieuBiKhoa(String code) throws Exception {
    byte[] merged = merge(code, false);
    String body = documentXml(merged, "word/document.xml");
    String footer = documentXml(merged, "word/footer1.xml");
    assertThat(body + footer).doesNotContain("showingPlcHdr");
    assertThat(text(body)).doesNotContainPattern("\\[[^\\]]+\\]");
    assertThat(text(footer))
        .contains("KHOI_DANG phiên bản 2.0.0")
        .contains("Phiên bản mẫu biểu: 1 (0.9.0-gia-lap)");
    assertThat(body).contains("w:val=\"sdtContentLocked\"");
    if (body.contains("<w:tbl>")) {
      assertThat(body).contains("<w:tblHeader/>").contains("<w:cantSplit/>");
    }
    for (String secret : GoldenForms.plainSealedValues(code)) {
      assertThat(text(body)).contains(secret.lines().findFirst().orElseThrow());
    }
  }

  @Test
  void nhanTieuChiTheoTungMauVaDinhDangTiengViet() throws Exception {
    String mau1 = text(documentXml(merge("MAU_1B", false), "word/document.xml"));
    assertThat(mau1)
        .contains("Đảm bảo", "Không đảm bảo", "87,3", "12,5%", "90%", "0,913", "45,6")
        .contains("Hoàn thành tốt nhiệm vụ", "Đã khắc phục", "Đang khắc phục")
        .doesNotContain("Vi phạm", "HTTNV", "DA_KHAC_PHUC");
    String mau2 = text(documentXml(merge("MAU_2", false), "word/document.xml"));
    assertThat(mau2)
        .contains("Không vi phạm", "Vi phạm", "Nhóm 2")
        .contains("Điểm năm được tính trên số quý thực tế công tác trong năm.")
        .doesNotContain("Không đảm bảo", "nghỉ thai sản");
    String mau1a = text(documentXml(merge("MAU_1A", false), "word/document.xml"));
    assertThat(mau1a).contains("01/12/2026", "15/12/2026", "Hoàn thành 18 trên 20 chỉ tiêu");
  }

  @Test
  void mau3SapTheoDonViMucVaTen() throws Exception {
    String text = text(documentXml(merge("MAU_3", false), "word/document.xml"));
    int an = text.indexOf("Nguyễn Văn An");
    int binh = text.indexOf("Trần Thị Bình");
    int cuong = text.indexOf("Lê Văn Cường");
    int hoa = text.indexOf("Võ Thị Hoa");
    assertThat(an).isPositive().isLessThan(binh);
    assertThat(binh).isLessThan(cuong);
    assertThat(cuong).isLessThan(hoa);
    assertThat(text).contains("Không tự đánh giá");
  }

  @Test
  void banNhapCoDau() throws Exception {
    String body = text(documentXml(merge("MAU_3", true), "word/document.xml"));
    assertThat(body).startsWith(FormComposer.DRAFT_MARK);
  }

  @Test
  void bangChamDiemTuChoiVetTinhKhacPhienBan() {
    Store store = new Store();
    Template template = template(store, "BANG_CHAM_DIEM");
    UUID form = UUID.randomUUID();
    byte[] data = GoldenForms.sealedInput("BANG_CHAM_DIEM", form);
    assertThatThrownBy(
            () ->
                composer(store)
                    .compose(
                        template,
                        data,
                        new RenderContext(
                            TENANT, form, "BANG_CHAM_DIEM", 1, "KHOI_DANG", "2.1.0", false),
                        null))
        .isInstanceOf(RejectedDocument.class)
        .hasMessageContaining("2.1.0");
  }

  @Test
  void c2GiaiMaSaiTruongBiTuChoi() throws Exception {
    Store store = new Store();
    Template template = template(store, "MAU_1B");
    UUID form = UUID.randomUUID();
    ObjectNode data = GoldenForms.input("MAU_1B");
    byte[] sealedForOther = GoldenForms.sealed("MAU_1B", data, UUID.randomUUID());
    assertThatThrownBy(
            () ->
                composer(store)
                    .compose(
                        template,
                        sealedForOther,
                        new RenderContext(TENANT, form, "MAU_1B", 1, null, null, false),
                        null))
        .isInstanceOf(RejectedDocument.class)
        .hasMessageNotContaining("Chủ động tham mưu");
  }

  @Test
  void mau3HaiNghinDong() throws Exception {
    Store store = new Store();
    Template template = template(store, "MAU_3");
    UUID form = UUID.randomUUID();
    ObjectNode data = GoldenForms.input("MAU_3");
    ArrayNode rows = data.putArray("danh_sach_xep_loai");
    String[] levels = {"HTXSNV", "HTTNV", "HTNV", "KHTNV"};
    for (int i = 0; i < 2000; i++) {
      ObjectNode r = rows.addObject();
      r.put("thu_tu_don_vi", i % 7);
      r.put("ten_don_vi", "Đơn vị " + (i % 7));
      r.put("ho_ten", "Cán bộ số " + i);
      r.put("chuc_danh", "Chuyên viên");
      r.put("muc_tu_de_xuat", levels[i % 4]);
      r.put("muc_quyet_dinh", levels[(i + 1) % 4]);
    }
    long started = System.nanoTime();
    byte[] merged =
        composer(store)
            .compose(
                template,
                GoldenForms.sealed("MAU_3", data, form),
                new RenderContext(TENANT, form, "MAU_3", 1, null, null, false),
                null);
    long ms = (System.nanoTime() - started) / 1_000_000;
    String body = documentXml(merged, "word/document.xml");
    assertThat(Pattern.compile("<w:cantSplit/>").matcher(body).results().count()).isEqualTo(2000);
    System.out.println("Mẫu 3, 2000 dòng: trộn DOCX " + ms + " ms, " + merged.length + " byte");
  }
}
