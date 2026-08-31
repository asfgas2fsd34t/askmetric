package dev.askmetric.server.report;

import java.util.List;
import java.util.Objects;

public final class DeterministicHtmlReportRenderer {
    private final String templateVersion;

    public DeterministicHtmlReportRenderer(String templateVersion) {
        this.templateVersion = Objects.requireNonNull(templateVersion);
    }

    public String render(StructuredAnalysis analysis) {
        Objects.requireNonNull(analysis);
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s | AskMetric</title>
                  <style>
                    :root { color-scheme: light; font-family: Inter, "Segoe UI", sans-serif; color: #202124; background: #f6f7f8; }
                    * { box-sizing: border-box; }
                    body { margin: 0; background: #f6f7f8; }
                    header { padding: 40px max(24px, calc((100%% - 960px) / 2)); color: #fff; background: #202124; }
                    header p, header h1 { margin: 0; }
                    header p { color: #b8d9c4; font-size: 13px; text-transform: uppercase; }
                    header h1 { margin-top: 8px; font-size: 30px; letter-spacing: 0; }
                    main { width: min(960px, calc(100%% - 48px)); margin: 32px auto 56px; }
                    section { padding: 24px 0; border-bottom: 1px solid #d9dde1; }
                    h2 { margin: 0 0 16px; font-size: 18px; letter-spacing: 0; }
                    p { line-height: 1.6; }
                    dl { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; margin: 0; }
                    dt { color: #5f6368; font-size: 12px; }
                    dd { margin: 6px 0 0; font-weight: 600; overflow-wrap: anywhere; }
                    .metric { color: #12613b; font-size: 28px; }
                    table { width: 100%%; border-collapse: collapse; }
                    th, td { padding: 12px 10px; border-bottom: 1px solid #d9dde1; text-align: left; vertical-align: top; }
                    th { color: #5f6368; font-size: 12px; }
                    ul { margin: 0; padding-left: 20px; }
                    li + li { margin-top: 8px; }
                    footer { width: min(960px, calc(100%% - 48px)); margin: 0 auto 32px; color: #5f6368; font-size: 12px; }
                    @media (max-width: 640px) { dl { grid-template-columns: 1fr 1fr; } header { padding-top: 28px; padding-bottom: 28px; } }
                  </style>
                </head>
                <body>
                  <header>
                    <p>AskMetric analysis report</p>
                    <h1>%s</h1>
                  </header>
                  <main>
                    <section aria-labelledby="summary-heading">
                      <h2 id="summary-heading">Conclusion</h2>
                      <p>%s</p>
                    </section>
                    <section aria-labelledby="metric-heading">
                      <h2 id="metric-heading">Metric</h2>
                      <dl>
                        <div><dt>Name</dt><dd>%s</dd></div>
                        <div><dt>Value</dt><dd class="metric">%s</dd></div>
                        <div><dt>Period</dt><dd>%s</dd></div>
                        <div><dt>Definition</dt><dd>%s</dd></div>
                      </dl>
                    </section>
                    <section aria-labelledby="evidence-heading">
                      <h2 id="evidence-heading">Evidence snapshot</h2>
                      <table>
                        <thead><tr><th>Source</th><th>Observation</th></tr></thead>
                        <tbody>%s</tbody>
                      </table>
                    </section>
                    <section aria-labelledby="assumptions-heading">
                      <h2 id="assumptions-heading">Assumptions</h2>
                      <ul>%s</ul>
                    </section>
                    <section aria-labelledby="uncertainties-heading">
                      <h2 id="uncertainties-heading">Uncertainties</h2>
                      <ul>%s</ul>
                    </section>
                  </main>
                  <footer>Report %s · Data as of %s · Template %s</footer>
                </body>
                </html>
                """.formatted(
                escape(analysis.title()),
                escape(analysis.title()),
                escape(analysis.conclusion()),
                escape(analysis.metricName()),
                escape(analysis.metricValue()),
                escape(analysis.period()),
                escape(analysis.metricVersion()),
                evidenceRows(analysis.evidence()),
                listItems(analysis.assumptions()),
                listItems(analysis.uncertainties()),
                escape(analysis.reportId()),
                escape(analysis.dataAsOf()),
                escape(templateVersion));
    }

    private static String evidenceRows(List<StructuredAnalysis.Evidence> evidence) {
        return evidence.stream()
                .map(item -> "<tr><td>%s</td><td>%s</td></tr>".formatted(
                        escape(item.source()), escape(item.observation())))
                .reduce("", String::concat);
    }

    private static String listItems(List<String> items) {
        if (items.isEmpty()) {
            return "<li>None recorded.</li>";
        }
        return items.stream()
                .map(item -> "<li>%s</li>".formatted(escape(item)))
                .reduce("", String::concat);
    }

    private static String escape(String value) {
        var escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            switch (value.charAt(index)) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(value.charAt(index));
            }
        }
        return escaped.toString();
    }
}
