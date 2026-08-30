package dev.askmetric.server.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicHtmlReportRendererTest {
    @Test
    void rendersAuditableAnalysisAsDeterministicAnalysisReport() {
        var renderer = new DeterministicHtmlReportRenderer("t03-v1");

        String first = renderer.render(fixedAnalysis());
        String second = renderer.render(fixedAnalysis());

        assertThat(second).isEqualTo(first);
        assertThat(sha256(first)).isEqualTo("9fe842dc3f62f0b7125ec591a63dbbf6cd224adc76c70e61fd3c01cb054ce2d3");
        assertThat(first)
                .startsWith("<!doctype html>\n")
                .contains("AskMetric analysis report", "Evidence snapshot", "metric-v3")
                .doesNotContain("http://", "https://", "<script", "src=");
    }

    @Test
    void escapesUntrustedAuditableAnalysisText() {
        String html = new DeterministicHtmlReportRenderer("t03-v1").render(
                new StructuredAnalysis(
                        "report-unsafe",
                        "<img src=x onerror=alert(1)>",
                        "Revenue & retention",
                        "MRR < target",
                        "$1 & growing",
                        "2026-07",
                        "2026-07-31T23:59:59Z",
                        "metric-v3",
                        List.of(new StructuredAnalysis.Evidence("CRM <export>", "42 & verified")),
                        List.of("No <script>alert(1)</script>"),
                        List.of("Coverage > 95%")));

        assertThat(html)
                .contains("&lt;img src=x onerror=alert(1)&gt;")
                .contains("Revenue &amp; retention")
                .contains("No &lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<img", "<script>alert(1)</script>");
    }

    static StructuredAnalysis fixedAnalysis() {
        return new StructuredAnalysis(
                "report-mrr-2026-07",
                "July MRR review",
                "MRR declined because enterprise expansion slowed.",
                "Monthly recurring revenue",
                "$1,240,000",
                "2026-07",
                "2026-07-31T23:59:59Z",
                "metric-v3",
                List.of(
                        new StructuredAnalysis.Evidence("billing_snapshot", "MRR = $1,240,000"),
                        new StructuredAnalysis.Evidence("crm_snapshot", "Enterprise expansion = $82,000")),
                List.of("All amounts use the contract currency."),
                List.of("Late invoices may change the final value."));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
