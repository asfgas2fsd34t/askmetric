package dev.askmetric.server.report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class ReportProbe {
    private ReportProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: ReportProbe <output.html>");
        }

        String html = new DeterministicHtmlReportRenderer("t03-v1").render(fixedAnalysis());
        Path output = Path.of(args[0]);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, html, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println(sha256(html));
    }

    private static StructuredAnalysis fixedAnalysis() {
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
