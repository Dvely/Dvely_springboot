#!/usr/bin/env bash
#
# Runs the generated deploy config steps against fake connected repositories and loads the config
# each one produces with Node.
#
# The unit tests pin what the workflow text says. This proves the shell inside it actually runs,
# that the file it writes is loadable as the module kind the project uses, and that everything else
# the user configured survives — none of which a string assertion can establish, and all of which
# would otherwise only be discovered by a failed deploy in someone's repository.
#
# Requires: node, ruby (for YAML), and a compiled classpath.
#
#   ./gradlew compileJava && scripts/verify-deploy-base-override.sh
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

# The values the deploy step would export. `base` has no trailing slash, `path` has one.
#
# They are set when the config is *loaded*, not when the step runs — the wrapped config reads them
# at build time, and in the real workflow that is the Build step, which is where the template
# attaches BASE_PATH/PUBLIC_URL/QEPLOY_BASE_PATH.
BASE_NOSLASH="/my-repo"
BASE_SLASH="/my-repo/"

# ── 워크플로 생성 ────────────────────────────────────────────────────────────
cat > "$WORK/DumpWorkflows.java" <<'JAVA'
import com.example.dvely.deployment.domain.value.PackageManager;
import com.example.dvely.deployment.infrastructure.workflow.DeployWorkflowTemplate;
import java.nio.file.Files;
import java.nio.file.Path;

public class DumpWorkflows {
    public static void main(String[] args) throws Exception {
        for (String type : new String[]{"vue-cli", "sveltekit", "gatsby", "astro", "nuxt"}) {
            Files.writeString(Path.of(args[0], type + ".yml"),
                    DeployWorkflowTemplate.generate(type, null, PackageManager.NPM, "20"));
        }
    }
}
JAVA
java -cp "$CLASSES" "$WORK/DumpWorkflows.java" "$WORK" || exit 1

extract_step() {   # extract_step <framework> <step name>
  ruby -ryaml -e '
    d = YAML.load_file(ARGV[0])
    s = d["jobs"]["deploy"]["steps"].find { |x| x["name"] == ARGV[1] }
    abort("step not found: #{ARGV[1]}") unless s
    print s["run"]
  ' "$WORK/$1.yml" "$2"
}

# Minimal stand-ins for packages a real repository would have installed. Without them the ESM
# configs cannot be imported at all, and the check would fail for a reason unrelated to wrapping.
stub_module() {   # stub_module <dir> <name> <source>
  local dir="$1/node_modules/$2"
  mkdir -p "$dir"
  printf '{"name":"%s","version":"0.0.0","type":"module","main":"index.js"}\n' "$2" > "$dir/package.json"
  printf '%s\n' "$3" > "$dir/index.js"
}

run_step() {   # run_step <framework> <step name> [base] [path]
  local dir="$WORK/$1"
  extract_step "$1" "$2" \
    | sed "s|\${{ steps.base.outputs.base }}|${3-$BASE_NOSLASH}|g; s|\${{ steps.base.outputs.path }}|${4-$BASE_SLASH}|g" \
    > "$dir/step.sh"
  ( cd "$dir" && bash step.sh > step.log 2>&1 ) \
    || { echo "!!  $1 스텝이 실패했습니다 — $dir/step.log"; cat "$dir/step.log"; fails=$((fails + 1)); }
}

check() {  # check <label> <expected> <actual>
  if [ "$2" = "$3" ]; then
    printf 'OK  %-30s %s\n' "$1" "$3"
  else
    printf '!!  %-30s 기대 %s, 실제 %s\n' "$1" "$2" "$3"
    fails=$((fails + 1))
  fi
}

# ── Vue CLI — CJS, 사용자가 다른 설정도 적어 둔 경우 ─────────────────────────
d="$WORK/vue-cli"; mkdir -p "$d"
printf '{"name":"theirs","dependencies":{"@vue/cli-service":"5"}}\n' > "$d/package.json"
cat > "$d/vue.config.js" <<'JS'
module.exports = { publicPath: '/their-own-path/', lintOnSave: false };
JS
run_step vue-cli "Configure Vue CLI public path"
out=$(cd "$d" && QEPLOY_BASE_PATH="$BASE_NOSLASH" BASE_PATH="$BASE_SLASH" \
  node -e "const c=require('./vue.config.js'); console.log(c.publicPath + '|' + c.lintOnSave)" 2>&1)
check "vue-cli publicPath|보존" "/my-repo/|false" "$out"

