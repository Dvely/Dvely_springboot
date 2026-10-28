package com.example.dvely.agent.infrastructure.git;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * 커밋의 트리를 컨테이너에 넣을 tar 로 쓴다(#523 PR-3, clone 대신). 컨테이너 안 git 으로 clone 하던 것과 같은
 * 작업 트리가 되도록 실행 비트·심볼릭 링크를 그대로 두고, 서브모듈(gitlink)은 clone 처럼 빈 디렉터리로 둔다.
 *
 * <p>디렉터리 항목을 모두 명시한다. tar 를 풀 때 암묵적으로 만들어지는 상위 디렉터리는 소유자 지정이 붙지 않아
 * root 소유가 될 수 있다 — 프리뷰 컨테이너는 node 로 돌고 DAC_OVERRIDE 가 없어 그 아래에 쓰지 못한다.</p>
 */
final class TreeArchive {

    private static final int DIRECTORY_MODE = 040755;
    private static final int FILE_MODE = 0100644;
    private static final int EXECUTABLE_MODE = 0100755;

    private TreeArchive() {
    }

    /**
     * @param prefix 모든 항목 앞에 붙일 디렉터리(예: {@code app}). 그 디렉터리 항목도 쓴다
     * @param extras prefix 밖에 함께 넣을 작은 파일(경로 → 내용)
     * @return 쓴 파일 수
     * @throws ServerGitException 파일 수·크기 상한을 넘었거나 허용되지 않는 경로가 있다
     */
    static int write(Repository repository, RevCommit commit, String prefix, Map<String, byte[]> extras,
                     int maxFiles, long maxBytes, OutputStream out) {
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out);
             TreeWalk walk = new TreeWalk(repository)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            for (Map.Entry<String, byte[]> extra : extras.entrySet()) {
                putFile(tar, extra.getKey(), FILE_MODE, extra.getValue());
            }
            Set<String> directories = new HashSet<>();
            putDirectory(tar, prefix, directories);

            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            int files = 0;
            long bytes = 0;
            while (walk.next()) {
                String path = walk.getPathString();
                requireSafe(path);
                if (isGitDirectory(path)) {
                    continue;
                }
                String name = prefix + "/" + path;
                ensureParents(tar, name, directories);
                FileMode mode = walk.getFileMode(0);
                if (mode == FileMode.GITLINK) {
                    putDirectory(tar, name, directories);   // clone 도 서브모듈 자리는 빈 디렉터리로 둔다
                    continue;
                }
                ObjectLoader blob = repository.open(walk.getObjectId(0));
                files++;
                bytes += blob.getSize();
                if (files > maxFiles || bytes > maxBytes) {
                    throw new ServerGitException("저장소가 너무 커서 작업 공간으로 가져오지 못했습니다(파일 상한 " + maxFiles
                            + "개, " + (maxBytes / 1024 / 1024) + " MB).", null);
                }
                if (mode == FileMode.SYMLINK) {
                    TarArchiveEntry link = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
                    link.setLinkName(new String(blob.getBytes(), StandardCharsets.UTF_8));
                    tar.putArchiveEntry(link);
                    tar.closeArchiveEntry();
                } else {
                    TarArchiveEntry entry = new TarArchiveEntry(name);
                    entry.setMode(mode == FileMode.EXECUTABLE_FILE ? EXECUTABLE_MODE : FILE_MODE);
                    entry.setSize(blob.getSize());
                    tar.putArchiveEntry(entry);
                    blob.copyTo(tar);
                    tar.closeArchiveEntry();
                }
            }
            tar.finish();
            return files;
        } catch (IOException e) {
            throw new ServerGitException("작업 공간 파일을 만들지 못했습니다", e);
        }
    }

    /** 받을 커밋이 없을 때(빈 저장소 등) — 빈 prefix 디렉터리와 extras 만 쓴다. */
    static void writeEmpty(String prefix, Map<String, byte[]> extras, OutputStream out) {
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, byte[]> extra : extras.entrySet()) {
                putFile(tar, extra.getKey(), FILE_MODE, extra.getValue());
            }
            putDirectory(tar, prefix, new HashSet<>());
            tar.finish();
        } catch (IOException e) {
            throw new ServerGitException("작업 공간 파일을 만들지 못했습니다", e);
        }
    }

    private static void putFile(TarArchiveOutputStream tar, String name, int mode, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setMode(mode);
        entry.setSize(content.length);
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
    }

    private static void ensureParents(TarArchiveOutputStream tar, String name, Set<String> directories)
            throws IOException {
        int slash = name.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        String parent = name.substring(0, slash);
        if (directories.contains(parent)) {
            return;
        }
        ensureParents(tar, parent, directories);
        putDirectory(tar, parent, directories);
    }

    private static void putDirectory(TarArchiveOutputStream tar, String name, Set<String> directories)
            throws IOException {
        if (!directories.add(name)) {
            return;
        }
        TarArchiveEntry entry = new TarArchiveEntry(name + "/");
        entry.setMode(DIRECTORY_MODE);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    /** 트리 경로는 원격이 정한다. 작업 공간 밖을 가리키거나 비정상인 이름은 받지 않는다. */
    private static void requireSafe(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\")) {
            throw new ServerGitException("허용되지 않는 경로입니다: " + path, null);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new ServerGitException("허용되지 않는 경로입니다: " + path, null);
            }
        }
    }

    /** 트리에 {@code .git} 이 들어 있어도 넣지 않는다 — 컨테이너 쪽 git 설정·훅이 될 수 있다. */
    private static boolean isGitDirectory(String path) {
        for (String segment : path.split("/")) {
            if (segment.toLowerCase(Locale.ROOT).equals(".git")) {
                return true;
            }
        }
        return false;
    }
}
