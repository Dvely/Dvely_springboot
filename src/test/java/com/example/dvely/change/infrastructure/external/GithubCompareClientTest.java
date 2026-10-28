package com.example.dvely.change.infrastructure.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.change.application.port.out.GithubComparePort.Comparison;
import com.example.dvely.change.application.port.out.GithubComparePort.File;
import com.example.dvely.change.infrastructure.external.GithubCompareClient.CommitDetail;
import com.example.dvely.change.infrastructure.external.GithubCompareClient.CommitResponse;
import com.example.dvely.change.infrastructure.external.GithubCompareClient.CompareResponse;
import com.example.dvely.change.infrastructure.external.GithubCompareClient.FileResponse;
import com.example.dvely.change.infrastructure.external.GithubCompareClient.ParentResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class GithubCompareClientTest {

    @Test
    void mapsGithubFilesToTheFourStatusesAndLeavesUncountedFilesNull() {
        Comparison comparison = GithubCompareClient.toComparison(new CompareResponse(260, List.of(
                new CommitResponse("c1", new CommitDetail("feat: apply Qeploy Agent task t-1"),
                        List.of(new ParentResponse("p1")), null)),
                List.of(
                        new FileResponse("a.txt", "modified", 2, 1, "@@ -1 +1,2 @@", null),
                        new FileResponse("logo.png", "modified", 0, 0, null, null),
                        new FileResponse("new.md", "renamed", 0, 0, null, "old.md"),
                        new FileResponse("copy.txt", "copied", 3, 0, "@@", "src.txt"),
                        new FileResponse("empty.txt", "added", 0, 0, null, null))));

        assertThat(comparison.totalCommits()).isEqualTo(260);
        assertThat(comparison.commits()).singleElement().satisfies(c -> {
            assertThat(c.message()).isEqualTo("feat: apply Qeploy Agent task t-1");
            assertThat(c.parents()).containsExactly("p1");
        });
        assertThat(comparison.files()).containsExactly(
                new File("a.txt", "modified", 2, 1, null),
                new File("logo.png", "modified", null, null, null),
                new File("new.md", "renamed", 0, 0, "old.md"),
                new File("copy.txt", "added", 3, 0, null),
                new File("empty.txt", "added", 0, 0, null));
    }
}
