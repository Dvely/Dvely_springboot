package com.example.dvely.agent.infrastructure.git;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.springframework.stereotype.Component;

/**
 * 컨테이너 안 clone·fetch 를 대신한다(#523 PR-3). 서버가 원격에서 대상 커밋을 얕게 받아 그 트리를 tar 로 만들고,
 * 호출자가 그 tar 를 컨테이너에 넣는다. GitHub 자격은 서버 밖으로 나가지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ServerGitCheckout {

    private final ServerGitProperties properties;

    /**
     * @param prefix 트리를 넣을 디렉터리 이름(tar 안 첫 구간, 예: {@code app})
     * @param extras prefix 밖에 함께 넣을 작은 파일. 넣을 커밋(없으면 null)을 받아 만든다 — 트리와 그 base 표시를
     *               같은 tar 에 담아, 둘이 어긋난 채 컨테이너에 들어가는 일이 없게 한다
     * @param sink   tar 를 받아 컨테이너에 넣는다. 작업 디렉터리가 열려 있는 동안 불린다
     * @return 넣은 커밋. 대상 브랜치가 없거나 빈 저장소면 비어 있다(이때도 빈 prefix 디렉터리는 넣는다)
     * @throws ServerGitException 태그가 없거나, 받지 못했거나, 상한을 넘었다
     */
    public Optional<String> checkout(String remoteUrl, CredentialsProvider credentials, Target target, String prefix,
                                     Function<String, Map<String, byte[]>> extras, Consumer<InputStream> sink) {
        try (ServerGitWorkspace workspace = ServerGitWorkspace.create(Path.of(properties.workDir()), properties)) {
            RevCommit commit = resolve(workspace, remoteUrl, credentials, target);
            Path tar = workspace.repository().getDirectory().toPath().resolve("checkout.tar");
            try (OutputStream out = Files.newOutputStream(tar)) {
                Map<String, byte[]> files = extras.apply(commit == null ? null : commit.name());
                if (commit == null) {
                    TreeArchive.writeEmpty(prefix, files, out);
                } else {
                    TreeArchive.write(workspace.repository(), commit, prefix, files,
                            properties.maxFiles(), properties.maxBytes(), out);
                }
            }
            try (InputStream in = Files.newInputStream(tar)) {
                sink.accept(in);
            }
            return Optional.ofNullable(commit).map(RevCommit::name);
        } catch (IOException e) {
            throw new ServerGitException("작업 공간 파일을 옮기지 못했습니다", e);
        }
    }

    private static RevCommit resolve(ServerGitWorkspace workspace, String remoteUrl, CredentialsProvider credentials,
                                     Target target) {
        if (target.tag() != null) {
            return workspace.fetchTag(remoteUrl, credentials, target.tag())
                    .orElseThrow(() -> new ServerGitException("배포할 버전 태그를 저장소에서 찾지 못했습니다: " + target.tag(), null));
        }
        for (String branch : target.branches()) {
            Optional<RevCommit> commit = workspace.fetchBase(remoteUrl, credentials, branch);
            if (commit.isPresent()) {
                return commit.get();
            }
        }
        // 후보가 없으면 원격 기본 브랜치 — clone 이 받던 것과 같다. 빈 저장소면 null
        Optional<String> defaultBranch = workspace.defaultBranch(remoteUrl, credentials);
        if (defaultBranch.isEmpty() || target.branches().contains(defaultBranch.get())) {
            return null;
        }
        return workspace.fetchBase(remoteUrl, credentials, defaultBranch.get()).orElse(null);
    }

    /**
     * @param branches 앞에서부터 처음 있는 브랜치를 쓴다. 하나도 없으면 원격 기본 브랜치
     * @param tag      있으면 브랜치 대신 이 태그(특정 버전 배포)
     */
    public record Target(List<String> branches, String tag) {

        public static Target branchesThenDefault(String... branches) {
            return new Target(List.of(branches), null);
        }

        public static Target tag(String tag) {
            return new Target(List.of(), tag);
        }
    }
}
