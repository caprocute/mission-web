package vn.viettel.okrkpi.docgen.application.render;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.DocxMerger;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.ObjectStore;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.domain.render.Template;

/**
 * Bước trộn dùng chung cho lượt sinh của worker và bản xem trước (DV-11-02): lấy tệp mẫu, kiểm băm,
 * chọn bộ ánh xạ theo mã mẫu và phiên bản, rồi trộn ra DOCX. Mẫu có tệp kê khai trộn theo tag
 * content control ({@link FormModelBuilder}); mẫu cũ trộn theo chỗ giữ {@code ${ten}}.
 *
 * <p>Tệp kê khai đã đọc được giữ trong bộ nhớ theo (mẫu, băm), vì mẫu của một phiên bản không đổi
 * nội dung; bộ nhớ đệm giới hạn {@link #MANIFEST_CACHE} mục.
 */
public class FormComposer {

  public static final String TEMPLATES_BUCKET = "templates";
  public static final String DRAFT_MARK = "BẢN NHÁP";
  static final int MANIFEST_CACHE = 256;

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final ObjectStore store;
  private final List<FormRenderer> renderers;
  private final DocxMerger merger;
  private final ObjectMapper json;
  private final FormModelBuilder builder;
  private final Map<String, TemplateManifest> manifests = new ConcurrentHashMap<>();

  public FormComposer(
      ObjectStore store,
      List<FormRenderer> renderers,
      DocxMerger merger,
      ObjectMapper json,
      FormModelBuilder builder) {
    this.store = store;
    this.renderers = List.copyOf(renderers);
    this.merger = merger;
    this.json = json;
    this.builder = builder;
  }

  /**
   * Trộn dữ liệu của một biểu mẫu vào mẫu.
   *
   * @param canonicalJson tài liệu JSON chuẩn tắc của MOD-06
   * @param footer dòng tem in ở chân mọi trang
   */
  public byte[] compose(Template template, byte[] canonicalJson, RenderContext ctx, String footer) {
    byte[] docx = store.get(TEMPLATES_BUCKET, template.objectKey());
    if (!Arrays.equals(Hashes.sha256(docx), template.contentSha256())) {
      throw new RejectedDocument("Tệp mẫu " + template + " không khớp băm đã đăng ký.");
    }
    Map<String, Object> data = parse(canonicalJson);
    FormRenderer renderer = renderer(template);
    if (!template.hasManifest()) {
      return merger.merge(docx, renderer.fields(data), footer);
    }
    TemplateManifest manifest = manifest(template);
    Map<String, Object> prepared = renderer.prepare(data, manifest, ctx);
    MergeModel model =
        renderer.finish(builder.build(manifest, prepared, ctx), prepared, manifest, ctx);
    if (ctx.draft()) {
      model = model.withWatermark(DRAFT_MARK);
    }
    return merger.merge(docx, model, footer);
  }

  TemplateManifest manifest(Template template) {
    String key = template.id() + ":" + HexFormat.of().formatHex(template.manifestSha256());
    TemplateManifest cached = manifests.get(key);
    if (cached != null) {
      return cached;
    }
    byte[] raw = store.get(TEMPLATES_BUCKET, template.manifestKey());
    if (!Arrays.equals(Hashes.sha256(raw), template.manifestSha256())) {
      throw new RejectedDocument("Tệp kê khai của mẫu " + template + " không khớp băm đã đăng ký.");
    }
    TemplateManifest manifest = TemplateManifest.parse(raw, json);
    if (!manifest.formCode().equals(template.formCode())) {
      throw new RejectedDocument("Tệp kê khai không thuộc mẫu " + template + ".");
    }
    if (manifests.size() >= MANIFEST_CACHE) {
      manifests.clear();
    }
    manifests.put(key, manifest);
    return manifest;
  }

  FormRenderer renderer(Template template) {
    return renderers.stream()
        .filter(r -> r.formCode().equals(template.formCode()) && r.supports(template.versionNo()))
        .findFirst()
        .orElseGet(PlainFieldRenderer::new);
  }

  private Map<String, Object> parse(byte[] canonicalJson) {
    try {
      return json.readerFor(MAP)
          .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .readValue(canonicalJson);
    } catch (IOException e) {
      throw new RejectedDocument("Dữ liệu biểu mẫu không phải JSON hợp lệ.");
    }
  }
}
