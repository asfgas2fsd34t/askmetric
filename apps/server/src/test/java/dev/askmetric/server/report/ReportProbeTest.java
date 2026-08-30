package dev.askmetric.server.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportProbeTest {
    @Test
    void doesNotOverwriteAnExistingAnalysisReport(@TempDir Path directory) throws Exception {
        Path existingReport = directory.resolve("report.html");
        Files.writeString(existingReport, "existing immutable report");

        assertThatThrownBy(() -> ReportProbe.main(new String[]{existingReport.toString()}))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(existingReport)).isEqualTo("existing immutable report");
    }
}
