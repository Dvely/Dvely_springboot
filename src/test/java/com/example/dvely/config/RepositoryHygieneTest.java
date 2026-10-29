package com.example.dvely.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;

/**
 * 로컬 설정·키 파일·OS 메타 파일은 저장소에 두지 않는다(#511 B13). .gitignore 는 이미 추적 중인 파일을 막지 못하므로
 * 인덱스를 직접 본다. git 체크아웃이 아닌 곳(소스 묶음 등)에서는 건너뛴다.
 */
class RepositoryHygieneTest {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "(^|/)\\.DS_Store$"
                    + "|\\.(pem|p12|pfx|key)$"
                    + "|(^|/)\\.env(\\.(?!example$)[^/]+)?$"
                    + "|(^|/)application-local\\.ya?ml$");

    @Test
    void noLocalOnlyFileIsTracked() throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(new File("."));
        assumeTrue(builder.getGitDir() != null, "git 체크아웃이 아니다");

        List<String> tracked = new ArrayList<>();
        try (Repository repository = builder.build()) {
            DirCache index = repository.readDirCache();
            for (int i = 0; i < index.getEntryCount(); i++) {
                String path = index.getEntry(i).getPathString();
                if (FORBIDDEN.matcher(path).find()) {
                    tracked.add(path);
                }
            }
            assertThat(index.getEntryCount()).isPositive();
        }
        assertThat(tracked).isEmpty();
    }
}
