package vn.viettel.okrkpi.docgen.infrastructure.render;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.docx4j.jaxb.Context;
import org.docx4j.model.datastorage.migration.VariablePrepare;
import org.docx4j.model.structure.HeaderFooterPolicy;
import org.docx4j.model.structure.SectionWrapper;
import org.docx4j.openpackaging.exceptions.InvalidFormatException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.openpackaging.parts.relationships.RelationshipsPart.AddPartBehaviour;
import org.docx4j.relationships.Relationship;
import org.docx4j.wml.FooterReference;
import org.docx4j.wml.HdrFtrRef;
import org.docx4j.wml.HpsMeasure;
import org.docx4j.wml.Jc;
import org.docx4j.wml.JcEnumeration;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.R;
import org.docx4j.wml.RPr;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Text;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.DocxMerger;
import vn.viettel.okrkpi.docgen.application.render.DocumentPorts.RejectedDocument;
import vn.viettel.okrkpi.docgen.application.render.MergeModel;

/**
 * Trộn dữ liệu vào mẫu DOCX bằng docx4j 11 (Apache 2.0): gom các đoạn chạy bị Word tách để chỗ giữ
 * {@code ${ten}} liền một khối ({@link VariablePrepare}), rồi thay giá trị đã thoát ký tự XML.
 *
 * <p>DV-11-04: tem phiên bản (BR-11-03) là một dòng chữ nhỏ căn giữa thêm vào cuối mọi chân trang
 * của mọi phần (chân trang mặc định, trang đầu, trang chẵn); phần chưa có chân trang mặc định được
 * tạo mới. Chữ in qua LibreOffice nên phông được nhúng như phần thân, tệp vẫn đạt PDF/A.
 */
public class Docx4jMerger implements DocxMerger {

  /** Cỡ chữ của tem, nửa điểm (8 pt). */
  static final long FOOTER_SIZE_HALF_POINTS = 16;

  @Override
  public byte[] merge(byte[] template, Map<String, String> fields) {
    return merge(template, fields, null);
  }

  @Override
  public byte[] merge(byte[] template, Map<String, String> fields, String footer) {
    try {
      WordprocessingMLPackage pkg =
          WordprocessingMLPackage.load(new ByteArrayInputStream(template));
      VariablePrepare.prepare(pkg);
      Map<String, String> escaped = new HashMap<>();
      fields.forEach((k, v) -> escaped.put(k, escape(v)));
      pkg.getMainDocumentPart().variableReplace(escaped);
      if (footer != null && !footer.isBlank()) {
        addFooter(pkg, footer);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream(template.length * 2);
      pkg.save(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new RejectedDocument("Không trộn được dữ liệu vào mẫu DOCX: " + e.getMessage());
    }
  }

  @Override
  public byte[] merge(byte[] template, MergeModel model, String footer) {
    try {
      WordprocessingMLPackage pkg =
          WordprocessingMLPackage.load(new ByteArrayInputStream(template));
      new ContentControlFiller(model).fill(pkg);
      if (footer != null && !footer.isBlank()) {
        addFooter(pkg, footer);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream(template.length * 2);
      pkg.save(out);
      return out.toByteArray();
    } catch (RejectedDocument e) {
      throw e;
    } catch (Exception e) {
      throw new RejectedDocument(
          "Không trộn được dữ liệu vào mẫu DOCX: " + e.getClass().getSimpleName());
    }
  }

  /** Thêm dòng tem vào mọi chân trang; một phần chân trang dùng chung chỉ được thêm một lần. */
  static void addFooter(WordprocessingMLPackage pkg, String text) throws InvalidFormatException {
    ObjectFactory factory = Context.getWmlObjectFactory();
    MainDocumentPart main = pkg.getMainDocumentPart();
    Set<FooterPart> stamped = Collections.newSetFromMap(new IdentityHashMap<>());
    int created = 0;
    for (SectionWrapper section : pkg.getDocumentModel().getSections()) {
      HeaderFooterPolicy policy = section.getHeaderFooterPolicy();
      FooterPart footer = policy == null ? null : policy.getDefaultFooter();
      if (footer == null) {
        footer = new FooterPart(new PartName("/word/footer-tem-" + (++created) + ".xml"));
        footer.setJaxbElement(factory.createFtr());
        Relationship rel = main.addTargetPart(footer, AddPartBehaviour.RENAME_IF_NAME_EXISTS);
        SectPr sectPr = section.getSectPr();
        if (sectPr == null) {
          sectPr = factory.createSectPr();
          main.getJaxbElement().getBody().setSectPr(sectPr);
        }
        FooterReference ref = factory.createFooterReference();
        ref.setId(rel.getId());
        ref.setType(HdrFtrRef.DEFAULT);
        sectPr.getEGHdrFtrReferences().add(ref);
      }
      for (FooterPart part :
          new FooterPart[] {
            footer,
            policy == null ? null : policy.getFirstFooter(),
            policy == null ? null : policy.getEvenFooter()
          }) {
        if (part != null && stamped.add(part)) {
          part.getContent().add(footerParagraph(factory, text));
        }
      }
    }
  }

  private static P footerParagraph(ObjectFactory factory, String text) {
    P paragraph = factory.createP();
    PPr ppr = factory.createPPr();
    Jc jc = factory.createJc();
    jc.setVal(JcEnumeration.CENTER);
    ppr.setJc(jc);
    paragraph.setPPr(ppr);
    R run = factory.createR();
    RPr rpr = factory.createRPr();
    HpsMeasure size = factory.createHpsMeasure();
    size.setVal(java.math.BigInteger.valueOf(FOOTER_SIZE_HALF_POINTS));
    rpr.setSz(size);
    run.setRPr(rpr);
    Text t = factory.createText();
    t.setValue(text);
    t.setSpace("preserve");
    run.getContent().add(factory.createRT(t));
    paragraph.getContent().add(run);
    return paragraph;
  }

  static String escape(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&apos;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }
}