# ── SvelteKit — 확장자는 .js 지만 ESM (package.json 이 type: module) ─────────
d="$WORK/sveltekit"; mkdir -p "$d"
printf '{"name":"theirs","type":"module","devDependencies":{"@sveltejs/adapter-static":"3"}}\n' > "$d/package.json"
stub_module "$d" "@sveltejs/adapter-static" 'export default function adapter(opts) { return { name: "static", opts }; }'
cat > "$d/svelte.config.js" <<'JS'
import adapter from '@sveltejs/adapter-static';
export default { kit: { adapter: adapter({ fallback: '404.html' }), paths: { base: '/their-own-path' }, prerender: { entries: ['*'] } } };
JS
run_step sveltekit "Configure SvelteKit static adapter"
out=$(cd "$d" && QEPLOY_BASE_PATH="$BASE_NOSLASH" BASE_PATH="$BASE_SLASH" node --input-type=module -e "
  const c = (await import('./svelte.config.js')).default;
  console.log(c.kit.paths.base + '|' + c.kit.adapter.name + '|' + JSON.stringify(c.kit.prerender.entries));
" 2>&1 | tail -1)
check "sveltekit base|adapter|보존" "/my-repo|static|[\"*\"]" "$out"

# ── Gatsby — 커스텀 도메인이라 base 가 비어 있는 경우 ────────────────────────
# 예전 스텝은 여기서 통째로 빠져나가, 커밋된 pathPrefix 가 그대로 남아 자산이 전부 404 였다.
d="$WORK/gatsby"; mkdir -p "$d"
printf '{"name":"theirs","dependencies":{"gatsby":"5"}}\n' > "$d/package.json"
cat > "$d/gatsby-config.js" <<'JS'
module.exports = { pathPrefix: '/their-own-path', siteMetadata: { title: 'theirs' } };
JS
run_step gatsby "Configure Gatsby path prefix" "" "/"
out=$(cd "$d" && QEPLOY_BASE_PATH="" BASE_PATH="/" \
  node -e "const c=require('./gatsby-config.js'); console.log('[' + c.pathPrefix + ']|' + c.siteMetadata.title)" 2>&1)
check "gatsby 빈 base 도 확정|보존" "[]|theirs" "$out"

# ── Astro — .mjs ESM ─────────────────────────────────────────────────────────
d="$WORK/astro"; mkdir -p "$d"
printf '{"name":"theirs","type":"module","dependencies":{"astro":"4"}}\n' > "$d/package.json"
mkdir -p "$d/node_modules/astro"
printf '{"name":"astro","version":"0.0.0","type":"module","exports":{"./config":"./config.js"}}\n' > "$d/node_modules/astro/package.json"
printf 'export function defineConfig(c) { return c; }\n' > "$d/node_modules/astro/config.js"
cat > "$d/astro.config.mjs" <<'JS'
import { defineConfig } from 'astro/config';
export default defineConfig({ base: '/their-own-path', site: 'https://theirs.example' });
JS
run_step astro "Configure Astro base path"
out=$(cd "$d" && QEPLOY_BASE_PATH="$BASE_NOSLASH" BASE_PATH="$BASE_SLASH" node --input-type=module -e "
  const c = (await import('./astro.config.mjs')).default;
  console.log(c.base + '|' + c.output + '|' + c.site);
" 2>&1 | tail -1)
check "astro base|output|보존" "/my-repo|static|https://theirs.example" "$out"

# ── Nuxt — 여태 분기 자체가 없던 프레임워크 ─────────────────────────────────
d="$WORK/nuxt"; mkdir -p "$d"
printf '{"name":"theirs","type":"module","dependencies":{"nuxt":"3"}}\n' > "$d/package.json"
cat > "$d/nuxt.config.js" <<'JS'
export default { app: { baseURL: '/their-own-path/', head: { title: 'theirs' } }, ssr: false };
JS
run_step nuxt "Configure Nuxt base URL"
out=$(cd "$d" && QEPLOY_BASE_PATH="$BASE_NOSLASH" BASE_PATH="$BASE_SLASH" node --input-type=module -e "
  const c = (await import('./nuxt.config.js')).default;
  console.log(c.app.baseURL + '|' + c.app.head.title + '|' + c.ssr);
" 2>&1 | tail -1)
check "nuxt baseURL|보존" "/my-repo/|theirs|false" "$out"

echo
[ "$fails" -eq 0 ] && echo "전체 통과" || echo "실패 ${fails}건"
exit $((fails > 0))
