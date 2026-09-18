package com.example.dvely.deployment.infrastructure.workflow;

import com.example.dvely.deployment.domain.value.PackageManager;

public class DeployWorkflowTemplate {

    private static final String WORKFLOW_FILE = "qeploy-deploy.yml";
    private static final String LEGACY_WORKFLOW_FILE = "dvely-deploy.yml";
    private static final String WORKFLOW_NAME = "Qeploy Deploy to GitHub Pages";
    private static final String LEGACY_WORKFLOW_NAME = "Dvely Deploy to GitHub Pages";

    /**
     * 발행 디렉터리는 러너에서 확정된다. 빌드가 어디에 냈는지는 설정과 프레임워크
     * 판본에 달려 있어, 워크플로를 만드는 시점에는 확실히 알 수 없다.
     */
    private static final String OUT_REF = "${{ steps.publish.outputs.dir }}";

    public static String fileName() {
        return WORKFLOW_FILE;
    }

    public static String legacyFileName() {
        return LEGACY_WORKFLOW_FILE;
    }

    /**
     * workflow_run 웹훅이 우리 배포 워크플로의 것인지 판별한다.
     *
     * <p>런타임에 실제로 도착하는 {@code workflow_run.name} 은 파일의 {@code name:} 이 아니라
     * <b>{@code run-name:} 이 적용된 값</b>이다. 아래 {@link #generate} 가 run-name 을 넣는
     * 순간부터 그렇게 됐는데 이 판별은 여전히 파일 이름만 보고 있었다. 그래서 항상 false 가 되어
     * 핸들러가 가드에서 조용히 빠져나갔고, 뒤따르는 correlationId 매칭에는 도달조차 못 했다.
     *
     * <p>증상이 고약하다 — GitHub 에서는 workflow 도 Pages 도 성공하고 사이트가 실제로 뜨는데,
     * 우리 배포 이력만 IN_PROGRESS 에 영원히 멈춘다. 경고 로그도 남지 않는다(2026-08-18 운영에서
     * historyId=1 이 그렇게 멈췄다 — 사이트는 200, DB 는 IN_PROGRESS).
     *
     * <p>과거 실행분은 run-name 이 없어 파일 이름으로 오므로 둘 다 받는다.
     */
    public static boolean isQeployWorkflowName(String workflowName) {
        return WORKFLOW_NAME.equalsIgnoreCase(workflowName)
                || LEGACY_WORKFLOW_NAME.equalsIgnoreCase(workflowName)
                || correlationIdFromRunTitle(workflowName) != null;
    }

    public static String runTitle(String correlationId) {
        return "Qeploy deployment " + correlationId;
    }

    public static String correlationIdFromRunTitle(String runTitle) {
        String prefix = "Qeploy deployment ";
        return runTitle != null && runTitle.startsWith(prefix)
                ? runTitle.substring(prefix.length()).trim()
                : null;
    }

