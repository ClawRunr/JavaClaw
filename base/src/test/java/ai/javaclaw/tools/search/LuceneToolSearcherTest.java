package ai.javaclaw.tools.search;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneToolSearcherTest {

    @Test
    void returnsRelevantToolsForQuery() throws Exception {
        try (LuceneToolIndex index = new LuceneToolIndex(0.0f)) {
            String sessionId = "s1";
            index.indexTool(sessionId, ToolReference.builder().toolName("fileSystem")
                    .summary("Read, write, and edit local files in the workspace. Use for file operations, patches, and edits.").build());
            index.indexTool(sessionId, ToolReference.builder().toolName("webFetch")
                    .summary("Fetch a URL and extract readable content from web pages. Use for scraping and summarization.").build());
            index.indexTool(sessionId, ToolReference.builder().toolName("shell")
                    .summary("Execute shell commands to inspect the repository, run builds/tests, and automate development tasks.").build());

            var response = index.search(new ToolSearchRequest(sessionId, "edit a local file", 5, null));

            assertThat(response.toolReferences()).isNotEmpty();
            assertThat(response.toolReferences().getFirst().toolName()).isEqualTo("fileSystem");
        }
    }

    @Test
    void ranksMoreRelevantToolHigherBasedOnDescription() throws Exception {
        try (LuceneToolIndex index = new LuceneToolIndex(0.0f)) {
            String sessionId = "s2";
            index.indexTool(sessionId, ToolReference.builder().toolName("webFetch")
                    .summary("Fetch a URL and extract page contents. Good for reading articles when you already have a URL.").build());
            index.indexTool(sessionId, ToolReference.builder().toolName("braveSearch")
                    .summary("Search the web by keyword query and return results. Use when you do not have a URL yet.").build());

            var response = index.search(new ToolSearchRequest(sessionId, "search the web for spring ai docs", 5, null));

            assertThat(response.toolReferences()).isNotEmpty();
            assertThat(response.toolReferences().getFirst().toolName()).isEqualTo("braveSearch");
        }
    }

    @Test
    void honorsMaxResults() throws Exception {
        try (LuceneToolIndex index = new LuceneToolIndex(0.0f)) {
            String sessionId = "s3";
            for (int i = 0; i < 10; i++) {
                index.indexTool(sessionId, ToolReference.builder().toolName("tool-" + i)
                        .summary("tool number " + i + " for testing").build());
            }

            var response = index.search(new ToolSearchRequest(sessionId, "tool testing", 3, null));

            assertThat(response.toolReferences().size()).isLessThanOrEqualTo(3);
        }
    }
}
