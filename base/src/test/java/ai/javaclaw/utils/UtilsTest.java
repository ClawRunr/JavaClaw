package ai.javaclaw.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UtilsTest {

    @TempDir
    Path dir;

    @Test
    void sha256MatchesTheStandardTestVector() {
        assertThat(Hashes.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Hashes.sha256("abc")).hasSize(32);
    }

    @Test
    void hashingTextWithANulHashesTheSameBytesAsFeedingTheDigestPieceByPiece() throws Exception {
        // ArgumentsHash used to update the digest with name, a 0 byte and the JSON separately
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("Write".getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update("{\"a\":\"é\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(Hashes.sha256Hex("Write\0{\"a\":\"é\"}")).isEqualTo(HexFormat.of().formatHex(digest.digest()));
    }

    @Test
    void parseObjectReturnsTheMembersInOrderOrNothing() {
        assertThat(JsonObjects.parseObject("{\"b\":1,\"a\":\"x\"}")).containsExactly(
                java.util.Map.entry("b", 1), java.util.Map.entry("a", "x"));
        assertThat(JsonObjects.parseObject("[1,2]")).isEmpty();
        assertThat(JsonObjects.parseObject("not json")).isEmpty();
        assertThat(JsonObjects.parseObject("  ")).isEmpty();
        assertThat(JsonObjects.parseObject(null)).isEmpty();
    }

    @Test
    void ensureDirectoryCreatesMissingParentsAndFailsUncheckedWhenItCannot() throws Exception {
        Path nested = dir.resolve("a/b/c");
        assertThat(FilePaths.ensureDirectory(nested)).isEqualTo(nested).isDirectory();

        Path file = Files.writeString(dir.resolve("file"), "x");
        assertThatThrownBy(() -> FilePaths.ensureDirectory(file.resolve("child")))
                .isInstanceOf(UncheckedIOException.class).hasMessageContaining("Failed to create directory");
    }

    @Test
    void summarizeUsesTheMessageOrTheExceptionName() {
        assertThat(Errors.summarize(new IllegalStateException("provider down"))).isEqualTo("provider down");
        assertThat(Errors.summarize(new IllegalStateException(" "))).isEqualTo("IllegalStateException");
        assertThat(Errors.summarize(new NullPointerException())).isEqualTo("NullPointerException");
    }
}