    public static String generate(String templateType, String publishDir,
                                  PackageManager pm, String nodeVersion) {
        String type   = templateType == null ? "" : templateType.toLowerCase();
        String outDir = publishDir != null ? publishDir : resolvePublishDir(type);
        // 명시적으로 지정된 발행 경로는 사용자의 선택이므로 건드리지 않는다.
        boolean nuxt  = publishDir == null
                && ("nuxt".equals(type) || "nuxtjs".equals(type) || "nuxt3".equals(type));

        StringBuilder w = new StringBuilder();

        // ── 헤더 ─────────────────────────────────────────────────────────────
        w.append("name: ").append(WORKFLOW_NAME).append("\n\n");
        w.append("run-name: Qeploy deployment ${{ inputs.deployment_id }}\n\n");
        w.append("on:\n");
        w.append("  workflow_dispatch:\n");
        w.append("    inputs:\n");
        w.append("      deployment_id:\n");
        w.append("        description: 'Qeploy deployment correlation ID'\n");
        w.append("        required: false\n");
        w.append("        type: string\n");
        w.append("      checkout_ref:\n");
        w.append("        description: 'Git ref to checkout and build'\n");
        w.append("        required: false\n");
        w.append("        type: string\n\n");
        w.append("permissions:\n");
        w.append("  contents: write\n");
        w.append("  pages: write\n\n");
        w.append("jobs:\n");
        w.append("  deploy:\n");
        w.append("    runs-on: ubuntu-latest\n");
        w.append("    steps:\n");

        // ── 1. Checkout ───────────────────────────────────────────────────────
        w.append("      - name: Checkout\n");
        w.append("        uses: actions/checkout@v4\n");
        w.append("        with:\n");
        w.append("          ref: ${{ inputs.checkout_ref || github.ref_name }}\n\n");

        // ── 2. 런타임 설정 ────────────────────────────────────────────────────
        // 정적 사이트(package.json 없음)는 Node 도 의존성도 빌드도 필요 없다. setup-node 는
        // cache 를 켜면 lock 파일을 요구해 그 자리에서 죽고, npm ci 는 package.json 을 찾다 죽는다.
        boolean staticSite = "static".equals(type);
        if (!staticSite) {
            w.append(runtimeSetupSteps(pm, nodeVersion));
        }

        // ── 3. base path 해석 ─────────────────────────────────────────────────
        // path  : trailing slash 포함 (Vite --base, PUBLIC_URL)
        // base  : trailing slash 없음 (Next.js basePath, SvelteKit paths.base)
        w.append("      - name: Resolve base path\n");
        w.append("        id: base\n");
        w.append("        run: |\n");
        w.append("          REPO=\"${{ github.event.repository.name }}\"\n");
        w.append("          OWNER=\"${{ github.repository_owner }}\"\n");
        w.append("          PAGES_JSON=$(curl -fsS \\\n");
        w.append("            -H \"Authorization: Bearer ${{ github.token }}\" \\\n");
        w.append("            -H \"Accept: application/vnd.github+json\" \\\n");
        w.append("            -H \"X-GitHub-Api-Version: 2022-11-28\" \\\n");
        w.append("            \"https://api.github.com/repos/${GITHUB_REPOSITORY}/pages\" || true)\n");
        w.append("          CNAME=$(printf '%s' \"$PAGES_JSON\" | node -e \"let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{try{const j=JSON.parse(d||'{}');process.stdout.write(j.cname||'')}catch{}})\")\n");
        w.append("          echo \"cname=${CNAME}\" >> $GITHUB_OUTPUT\n");
        w.append("          if [ \"$REPO\" = \"${OWNER}.github.io\" ] || [ -n \"$CNAME\" ]; then\n");
        w.append("            echo \"path=/\" >> $GITHUB_OUTPUT\n");
        w.append("            echo \"base=\" >> $GITHUB_OUTPUT\n");
        w.append("          else\n");
        w.append("            echo \"path=/${REPO}/\" >> $GITHUB_OUTPUT\n");
        w.append("            echo \"base=/${REPO}\" >> $GITHUB_OUTPUT\n");
        w.append("          fi\n\n");

        // ── 4~6. 프레임워크 설정 · 설치 · 빌드 ───────────────────────────────
        // 정적 사이트는 셋 다 건너뛴다 — 올릴 파일이 이미 리포지토리에 있다.
        if (!staticSite) {
            String configStep = resolveConfigStep(type, pm);
            if (!configStep.isEmpty()) {
                w.append(configStep);
            }

            w.append("      - name: Install dependencies\n");
            w.append("        run: ").append(pm.installCommand()).append("\n\n");

            w.append("      - name: Build\n");
            w.append("        run: ").append(resolveBuildCommand(type, pm)).append("\n");
            w.append("        env:\n");
            w.append("          BASE_PATH: ${{ steps.base.outputs.path }}\n");
            w.append("          PUBLIC_URL: ${{ steps.base.outputs.path }}\n");
            // Next.js 의 basePath 는 trailing slash 가 없어야 한다. 위 두 값은 slash 를 포함하므로
            // 그대로 쓰면 안 되고, 감싼 config 가 이 값을 읽는다.
            w.append("          QEPLOY_BASE_PATH: ${{ steps.base.outputs.base }}\n\n");
        }

        // ── 6.5 발행 디렉터리 확정 + 산출물 검증 ─────────────────────────────
        // 디렉터리를 러너에서 정하는 이유는 Nuxt 다. `nuxi generate` 는 산출물을
        // `.output/public` 에 내고 `dist` 는 호환용으로 만들어 주는 심볼릭 링크다(Nuxt 4.5
        // 실측). 링크는 판본·설정에 따라 없을 수도 있고, 발행 액션이 링크를 따라간다는 보장도
        // 없다. 실물을 가리키면 그 두 불확실성이 함께 사라진다.
        w.append("      - name: Resolve publish dir\n");
        w.append("        id: publish\n");
        w.append("        run: |\n");
        w.append("          DIR=\"").append(outDir).append("\"\n");
        if (nuxt) {
            w.append("          if [ -d \".output/public\" ]; then DIR=\".output/public\"; fi\n");
        }
        w.append("          echo \"dir=$DIR\" >> $GITHUB_OUTPUT\n");
        w.append("          echo \"발행 대상: $DIR\"\n\n");

        // 산출물 검증은 아래 custom domain 스텝의 `mkdir -p` 보다 <b>앞</b>이어야 한다.
        // 그 mkdir 은 CNAME 을 쓰려고 디렉터리를 보장하는데, 부수효과로 "산출물이 없다" 를
        // "산출물이 비었다" 로 바꾼다. 그러면 실패한 스텝 하나 없이 빈 사이트가 배포되고,
        // 사용자는 어디를 볼지 알 수 없다 — 조용한 실패가 시끄러운 실패보다 나쁘다.
        w.append("      - name: Verify build output\n");
        w.append("        run: |\n");
        w.append("          DIR=\"").append(OUT_REF).append("\"\n");
        w.append("          if [ ! -d \"$DIR\" ]; then\n");
        w.append("            echo \"::error::빌드 산출물 디렉터리가 없습니다: $DIR\"\n");
        w.append("            echo \"빌드가 산출물을 다른 경로에 냈거나, 아무것도 만들지 않았습니다.\"\n");
        w.append("            exit 1\n");
        w.append("          fi\n");
        w.append("          if [ -z \"$(ls -A \"$DIR\" 2>/dev/null)\" ]; then\n");
        w.append("            echo \"::error::빌드 산출물 디렉터리가 비어 있습니다: $DIR\"\n");
        w.append("            exit 1\n");
        w.append("          fi\n\n");

        // ── 7. SPA 라우팅 404 대응 (빌드 결과물 있을 때만) ───────────────────
        w.append("      - name: Copy index.html to 404.html\n");
        w.append("        run: |\n");
        w.append("          [ -f ").append(OUT_REF).append("/index.html ]");
        w.append(" && cp ").append(OUT_REF).append("/index.html ").append(OUT_REF).append("/404.html || true\n\n");

        // ── 8. 기존 custom domain 보존 ───────────────────────────────────────
        w.append(preserveCustomDomainStep(OUT_REF));

        // ── 9. gh-pages 배포 ──────────────────────────────────────────────────
        w.append("      - name: Deploy to gh-pages\n");
        w.append("        uses: peaceiris/actions-gh-pages@v4\n");
        w.append("        with:\n");
        w.append("          github_token: ${{ secrets.GITHUB_TOKEN }}\n");
        w.append("          publish_dir: ").append(OUT_REF).append("\n");

        return w.toString();
    }

