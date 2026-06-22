package ai.javaclaw.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

class SkillsJarSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsSkillsFromJarResource() throws Exception {
        // GIVEN
        Path jarPath = tempDir.resolve("skills.jar");
        writeSkillJar(jarPath);

        // WHEN
        ToolCallback callback = getSkillsToolWithSkillsJar(jarPath, "META-INF/skills");

        // THEM
        String result = callback.call("{\"command\":\"jar-skill\"}");
        assertThat(result).contains("Base directory for this skill:");
        assertThat(result).contains("This skill came from a jar.");
    }

    private static ToolCallback getSkillsToolWithSkillsJar(Path jarPath, String path) throws IOException {
        ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader cl = new URLClassLoader(new URL[]{jarPath.toUri().toURL()}, originalCl)) {
            Thread.currentThread().setContextClassLoader(cl);

            return SkillsTool.builder()
                    .addSkillsResource(new ClassPathResource(path, cl))
                    .build();
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }
    }

    private static void writeSkillJar(Path jarPath) throws Exception {
        Files.createDirectories(jarPath.getParent());
        try (OutputStream out = Files.newOutputStream(jarPath);
             JarOutputStream jar = new JarOutputStream(out, manifest())) {
            JarEntry entry = new JarEntry("META-INF/skills/jar-skill/SKILL.md");
            jar.putNextEntry(entry);
            jar.write(skillMarkdown().getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static Manifest manifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        return manifest;
    }

    private static String skillMarkdown() {
        return """
                ---
                name: jar-skill
                description: Test skill packaged in a jar
                ---
                This skill came from a jar.
                """;
    }
}
