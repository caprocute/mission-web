package vn.viettel.okrkpi.config.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Danh sách cho phép của SVG trong UI Pack (CORE-08-10). */
class SvgSanitizerTest {

  private static java.util.List<String> check(String svg) {
    return SvgSanitizer.violations(svg.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void acceptsStaticDrawings() {
    assertThat(
            check(
                """
                <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 10 10">
                  <title>Logo</title>
                  <defs><linearGradient id="g"><stop offset="0" stop-color="#C8102E"/></linearGradient></defs>
                  <rect width="10" height="10" fill="url(#g)" style="stroke:#000"/>
                  <use xlink:href="#g"/>
                  <text x="1" y="9">Sở Y tế</text>
                </svg>
                """))
        .isEmpty();
  }

  @Test
  void rejectsScriptsHandlersAndExternalReferences() {
    String ns = "xmlns=\"http://www.w3.org/2000/svg\"";
    assertThat(check("<svg " + ns + " onload=\"alert(1)\"/>")).isNotEmpty();
    assertThat(check("<svg " + ns + "><script>alert(1)</script></svg>")).isNotEmpty();
    assertThat(check("<svg " + ns + "><foreignObject/></svg>")).isNotEmpty();
    assertThat(check("<svg " + ns + "><style>@import url(x)</style></svg>")).isNotEmpty();
    assertThat(check("<svg " + ns + "><a href=\"https://evil.example\"/></svg>")).isNotEmpty();
    assertThat(check("<svg " + ns + "><use href=\"https://evil.example/a.svg#x\"/></svg>"))
        .isNotEmpty();
    assertThat(check("<svg " + ns + "><rect fill=\"url(https://evil.example/x)\"/></svg>"))
        .isNotEmpty();
    assertThat(check("<svg " + ns + "><rect style=\"background:url(//x)\"/></svg>")).isNotEmpty();
    assertThat(check("<svg " + ns + "><rect fill=\"java&#x09;script:x\"/></svg>")).isNotEmpty();
    assertThat(check("<svg " + ns + "><?xml-stylesheet href=\"x.css\"?></svg>")).isNotEmpty();
    assertThat(
            check(
                "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                    + "<svg "
                    + ns
                    + "><text>&x;</text></svg>"))
        .isNotEmpty();
    assertThat(check("<html/>")).containsExactly("Tệp không phải SVG");
    assertThat(check("không phải xml")).isNotEmpty();
  }
}