    // ── 런타임 setup 스텝 ─────────────────────────────────────────────────────

    private static String runtimeSetupSteps(PackageManager pm, String nodeVersion) {
        return switch (pm) {
            case NPM  -> nodeSetupStep("npm",  nodeVersion);
            case YARN -> nodeSetupStep("yarn", nodeVersion);
            case PNPM -> pnpmSetupStep() + nodeSetupStep("pnpm", nodeVersion);
            case BUN  -> bunSetupStep();
        };
    }

    private static String nodeSetupStep(String cache, String nodeVersion) {
        return "      - name: Setup Node.js\n"
             + "        uses: actions/setup-node@v4\n"
             + "        with:\n"
             + "          node-version: '" + nodeVersion + "'\n"
             + "          cache: '" + cache + "'\n"
             + "\n";
    }

    private static String pnpmSetupStep() {
        return "      - name: Setup pnpm\n"
             + "        uses: pnpm/action-setup@v4\n"
             + "        with:\n"
             + "          version: latest\n"
             + "\n";
    }

    private static String bunSetupStep() {
        return "      - name: Setup Bun\n"
             + "        uses: oven-sh/setup-bun@v2\n"
             + "        with:\n"
             + "          bun-version: latest\n"
             + "\n";
    }

    // ── 프레임워크별 사전 설정 스텝 ───────────────────────────────────────────

    private static String resolveConfigStep(String type, PackageManager pm) {
        return switch (type) {
            case "cra", "create-react-app" -> craConfigStep();
            case "nextjs", "next"          -> nextjsConfigStep();
            case "vue-cli", "vue_cli"      -> vueCliConfigStep();
            case "svelte", "sveltekit"     -> sveltekitConfigStep(pm);
            case "gatsby"                  -> gatsbyConfigStep();
            case "astro"                   -> astroConfigStep();
            case "nuxt", "nuxtjs", "nuxt3" -> nuxtConfigStep();
            default                        -> "";
        };
    }

    /**
     * CRA: package.json 의 homepage 필드를 base path 로 덮어씀.
     * homepage: "." 처럼 상대경로로 설정된 경우 PUBLIC_URL env 만으로는 불안정하므로
     * 빌드 전 직접 수정하여 CRA 가 올바른 절대 경로로 빌드하도록 강제.
     */
    private static String craConfigStep() {
        return "      - name: Configure CRA homepage\n"
             + "        run: |\n"
             + "          BASE=\"${{ steps.base.outputs.path }}\"\n"
             + "          node -e \"const fs=require('fs');const p=JSON.parse(fs.readFileSync('package.json','utf8'));p.homepage='$BASE';fs.writeFileSync('package.json',JSON.stringify(p,null,2));\"\n"
             + "          echo \"package.json homepage 설정 완료: $BASE\"\n"
             + "\n";
    }

