package ai.javaclaw.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class AutoConfigurationImportsTestSupport {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private AutoConfigurationImportsTestSupport() {
    }

    public static List<String> importedAutoConfigurations(Class<?> testClass) throws IOException {
        URL resource = testClass.getClassLoader().getResource(IMPORTS_RESOURCE);
        try {
            Path path = Path.of(resource.toURI());
            return Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}