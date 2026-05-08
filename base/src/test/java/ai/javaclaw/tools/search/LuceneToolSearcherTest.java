package ai.javaclaw.tools.search;

import org.junit.jupiter.api.Test;
import org.springaicommunity.tool.search.ToolReference;
import org.springaicommunity.tool.search.ToolSearchRequest;
import org.springaicommunity.tool.searcher.LuceneToolSearcher;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneToolSearcherTest {

    @Test
    void returnsRelevantToolsForQuery() throws Exception {
        try (LuceneToolSearcher searcher = new LuceneToolSearcher(0.0f)) {
            String sessionId = "s1";
            searcher.indexTool(sessionId, new ToolReference("fileSystem", null,
                    "Read, write, and edit local files in the workspace. Use for file operations, patches, and edits."));
            searcher.indexTool(sessionId, new ToolReference("webFetch", null,
                    "Fetch a URL and extract readable content from web pages. Use for scraping and summarization."));
            searcher.indexTool(sessionId, new ToolReference("shell", null,
                    "Execute shell commands to inspect the repository, run builds/tests, and automate development tasks."));

            var response = searcher.search(new ToolSearchRequest(sessionId, "edit a local file", 5, null));

            assertThat(response.toolReferences()).isNotEmpty();
            assertThat(response.toolReferences().getFirst().toolName()).isEqualTo("fileSystem");
        }
    }

    @Test
    void ranksMoreRelevantToolHigherBasedOnDescription() throws Exception {
        try (LuceneToolSearcher searcher = new LuceneToolSearcher(0.0f)) {
            String sessionId = "s2";
            searcher.indexTool(sessionId, new ToolReference("webFetch", null,
                    "Fetch a URL and extract page contents. Good for reading articles when you already have a URL."));
            searcher.indexTool(sessionId, new ToolReference("braveSearch", null,
                    "Search the web by keyword query and return results. Use when you do not have a URL yet."));

            var response = searcher.search(new ToolSearchRequest(sessionId, "search the web for spring ai docs", 5, null));

            assertThat(response.toolReferences()).isNotEmpty();
            assertThat(response.toolReferences().getFirst().toolName()).isEqualTo("braveSearch");
        }
    }

    @Test
    void honorsMaxResults() throws Exception {
        try (LuceneToolSearcher searcher = new LuceneToolSearcher(0.0f)) {
            String sessionId = "s3";
            for (int i = 0; i < 10; i++) {
                searcher.indexTool(sessionId, new ToolReference("tool-" + i, null, "tool number " + i + " for testing"));
            }

            var response = searcher.search(new ToolSearchRequest(sessionId, "tool testing", 3, null));

            assertThat(response.toolReferences().size()).isLessThanOrEqualTo(3);
        }
    }
}