    /**
     * Next.js: 정적 export 설정을 배포 시점에 확정한다.
     *
     * 예전에는 config 가 없을 때만 만들고, 있으면 경고만 남기고 넘어갔다. 그런데
     * {@code create-next-app} 은 스캐폴딩 때 {@code next.config.mjs} 를 **항상** 만든다. 그래서
     * 생성 분기는 현실에서 거의 발화하지 않고, 커밋된 config 가 유일한 진실이 된다. 그 config 에는
     * {@code output: 'export'} 가 없으므로 {@code next build} 는 {@code .next} 만 만들고
     * {@code ./out} 은 생기지 않는다 — publish 스텝이 그 디렉터리를 찾다 실패한다. 즉 자산 404
     * 이전에 **배포 자체가 끝까지 가지 못한다.**
     *
     * basePath 도 마찬가지다. 배포 대상 URL 이 결정하는 값이라 빌드 시점에만 알 수 있는데
     * (커스텀 도메인이 붙으면 {@code /}, 아니면 {@code /{repo}}), 커밋된 값은 그것을 알 수 없다.
     *
     * 그래서 **덮어쓰지 않고 감싼다.** 사용자의 config 를 그대로 두고 옆으로 옮긴 뒤, 그것을
     * 불러와 우리가 소유하는 세 필드만 얹은 config 를 새로 쓴다. 사용자가 적은 다른 설정
     * (redirects, env, webpack 등)은 그대로 살아남는다. 파싱하지 않으므로 config 가 JS 든 MJS 든
     * TS 든 형식에 기대지 않는다 — Next 자신이 읽게 두고 결과 객체에만 손댄다.
     *
     * 세 필드를 우리가 소유하는 근거는 각각 다르다. {@code output} 과 {@code images.unoptimized}
     * 는 GitHub Pages 가 정적 호스팅이라는 사실에서 나오고, {@code basePath} 는 배포 URL 에서
     * 나온다. 셋 다 사용자가 정할 수 있는 값이 아니다.
     */
    private static String nextjsConfigStep() {
        return "      - name: Configure Next.js for static export\n"
             + "        run: |\n"
             + "          BASE=\"${{ steps.base.outputs.base }}\"\n"
             + "          USER_CONFIG=\"\"\n"
             + "          for f in next.config.js next.config.mjs next.config.ts; do\n"
             + "            if [ -f \"$f\" ]; then USER_CONFIG=\"$f\"; break; fi\n"
             + "          done\n"
             + "          if [ -z \"$USER_CONFIG\" ]; then\n"
             + "            {\n"
             + "              echo \"/** @type {import('next').NextConfig} */\"\n"
             + "              echo \"module.exports = {\"\n"
             + "              echo \"  output: 'export',\"\n"
             + "              echo \"  basePath: '$BASE',\"\n"
             + "              echo \"  images: { unoptimized: true },\"\n"
             + "              echo \"};\"\n"
             + "            } > next.config.js\n"
             + "            echo \"next.config.js 생성 완료 (output: export, basePath: '$BASE')\"\n"
             + "            exit 0\n"
             + "          fi\n"
             + "          # 사용자의 config 는 지우지 않고 옆으로 옮긴다. 우리 config 가 이것을\n"
             + "          # 불러와 감싸므로, 사용자가 적은 다른 설정은 그대로 살아남는다.\n"
             + "          EXT=\"${USER_CONFIG##*.}\"\n"
             + "          WRAPPED=\"next.config.qeploy-user.$EXT\"\n"
             + "          mv \"$USER_CONFIG\" \"$WRAPPED\"\n"
             + "          # ESM 은 확장자를 반드시 적어야 하고, TypeScript 는 반대로 '.ts' 확장자\n"
             + "          # import 를 허용하지 않는다(allowImportingTsExtensions 없이는 컴파일 오류).\n"
             + "          if [ \"$EXT\" = \"ts\" ]; then IMPORT_PATH=\"./${WRAPPED%.ts}\"; else IMPORT_PATH=\"./$WRAPPED\"; fi\n"
             + "          if [ \"$EXT\" = \"mjs\" ] || [ \"$EXT\" = \"ts\" ]; then\n"
             + "            {\n"
             + "              echo \"import userConfig from '$IMPORT_PATH';\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"export default {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  output: 'export',\"\n"
             + "              echo \"  basePath: base,\"\n"
             + "              echo \"  images: { ...(resolved.images ?? {}), unoptimized: true },\"\n"
             + "              echo \"};\"\n"
             + "            } > \"next.config.$EXT\"\n"
             + "          else\n"
             + "            {\n"
             + "              echo \"const userConfig = require('./$WRAPPED');\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"module.exports = {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  output: 'export',\"\n"
             + "              echo \"  basePath: base,\"\n"
             + "              echo \"  images: { ...(resolved.images ?? {}), unoptimized: true },\"\n"
             + "              echo \"};\"\n"
             + "            } > \"next.config.$EXT\"\n"
             + "          fi\n"
             + "          echo \"$USER_CONFIG 를 $WRAPPED 로 옮기고 감쌌습니다 (output: export, basePath: '$BASE')\"\n\n";
    }

    /**
     * Vue CLI: publicPath 설정을 위해 vue.config.js 가 없으면 자동 생성.
     * Vue CLI 는 PUBLIC_URL 을 인식하지 않으므로 vue.config.js 에서 직접 지정해야 함.
     */
    /**
     * Vue CLI: publicPath 를 배포 시점 값으로 확정한다.
     *
     * publicPath 는 trailing slash 를 포함해야 한다 — Vue CLI 가 그대로 자산 URL 앞에 붙이므로,
     * slash 가 없으면 "/repoassets/app.js" 가 된다.
     */
    private static String vueCliConfigStep() {
        return "      - name: Configure Vue CLI public path\n"
             + "        run: |\n"
             + "          BASE=\"${{ steps.base.outputs.path }}\"\n"
             + "          USER_CONFIG=\"\"\n"
             + "          for f in vue.config.js vue.config.ts; do\n"
             + "            if [ -f \"$f\" ]; then USER_CONFIG=\"$f\"; break; fi\n"
             + "          done\n"
             + "          if [ -z \"$USER_CONFIG\" ]; then\n"
             + "            {\n"
             + "              echo \"module.exports = {\"\n"
             + "              echo \"  publicPath: '$BASE',\"\n"
             + "              echo \"};\"\n"
             + "            } > vue.config.js\n"
             + "            echo \"vue.config.js 생성 완료 (publicPath: $BASE)\"\n"
             + "            exit 0\n"
             + "          fi\n"
             + "          # 사용자의 config 는 지우지 않고 옆으로 옮긴다. 우리 config 가 이것을\n"
             + "          # 불러와 감싸므로, 사용자가 적은 다른 설정은 그대로 살아남는다.\n"
             + "          EXT=\"${USER_CONFIG##*.}\"\n"
             + "          WRAPPED=\"vue.config.qeploy-user.$EXT\"\n"
             + "          mv \"$USER_CONFIG\" \"$WRAPPED\"\n"
             + "          # ESM 은 확장자를 반드시 적어야 하고, TypeScript 는 반대로 '.ts' 확장자\n"
             + "          # import 를 허용하지 않는다(allowImportingTsExtensions 없이는 컴파일 오류).\n"
             + "          if [ \"$EXT\" = \"ts\" ]; then IMPORT_PATH=\"./${WRAPPED%.ts}\"; else IMPORT_PATH=\"./$WRAPPED\"; fi\n"
             + "          # 확장자만으로는 모듈 종류를 알 수 없다. SvelteKit 의 svelte.config.js 는\n"
             + "          # 항상 ESM 이고(프로젝트가 \"type\": \"module\" 이다), CJS 로 감싸면 그 자리에서\n"
             + "          # 깨진다. 그래서 package.json 의 선언까지 본다.\n"
             + "          IS_ESM=false\n"
             + "          case \"$EXT\" in mjs|ts) IS_ESM=true;; esac\n"
             + "          if [ \"$IS_ESM\" = \"false\" ] && grep -q '\"type\"[[:space:]]*:[[:space:]]*\"module\"' package.json 2>/dev/null; then\n"
             + "            IS_ESM=true\n"
             + "          fi\n"
             + "          if [ \"$IS_ESM\" = \"true\" ]; then\n"
             + "            {\n"
             + "              echo \"import userConfig from '$IMPORT_PATH';\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const basePath = process.env.BASE_PATH ?? '/';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"export default {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  publicPath: basePath,\"\n"
             + "              echo \"};\"\n"
             + "            } > \"vue.config.$EXT\"\n"
             + "          else\n"
             + "            {\n"
             + "              echo \"const userConfig = require('$IMPORT_PATH');\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const basePath = process.env.BASE_PATH ?? '/';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"module.exports = {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  publicPath: basePath,\"\n"
             + "              echo \"};\"\n"
             + "            } > \"vue.config.$EXT\"\n"
             + "          fi\n"
             + "          echo \"$USER_CONFIG 를 $WRAPPED 로 옮기고 감쌌습니다 (publicPath: '$BASE')\"\n";
    }

    /**
     * SvelteKit: adapter-static 을 보장하고 paths.base 를 배포 시점 값으로 확정한다.
     *
     * paths.base 는 trailing slash 가 있으면 빌드가 거부하고, 루트일 때는 빈 문자열이어야 한다 —
     * 그래서 slash 없는 쪽(base)을 읽는다. adapter 는 사용자의 것을 그대로 둔다. 정적 어댑터가
     * 아니면 배포 자체가 성립하지 않지만 그건 base 문제가 아니고, 남의 어댑터를 갈아치우는 것은
     * 이 스텝이 할 일보다 훨씬 큰 개입이다.
     */
    private static String sveltekitConfigStep(PackageManager pm) {
        String adapterInstallCmd = switch (pm) {
            case NPM  -> "npm install --save-dev @sveltejs/adapter-static";
            case YARN -> "yarn add --dev @sveltejs/adapter-static";
            case PNPM -> "pnpm add --save-dev @sveltejs/adapter-static";
            case BUN  -> "bun add --dev @sveltejs/adapter-static";
        };
        return "      - name: Configure SvelteKit static adapter\n"
             + "        run: |\n"
             + "          BASE=\"${{ steps.base.outputs.base }}\"\n"
             + "          if ! grep -q 'adapter-static' package.json 2>/dev/null; then\n"
             + "            " + adapterInstallCmd + "\n"
             + "            echo \"@sveltejs/adapter-static 설치 완료\"\n"
             + "          fi\n"
             + "          USER_CONFIG=\"\"\n"
             + "          for f in svelte.config.js svelte.config.ts; do\n"
             + "            if [ -f \"$f\" ]; then USER_CONFIG=\"$f\"; break; fi\n"
             + "          done\n"
             + "          if [ -z \"$USER_CONFIG\" ]; then\n"
             + "            {\n"
             + "              echo \"import adapter from '@sveltejs/adapter-static';\"\n"
             + "              echo \"const config = {\"\n"
             + "              echo \"  kit: {\"\n"
             + "              echo \"    adapter: adapter({ fallback: '404.html' }),\"\n"
             + "              echo \"    paths: { base: '$BASE' },\"\n"
             + "              echo \"  },\"\n"
             + "              echo \"};\"\n"
             + "              echo \"export default config;\"\n"
             + "            } > svelte.config.js\n"
             + "            echo \"svelte.config.js 생성 완료 (adapter-static, base: $BASE)\"\n"
             + "            exit 0\n"
             + "          fi\n"
             + "          # 사용자의 config 는 지우지 않고 옆으로 옮긴다. 우리 config 가 이것을\n"
             + "          # 불러와 감싸므로, 사용자가 적은 다른 설정은 그대로 살아남는다.\n"
             + "          EXT=\"${USER_CONFIG##*.}\"\n"
             + "          WRAPPED=\"svelte.config.qeploy-user.$EXT\"\n"
             + "          mv \"$USER_CONFIG\" \"$WRAPPED\"\n"
             + "          # ESM 은 확장자를 반드시 적어야 하고, TypeScript 는 반대로 '.ts' 확장자\n"
             + "          # import 를 허용하지 않는다(allowImportingTsExtensions 없이는 컴파일 오류).\n"
             + "          if [ \"$EXT\" = \"ts\" ]; then IMPORT_PATH=\"./${WRAPPED%.ts}\"; else IMPORT_PATH=\"./$WRAPPED\"; fi\n"
             + "          # 확장자만으로는 모듈 종류를 알 수 없다. SvelteKit 의 svelte.config.js 는\n"
             + "          # 항상 ESM 이고(프로젝트가 \"type\": \"module\" 이다), CJS 로 감싸면 그 자리에서\n"
             + "          # 깨진다. 그래서 package.json 의 선언까지 본다.\n"
             + "          IS_ESM=false\n"
             + "          case \"$EXT\" in mjs|ts) IS_ESM=true;; esac\n"
             + "          if [ \"$IS_ESM\" = \"false\" ] && grep -q '\"type\"[[:space:]]*:[[:space:]]*\"module\"' package.json 2>/dev/null; then\n"
             + "            IS_ESM=true\n"
             + "          fi\n"
             + "          if [ \"$IS_ESM\" = \"true\" ]; then\n"
             + "            {\n"
             + "              echo \"import userConfig from '$IMPORT_PATH';\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const basePath = process.env.BASE_PATH ?? '/';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"export default {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  kit: { ...(resolved.kit ?? {}), paths: { ...(resolved.kit?.paths ?? {}), base } },\"\n"
             + "              echo \"};\"\n"
             + "            } > \"svelte.config.$EXT\"\n"
             + "          else\n"
             + "            {\n"
             + "              echo \"const userConfig = require('$IMPORT_PATH');\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const basePath = process.env.BASE_PATH ?? '/';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"module.exports = {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  kit: { ...(resolved.kit ?? {}), paths: { ...(resolved.kit?.paths ?? {}), base } },\"\n"
             + "              echo \"};\"\n"
             + "            } > \"svelte.config.$EXT\"\n"
             + "          fi\n"
             + "          echo \"$USER_CONFIG 를 $WRAPPED 로 옮기고 감쌌습니다 (paths.base: '$BASE')\"\n";
    }

    /**
     * Gatsby: pathPrefix 를 배포 시점 값으로 확정한다.
     *
     * BASE 가 비어 있을 때(커스텀 도메인) 그냥 빠져나가면 안 된다 — 커밋된 pathPrefix 가 남아
     * 자산 경로 앞에 "/repo" 가 붙는데, 그 경로에는 아무것도 없다. 비어 있는 것도 확정해야 할
     * 값이다. 빌드는 이미 --prefix-paths 로 돈다.
     */
    private static String gatsbyConfigStep() {
        return "      - name: Configure Gatsby path prefix\n"
             + "        run: |\n"
             + "          BASE=\"${{ steps.base.outputs.base }}\"\n"
             + "          USER_CONFIG=\"\"\n"
             + "          for f in gatsby-config.js gatsby-config.ts gatsby-config.mjs; do\n"
             + "            if [ -f \"$f\" ]; then USER_CONFIG=\"$f\"; break; fi\n"
             + "          done\n"
             + "          if [ -z \"$USER_CONFIG\" ]; then\n"
             + "            {\n"
             + "              echo \"module.exports = {\"\n"
             + "              echo \"  pathPrefix: '$BASE',\"\n"
             + "              echo \"};\"\n"
             + "            } > gatsby-config.js\n"
             + "            echo \"gatsby-config.js 생성 완료 (pathPrefix: $BASE)\"\n"
             + "            exit 0\n"
             + "          fi\n"
             + "          # 사용자의 config 는 지우지 않고 옆으로 옮긴다. 우리 config 가 이것을\n"
             + "          # 불러와 감싸므로, 사용자가 적은 다른 설정은 그대로 살아남는다.\n"
             + "          EXT=\"${USER_CONFIG##*.}\"\n"
             + "          WRAPPED=\"gatsby-config.qeploy-user.$EXT\"\n"
             + "          mv \"$USER_CONFIG\" \"$WRAPPED\"\n"
             + "          # ESM 은 확장자를 반드시 적어야 하고, TypeScript 는 반대로 '.ts' 확장자\n"
             + "          # import 를 허용하지 않는다(allowImportingTsExtensions 없이는 컴파일 오류).\n"
             + "          if [ \"$EXT\" = \"ts\" ]; then IMPORT_PATH=\"./${WRAPPED%.ts}\"; else IMPORT_PATH=\"./$WRAPPED\"; fi\n"
             + "          # 확장자만으로는 모듈 종류를 알 수 없다. SvelteKit 의 svelte.config.js 는\n"
             + "          # 항상 ESM 이고(프로젝트가 \"type\": \"module\" 이다), CJS 로 감싸면 그 자리에서\n"
             + "          # 깨진다. 그래서 package.json 의 선언까지 본다.\n"
             + "          IS_ESM=false\n"
             + "          case \"$EXT\" in mjs|ts) IS_ESM=true;; esac\n"
             + "          if [ \"$IS_ESM\" = \"false\" ] && grep -q '\"type\"[[:space:]]*:[[:space:]]*\"module\"' package.json 2>/dev/null; then\n"
             + "            IS_ESM=true\n"
             + "          fi\n"
             + "          if [ \"$IS_ESM\" = \"true\" ]; then\n"
             + "            {\n"
             + "              echo \"import userConfig from '$IMPORT_PATH';\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const basePath = process.env.BASE_PATH ?? '/';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"export default {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  pathPrefix: base,\"\n"
             + "              echo \"};\"\n"
             + "            } > \"gatsby-config.$EXT\"\n"
             + "          else\n"
             + "            {\n"
             + "              echo \"const userConfig = require('$IMPORT_PATH');\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const basePath = process.env.BASE_PATH ?? '/';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"module.exports = {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  pathPrefix: base,\"\n"
             + "              echo \"};\"\n"
             + "            } > \"gatsby-config.$EXT\"\n"
             + "          fi\n"
             + "          echo \"$USER_CONFIG 를 $WRAPPED 로 옮기고 감쌌습니다 (pathPrefix: '$BASE')\"\n";
    }

    /**
     * Astro: base 와 output 을 배포 시점 값으로 확정한다.
     *
     * base 는 빈 문자열이 아니라 '/' 여야 한다 — Astro 의 기본값이 '/' 이고 빈 값은 경로 계산을
     * 어긋나게 한다. output 이 static 이 아니면 정적 산출물이 나오지 않아 publish 스텝이 찾을
     * 디렉터리가 없다.
     */
    private static String astroConfigStep() {
        return "      - name: Configure Astro base path\n"
             + "        run: |\n"
             + "          BASE=\"${{ steps.base.outputs.base }}\"\n"
             + "          USER_CONFIG=\"\"\n"
             + "          for f in astro.config.mjs astro.config.js astro.config.ts; do\n"
             + "            if [ -f \"$f\" ]; then USER_CONFIG=\"$f\"; break; fi\n"
             + "          done\n"
             + "          if [ -z \"$USER_CONFIG\" ]; then\n"
             + "            {\n"
             + "              echo \"import { defineConfig } from 'astro/config';\"\n"
             + "              echo \"export default defineConfig({\"\n"
             + "              echo \"  base: '$BASE',\"\n"
             + "              echo \"  output: 'static',\"\n"
             + "              echo \"});\"\n"
             + "            } > astro.config.mjs\n"
             + "            echo \"astro.config.mjs 생성 완료 (base: $BASE)\"\n"
             + "            exit 0\n"
             + "          fi\n"
             + "          # 사용자의 config 는 지우지 않고 옆으로 옮긴다. 우리 config 가 이것을\n"
             + "          # 불러와 감싸므로, 사용자가 적은 다른 설정은 그대로 살아남는다.\n"
             + "          EXT=\"${USER_CONFIG##*.}\"\n"
             + "          WRAPPED=\"astro.config.qeploy-user.$EXT\"\n"
             + "          mv \"$USER_CONFIG\" \"$WRAPPED\"\n"
             + "          # ESM 은 확장자를 반드시 적어야 하고, TypeScript 는 반대로 '.ts' 확장자\n"
             + "          # import 를 허용하지 않는다(allowImportingTsExtensions 없이는 컴파일 오류).\n"
             + "          if [ \"$EXT\" = \"ts\" ]; then IMPORT_PATH=\"./${WRAPPED%.ts}\"; else IMPORT_PATH=\"./$WRAPPED\"; fi\n"
             + "          # 확장자만으로는 모듈 종류를 알 수 없다. SvelteKit 의 svelte.config.js 는\n"
             + "          # 항상 ESM 이고(프로젝트가 \"type\": \"module\" 이다), CJS 로 감싸면 그 자리에서\n"
             + "          # 깨진다. 그래서 package.json 의 선언까지 본다.\n"
             + "          IS_ESM=false\n"
             + "          case \"$EXT\" in mjs|ts) IS_ESM=true;; esac\n"
             + "          if [ \"$IS_ESM\" = \"false\" ] && grep -q '\"type\"[[:space:]]*:[[:space:]]*\"module\"' package.json 2>/dev/null; then\n"
             + "            IS_ESM=true\n"
             + "          fi\n"
             + "          if [ \"$IS_ESM\" = \"true\" ]; then\n"
             + "            {\n"
             + "              echo \"import userConfig from '$IMPORT_PATH';\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const basePath = process.env.BASE_PATH ?? '/';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"export default {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  base: base || '/',\"\n"
             + "              echo \"  output: 'static',\"\n"
             + "              echo \"};\"\n"
             + "            } > \"astro.config.$EXT\"\n"
             + "          else\n"
             + "            {\n"
             + "              echo \"const userConfig = require('$IMPORT_PATH');\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const basePath = process.env.BASE_PATH ?? '/';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"module.exports = {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  base: base || '/',\"\n"
             + "              echo \"  output: 'static',\"\n"
             + "              echo \"};\"\n"
             + "            } > \"astro.config.$EXT\"\n"
             + "          fi\n"
             + "          echo \"$USER_CONFIG 를 $WRAPPED 로 옮기고 감쌌습니다 (base: '$BASE')\"\n";
    }

    /**
     * Nuxt: app.baseURL 을 배포 시점 값으로 확정한다.
     *
     * Nuxt 는 여태 분기가 아예 없어 커밋된 nuxt.config 이 유일한 진실이었다. baseURL 은
     * trailing slash 를 포함해야 한다 — Nuxt 가 자산 URL 앞에 그대로 이어 붙인다.
     */
    private static String nuxtConfigStep() {
        return "      - name: Configure Nuxt base URL\n"
             + "        run: |\n"
             + "          BASE=\"${{ steps.base.outputs.path }}\"\n"
             + "          USER_CONFIG=\"\"\n"
             + "          for f in nuxt.config.js nuxt.config.ts nuxt.config.mjs; do\n"
             + "            if [ -f \"$f\" ]; then USER_CONFIG=\"$f\"; break; fi\n"
             + "          done\n"
             + "          if [ -z \"$USER_CONFIG\" ]; then\n"
             + "            {\n"
             + "              echo \"export default {\"\n"
             + "              echo \"  app: { baseURL: '$BASE' },\"\n"
             + "              echo \"};\"\n"
             + "            } > nuxt.config.js\n"
             + "            echo \"nuxt.config.js 생성 완료 (app.baseURL: $BASE)\"\n"
             + "            exit 0\n"
             + "          fi\n"
             + "          # 사용자의 config 는 지우지 않고 옆으로 옮긴다. 우리 config 가 이것을\n"
             + "          # 불러와 감싸므로, 사용자가 적은 다른 설정은 그대로 살아남는다.\n"
             + "          EXT=\"${USER_CONFIG##*.}\"\n"
             + "          WRAPPED=\"nuxt.config.qeploy-user.$EXT\"\n"
             + "          mv \"$USER_CONFIG\" \"$WRAPPED\"\n"
             + "          # ESM 은 확장자를 반드시 적어야 하고, TypeScript 는 반대로 '.ts' 확장자\n"
             + "          # import 를 허용하지 않는다(allowImportingTsExtensions 없이는 컴파일 오류).\n"
             + "          if [ \"$EXT\" = \"ts\" ]; then IMPORT_PATH=\"./${WRAPPED%.ts}\"; else IMPORT_PATH=\"./$WRAPPED\"; fi\n"
             + "          # 확장자만으로는 모듈 종류를 알 수 없다. SvelteKit 의 svelte.config.js 는\n"
             + "          # 항상 ESM 이고(프로젝트가 \"type\": \"module\" 이다), CJS 로 감싸면 그 자리에서\n"
             + "          # 깨진다. 그래서 package.json 의 선언까지 본다.\n"
             + "          IS_ESM=false\n"
             + "          case \"$EXT\" in mjs|ts) IS_ESM=true;; esac\n"
             + "          if [ \"$IS_ESM\" = \"false\" ] && grep -q '\"type\"[[:space:]]*:[[:space:]]*\"module\"' package.json 2>/dev/null; then\n"
             + "            IS_ESM=true\n"
             + "          fi\n"
             + "          if [ \"$IS_ESM\" = \"true\" ]; then\n"
             + "            {\n"
             + "              echo \"import userConfig from '$IMPORT_PATH';\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const basePath = process.env.BASE_PATH ?? '/';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"export default {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  app: { ...(resolved.app ?? {}), baseURL: basePath },\"\n"
             + "              echo \"};\"\n"
             + "            } > \"nuxt.config.$EXT\"\n"
             + "          else\n"
             + "            {\n"
             + "              echo \"const userConfig = require('$IMPORT_PATH');\"\n"
             + "              echo \"const base = process.env.QEPLOY_BASE_PATH ?? '';\"\n"
             + "              echo \"const basePath = process.env.BASE_PATH ?? '/';\"\n"
             + "              echo \"const resolved = typeof userConfig === 'function' ? userConfig() : (userConfig?.default ?? userConfig ?? {});\"\n"
             + "              echo \"module.exports = {\"\n"
             + "              echo \"  ...resolved,\"\n"
             + "              echo \"  app: { ...(resolved.app ?? {}), baseURL: basePath },\"\n"
             + "              echo \"};\"\n"
             + "            } > \"nuxt.config.$EXT\"\n"
             + "          fi\n"
             + "          echo \"$USER_CONFIG 를 $WRAPPED 로 옮기고 감쌌습니다 (app.baseURL: $BASE)\"\n";
    }

    // ── 빌드 명령어 ───────────────────────────────────────────────────────────

    private static String resolveBuildCommand(String type, PackageManager pm) {
        return switch (type) {
            // CRA: PUBLIC_URL env 로 base path 적용
            case "cra", "create-react-app" -> pm.runScript("build");

            // Vue 3 (Vite 기반): --base 플래그로 직접 지정
            case "vue", "vue3"             -> pm.execBin("vite build --base=${{ steps.base.outputs.path }}");

            // Vue CLI: vue.config.js 의 publicPath 에서 읽음 (사전 설정 스텝에서 생성)
            case "vue-cli", "vue_cli"      -> pm.runScript("build");

            // Next.js: next.config 의 output: export 설정 필요 (사전 설정 스텝에서 생성)
            case "nextjs", "next"          -> pm.runScript("build");

            // Gatsby: gatsby-config 의 pathPrefix 를 적용하려면 --prefix-paths 필수
            case "gatsby"                  -> pm.execBin("gatsby build --prefix-paths");

            // SvelteKit: svelte.config 의 adapter-static 설정 필요 (사전 설정 스텝에서 생성)
            case "svelte", "sveltekit"     -> pm.runScript("build");

            // Nuxt 2/3: generate 로 정적 파일 생성
            case "nuxt", "nuxtjs",
                 "nuxt3"                   -> pm.runScript("generate");

            // Astro: 기본 빌드 (astro.config 의 base 설정 권장)
            case "astro"                   -> pm.runScript("build");

            // 기본값: Vite 프로젝트로 간주
            default                        -> pm.execBin("vite build --base=${{ steps.base.outputs.path }}");
        };
    }

    // ── 빌드 결과물 디렉토리 ─────────────────────────────────────────────────

    private static String resolvePublishDir(String type) {
        return switch (type) {
            // 빌드가 없으므로 리포지토리 루트가 곧 발행 대상이다.
            case "static"                  -> ".";
            case "cra", "create-react-app" -> "./build";
            case "nextjs", "next"          -> "./out";
            case "gatsby"                  -> "./public";
            case "svelte", "sveltekit"     -> "./build";
            case "nuxt", "nuxtjs",
                 "nuxt3"                   -> "./dist";
            case "astro"                   -> "./dist";
            default                        -> "./dist";
        };
    }

    private static String preserveCustomDomainStep(String outDir) {
        return "      - name: Preserve custom domain\n"
             + "        run: |\n"
             + "          mkdir -p " + outDir + "\n"
             + "          CNAME=\"${{ steps.base.outputs.cname }}\"\n"
             + "          if [ -n \"$CNAME\" ]; then\n"
             + "            printf '%s\\n' \"$CNAME\" > " + outDir + "/CNAME\n"
             + "            echo \"GitHub Pages custom domain CNAME 파일 생성 완료\"\n"
             + "            exit 0\n"
             + "          fi\n"
             + "          if git ls-remote --exit-code --heads origin gh-pages >/dev/null 2>&1; then\n"
             + "            if git fetch origin gh-pages --depth=1; then\n"
             + "              if git show FETCH_HEAD:CNAME > /tmp/qeploy-cname 2>/dev/null; then\n"
             + "                cp /tmp/qeploy-cname " + outDir + "/CNAME\n"
             + "                echo \"기존 CNAME 파일 보존 완료\"\n"
             + "              else\n"
             + "                echo \"기존 CNAME 파일 없음\"\n"
             + "              fi\n"
             + "            else\n"
             + "              echo \"gh-pages 브랜치 fetch 실패\"\n"
             + "            fi\n"
             + "          else\n"
             + "            echo \"gh-pages 브랜치 없음\"\n"
             + "          fi\n"
             + "\n";
    }
}
