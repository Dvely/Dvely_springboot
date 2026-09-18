#!/usr/bin/env bash
#
# Runs the generated "Resolve publish dir" and "Verify build output" steps against real directory
# layouts.
#
# The point of these two steps is what happens when a build produced nothing. Before them, the
# custom-domain step's `mkdir -p` turned "no output" into "empty output" and the deploy published an
# empty site with every step green — the failure mode this checks is one where nothing looks wrong,
# so a string assertion about the workflow text is not enough.
#
# The Nuxt case is checked against a real `nuxi generate` layout: output in `.output/public` with
# `dist` as a symlink to it (measured on Nuxt 4.5.2). Depending on that symlink is exactly what this
# change stopped doing.
#
# Requires: node, ruby (for YAML), and a compiled classpath.
#
#   ./gradlew compileJava && scripts/verify-deploy-publish-dir.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="$ROOT/build/classes/java/main"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fails=0

if [ ! -d "$CLASSES" ]; then
  echo "컴파일된 클래스가 없습니다. 먼저 ./gradlew compileJava 를 실행하세요." >&2
  exit 2
fi

cat > "$WORK/DumpWorkflows.java" <<'JAVA'
import com.example.dvely.deployment.domain.value.PackageManager;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import java.nio.file.Files;
import java.nio.file.Path;

public class DumpWorkflows {
    public static void main(String[] args) throws Exception {
        for (String type : new String[]{"nuxt", "astro"}) {
            Files.writeString(Path.of(args[0], type + ".yml"),
                    DeployWorkflowTemplate.generate(type, null, PackageManager.NPM, "20"));
        }
    }
}
JAVA
java -cp "$CLASSES" "$WORK/DumpWorkflows.java" "$WORK" || exit 1

step() {   # step <framework> <step name>
  ruby -ryaml -e '
    d = YAML.load_file(ARGV[0])
    s = d["jobs"]["deploy"]["steps"].find { |x| x["name"] == ARGV[1] }
    abort("step not found: #{ARGV[1]}") unless s
    print s["run"]
  ' "$WORK/$1.yml" "$2"
}

check() {  # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then
    printf 'OK  %-42s %s\n' "$1" "$3"
  else
    printf '!!  %-42s 기대 %s, 실제 %s\n' "$1" "$2" "$3"
    fails=$((fails + 1))
  fi
}

# Runs both steps the way Actions would: the first writes to $GITHUB_OUTPUT, the second reads what
# it wrote. Echoing "dir=..." into a file is the whole contract between them.
run_steps() {   # run_steps <framework> <dir> -> prints "<resolved dir>|<verify exit code>"
  local fw="$1" dir="$2"
  ( cd "$dir" || exit 9
    export GITHUB_OUTPUT="$dir/.github_output"
    : > "$GITHUB_OUTPUT"
    step "$fw" "Resolve publish dir" > .resolve.sh
    bash .resolve.sh > /dev/null 2>&1
    local resolved
    resolved="$(sed -n 's/^dir=//p' "$GITHUB_OUTPUT")"
    step "$fw" "Verify build output" | sed "s|\${{ steps.publish.outputs.dir }}|$resolved|g" > .verify.sh
    bash .verify.sh > .verify.log 2>&1
    echo "$resolved|$?"
  )
}

echo "── Nuxt: 실제 generate 산출물 배치"
d="$WORK/nuxt-real"; mkdir -p "$d/.output/public"
printf '<html></html>\n' > "$d/.output/public/index.html"
printf 'body{}\n' > "$d/.output/public/style.css"
# nuxi 가 만들어 주는 호환용 심볼릭 링크. 이제 여기에 기대지 않는다는 것이 확인 대상이다.
ln -s "$d/.output/public" "$d/dist"
check "실물 경로로 확정된다" ".output/public|0" "$(run_steps nuxt "$d")"

echo
echo "── Nuxt: 심볼릭 링크가 없는 판본·설정"
d="$WORK/nuxt-nolink"; mkdir -p "$d/.output/public"
printf '<html></html>\n' > "$d/.output/public/index.html"
check "링크가 없어도 찾아낸다" ".output/public|0" "$(run_steps nuxt "$d")"

echo
echo "── Nuxt 2 계열: .output 이 없고 dist 가 실물"
d="$WORK/nuxt2"; mkdir -p "$d/dist"
printf '<html></html>\n' > "$d/dist/index.html"
check "예전 경로로 떨어진다" "./dist|0" "$(run_steps nuxt "$d")"

echo
echo "── 빌드가 아무것도 만들지 않았을 때"
# 이것이 이 변경의 핵심이다. 예전에는 여기서 빈 디렉터리가 만들어져 그대로 배포됐다.
d="$WORK/nothing"; mkdir -p "$d"
check "산출물이 없으면 멈춘다" "./dist|1" "$(run_steps astro "$d")"

echo
echo "── 디렉터리는 있는데 비었을 때"
d="$WORK/empty"; mkdir -p "$d/dist"
check "비어 있어도 멈춘다" "./dist|1" "$(run_steps astro "$d")"

echo
echo "── 정상 산출물"
d="$WORK/ok"; mkdir -p "$d/dist"
printf '<html></html>\n' > "$d/dist/index.html"
check "정상이면 통과한다" "./dist|0" "$(run_steps astro "$d")"

echo
[ "$fails" -eq 0 ] && echo "전체 통과" || echo "실패 ${fails}건"
exit $((fails > 0))
