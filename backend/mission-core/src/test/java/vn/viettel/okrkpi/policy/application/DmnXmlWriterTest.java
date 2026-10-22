package vn.viettel.okrkpi.policy.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.flowable.dmn.api.DmnDecisionService;
import org.flowable.dmn.engine.DmnEngine;
import org.flowable.dmn.engine.DmnEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Column;
import vn.viettel.okrkpi.policy.domain.DecisionTableSpec.Row;

/**
 * {@code dmn_xml} sinh ra hợp lệ theo XSD DMN 1.4 của OMG và nạp lại được vào Flowable DMN cho cùng
 * kết quả với bộ đánh giá của MOD-05 trên bảng {@code xep-loai-dang} của TL-01 (CORE-05-05).
 * Flowable 7.2 chỉ nhận không gian tên tới DMN 1.3 nên bản nạp lại chỉ đổi không gian tên (các phần
 * tử dùng ở đây giống hệt giữa hai phiên bản); lược đồ DMN 1.4 được kiểm riêng.
 */
class DmnXmlWriterTest {

  /** XSD DMN 1.4 tải từ omg.org; DMNDI 1.3, DC, DI lấy từ thư viện Flowable. */
  private static final Path XSD =
      Path.of("src/test/java/vn/viettel/okrkpi/policy/application/dmn14/DMN14.xsd");

  private static final String DMN13 = "https://www.omg.org/spec/DMN/20191111/MODEL/";

  private static DmnEngine engine;

  @BeforeAll
  static void startEngine() {
    engine =
        DmnEngineConfiguration.createStandaloneInMemDmnEngineConfiguration()
            .setJdbcUrl("jdbc:h2:mem:dmn-core-05-05;DB_CLOSE_DELAY=1000")
            .setDatabaseSchemaUpdate("create-drop")
            .buildDmnEngine();
  }

  @AfterAll
  static void stopEngine() {
    engine.close();
  }

  static DecisionTableSpec xepLoaiDang() {
    List<Column> columns =
        List.of(
            new Column("diem", "Điểm", "number"),
            new Column("ty_le_O", "Tỷ lệ O", "number"),
            new Column("ty_le_vuot", "Tỷ lệ vượt", "number"),
            new Column("truot_then_chot_chu_quan", "Trượt then chốt chủ quan", "boolean"));
    return new DecisionTableSpec(
        "FIRST",
        columns,
        List.of(
            row(">= 90", "1", ">= 0.30", null, "HTXSNV"),
            row(">= 80", null, null, "false", "HTTNV"),
            row(">= 60", null, null, null, "HTNV"),
            row(null, null, null, null, "KHTNV")));
  }

  private static Row row(String diem, String o, String vuot, String truot, String level) {
    Map<String, String> in = new HashMap<>();
    if (diem != null) {
      in.put("diem", diem);
    }
    if (o != null) {
      in.put("ty_le_O", o);
    }
    if (vuot != null) {
      in.put("ty_le_vuot", vuot);
    }
    if (truot != null) {
      in.put("truot_then_chot_chu_quan", truot);
    }
    return new Row(in, level, "QĐ Điều 11 & PL 6", "Đạt {diem} điểm <mức> theo {dieu_khoan}.");
  }

  @Test
  void exportIsValidDmn14AndDeterministic() throws Exception {
    String xml =
        DmnXmlWriter.write("khoi-dang/quy-dinh-moi", "xep_loai", "QĐ Điều 11", xepLoaiDang());
    assertThat(xml)
        .contains("https://www.omg.org/spec/DMN/20211108/MODEL/")
        .contains("hitPolicy=\"FIRST\"")
        .contains("<text>>= 90</text>")
        .contains("&lt;mức>");
    validator().validate(new StreamSource(new StringReader(xml)));
    assertThat(
            DmnXmlWriter.write("khoi-dang/quy-dinh-moi", "xep_loai", "QĐ Điều 11", xepLoaiDang()))
        .isEqualTo(xml);
    assertThat(DmnXmlWriter.write("p", "k", "", xepLoaiDang())).doesNotContain("<description>");
  }

  @Test
  void flowableDmnGivesSameLevelsAsMod05() {
    DecisionTableSpec spec = xepLoaiDang();
    String xml = DmnXmlWriter.write("khoi-dang/quy-dinh-moi", "xep_loai", "QĐ Điều 11", spec);
    // Flowable 7.2 chỉ nhận DMN 1.3; nội dung bảng dùng chung cú pháp giữa 1.3 và 1.4.
    engine
        .getDmnRepositoryService()
        .createDeployment()
        .addString("xep_loai.dmn", xml.replace(DmnXmlWriter.DMN14, DMN13))
        .deploy();
    DmnDecisionService decisions = engine.getDmnDecisionService();
    for (Map<String, Object> ho :
        List.<Map<String, Object>>of(
            ho(90, 1, 0.30, false),
            ho(89.99, 1, 0.30, false),
            ho(92, 1, 0.29, false),
            ho(95, 1, 0.20, true),
            ho(80, 1, 0, false),
            ho(79.99, 1, 0, false),
            ho(60, 1, 0, true),
            ho(59.99, 1, 0, false))) {
      Object flowable =
          decisions
              .createExecuteDecisionBuilder()
              .decisionKey("xep-loai")
              .variables(ho)
              .executeWithSingleResult()
              .get(DmnXmlWriter.OUTPUT);
      assertThat(flowable).as(ho.toString()).isEqualTo(spec.evaluate(ho).orElseThrow());
    }
  }

  private static Map<String, Object> ho(double diem, double o, double vuot, boolean truot) {
    return new HashMap<>(
        Map.of("diem", diem, "ty_le_O", o, "ty_le_vuot", vuot, "truot_then_chot_chu_quan", truot));
  }

  private static Validator validator() throws SAXException {
    SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    factory.setResourceResolver(new FlowableXsd());
    return factory.newSchema(XSD.toFile()).newValidator();
  }

  /** Lấy DMNDI13.xsd, DC.xsd, DI.xsd từ {@code org/flowable/impl/dmn/parser/}. */
  private static final class FlowableXsd implements LSResourceResolver {
    @Override
    public LSInput resolveResource(
        String type, String ns, String publicId, String systemId, String baseUri) {
      String name = systemId.substring(systemId.lastIndexOf('/') + 1);
      InputStream in =
          DmnXmlWriterTest.class.getResourceAsStream("/org/flowable/impl/dmn/parser/" + name);
      if (in == null) {
        return null;
      }
      return new Input(publicId, systemId, in);
    }
  }

  private record Input(String publicId, String systemId, InputStream byteStream)
      implements LSInput {
    @Override
    public java.io.Reader getCharacterStream() {
      return null;
    }

    @Override
    public void setCharacterStream(java.io.Reader r) {}

    @Override
    public InputStream getByteStream() {
      return byteStream;
    }

    @Override
    public void setByteStream(InputStream s) {}

    @Override
    public String getStringData() {
      return null;
    }

    @Override
    public void setStringData(String s) {}

    @Override
    public String getSystemId() {
      return systemId;
    }

    @Override
    public void setSystemId(String s) {}

    @Override
    public String getPublicId() {
      return publicId;
    }

    @Override
    public void setPublicId(String s) {}

    @Override
    public String getBaseURI() {
      return null;
    }

    @Override
    public void setBaseURI(String s) {}

    @Override
    public String getEncoding() {
      return null;
    }

    @Override
    public void setEncoding(String s) {}

    @Override
    public boolean getCertifiedText() {
      return false;
    }

    @Override
    public void setCertifiedText(boolean b) {}
  }
}
