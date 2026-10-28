package com.example.dvely.change.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.change.application.result.ResultScope.FileChange;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffFilesTest {

    @Test
    void readsStatusAndLineCountsPerFile() {
        List<FileChange> files = UnifiedDiffFiles.parse("""
                diff --git a/README.md b/README.md
                index 1..2 100644
                --- a/README.md
                +++ b/README.md
                @@ -1,2 +1,2 @@
                -old
                +new
                +more
                 same
                diff --git a/gone.txt b/gone.txt
                deleted file mode 100644
                --- a/gone.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -bye
                diff --git a/logo.png b/logo.png
                new file mode 100644
                Binary files /dev/null and b/logo.png differ
                diff --git "a/my file.txt" "b/my file.txt"
                new file mode 100644
                --- /dev/null
                +++ "b/my file.txt"
                @@ -0,0 +1 @@
                +x
                """);

        assertThat(files).extracting(FileChange::path).containsExactly("README.md", "gone.txt", "logo.png", "my file.txt");
        assertThat(files.get(0)).satisfies(f -> {
            assertThat(f.status()).isEqualTo("modified");
            assertThat(f.additions()).isEqualTo(2);
            assertThat(f.deletions()).isEqualTo(1);
        });
        assertThat(files.get(1).status()).isEqualTo("removed");
        assertThat(files.get(1).deletions()).isEqualTo(1);
        assertThat(files.get(2).status()).isEqualTo("added");
        assertThat(files.get(2).additions()).isNull();
        assertThat(files.get(3).additions()).isEqualTo(1);
    }

    @Test
    void anEmptyDiffHasNoFiles() {
        assertThat(UnifiedDiffFiles.parse(null)).isEmpty();
        assertThat(UnifiedDiffFiles.parse("")).isEmpty();
    }
}
