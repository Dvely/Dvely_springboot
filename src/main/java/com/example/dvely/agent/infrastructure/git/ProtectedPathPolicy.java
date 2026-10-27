package com.example.dvely.agent.infrastructure.git;

import com.example.dvely.agent.infrastructure.git.ServerGitWorkspace.ChangedPath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 바뀐 경로 중 사람이 확인해야 하는 것(#508)을 고른다. 판정 근거는 서버가 만든 트리다.
 *
 * <p>보호 경로는 {@code .github} 자체와 {@code .github/workflows/**}·{@code .github/actions/**} 다.
 * 대소문자·{@code ./}·{@code //}·역슬래시를 정규화한 뒤 비교한다.</p>
 *
 * <p>Qeploy 가 만든 파일과 <b>바이트까지 같은</b> 변경은 승인 없이 통과한다. 비교 값은 git blob id(내용의
 * 해시)이고 허용목록은 서버에만 있다 — 리소스 {@code git/protected-path-allowlist.txt} 와, 호출자가 서버에서
 * 다시 생성해 넘기는 값(예: 배포 workflow). 보호 경로의 삭제는 허용목록과 무관하게 승인을 받는다.</p>
 */
@Component
public class ProtectedPathPolicy {

    static final String ALLOWLIST_RESOURCE = "git/protected-path-allowlist.txt";
    private static final List<String> GUARDED_PREFIXES = List.of(".github/workflows", ".github/actions");

    private final Set<ObjectId> bundledAllowlist;

    public ProtectedPathPolicy() {
        this(loadBundledAllowlist());
    }

    ProtectedPathPolicy(Set<ObjectId> bundledAllowlist) {
        this.bundledAllowlist = Set.copyOf(bundledAllowlist);
    }

    /** 승인이 필요한 경로 목록. 비어 있으면 그대로 반영해도 된다. */
    public List<String> requiringApproval(Collection<ChangedPath> changes, Set<ObjectId> serverGenerated) {
        return changes.stream()
                .filter(change -> isGuarded(change.path()))
                .filter(change -> change.newBlob() == null
                        || !(bundledAllowlist.contains(change.newBlob()) || serverGenerated.contains(change.newBlob())))
                .map(ChangedPath::path)
                .distinct()
                .toList();
    }

    /** 서버가 생성한 내용의 blob id. 허용목록 비교에 쓴다. */
    public static ObjectId blobIdOf(String content) {
        try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter()) {
            return formatter.idFor(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
        }
    }

    static boolean isGuarded(String raw) {
        String path = normalize(raw);
        if (path.isEmpty()) {
            return false;
        }
        return path.equals(".github") || GUARDED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static String normalize(String raw) {
        String path = raw.trim().replace('\\', '/');
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        while (path.startsWith("./") || path.startsWith("/")) {
            path = path.substring(path.startsWith("./") ? 2 : 1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.toLowerCase(Locale.ROOT);
    }

    private static Set<ObjectId> loadBundledAllowlist() {
        ClassPathResource resource = new ClassPathResource(ALLOWLIST_RESOURCE);
        if (!resource.exists()) {
            return Set.of();
        }
        try (InputStream in = resource.getInputStream()) {
            Set<ObjectId> ids = new HashSet<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                ids.add(ObjectId.fromString(trimmed.split("\\s+")[0]));
            }
            return ids;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
