package com.example.dvely.deployment.infrastructure.workflow;

import com.example.dvely.deployment.domain.value.PackageManager;

public class DeployWorkflowTemplate {

    private static final String WORKFLOW_FILE = "dvely-deploy.yml";

    public static String fileName() {
        return WORKFLOW_FILE;
    }

    public static String generate(String templateType, String publishDir,
                                  PackageManager pm, String nodeVersion) {
        String type   = templateType == null ? "" : templateType.toLowerCase();
        String outDir = publishDir != null ? publishDir : resolvePublishDir(type);

        StringBuilder w = new StringBuilder();

        // ── 헤더 ─────────────────────────────────────────────────────────────
        w.append("name: Dvely Deploy to GitHub Pages\n\n");
        w.append("on:\n");
        w.append("  workflow_dispatch:\n");
        w.append("    inputs:\n");
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
        w.append(runtimeSetupSteps(pm, nodeVersion));

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

        // ── 4. 프레임워크별 빌드 전 설정 ─────────────────────────────────────
        String configStep = resolveConfigStep(type, pm);
        if (!configStep.isEmpty()) {
            w.append(configStep);
        }

        // ── 5. 의존성 설치 ────────────────────────────────────────────────────
        w.append("      - name: Install dependencies\n");
        w.append("        run: ").append(pm.installCommand()).append("\n\n");

        // ── 6. 빌드 ──────────────────────────────────────────────────────────
        w.append("      - name: Build\n");
        w.append("        run: ").append(resolveBuildCommand(type, pm)).append("\n");
        w.append("        env:\n");
        w.append("          BASE_PATH: ${{ steps.base.outputs.path }}\n");
        w.append("          PUBLIC_URL: ${{ steps.base.outputs.path }}\n\n");

        // ── 7. SPA 라우팅 404 대응 (빌드 결과물 있을 때만) ───────────────────
        w.append("      - name: Copy index.html to 404.html\n");
        w.append("        run: |\n");
        w.append("          [ -f ").append(outDir).append("/index.html ]");
        w.append(" && cp ").append(outDir).append("/index.html ").append(outDir).append("/404.html || true\n\n");

        // ── 8. 기존 custom domain 보존 ───────────────────────────────────────
        w.append(preserveCustomDomainStep(outDir));

        // ── 9. gh-pages 배포 ──────────────────────────────────────────────────
        w.append("      - name: Deploy to gh-pages\n");
        w.append("        uses: peaceiris/actions-gh-pages@v4\n");
        w.append("        with:\n");
        w.append("          github_token: ${{ secrets.GITHUB_TOKEN }}\n");
        w.append("          publish_dir: ").append(outDir).append("\n");

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
     * Next.js: 정적 export 를 위해 next.config 가 없으면 자동 생성.
     * 있으면 경고만 출력 (기존 설정 덮어쓰기 방지).
     * output: 'export' 와 basePath 가 없으면 빌드 결과물이 ./out 에 생성되지 않음.
     */
    private static String nextjsConfigStep() {
        return "      - name: Configure Next.js for static export\n"
             + "        run: |\n"
             + "          BASE=\"${{ steps.base.outputs.base }}\"\n"
             + "          CONFIG_EXISTS=false\n"
             + "          for f in next.config.js next.config.mjs next.config.ts; do\n"
             + "            if [ -f \"$f\" ]; then CONFIG_EXISTS=true; break; fi\n"
             + "          done\n"
             + "          if [ \"$CONFIG_EXISTS\" = \"false\" ]; then\n"
             + "            {\n"
             + "              echo \"/** @type {import('next').NextConfig} */\"\n"
             + "              echo \"const nextConfig = {\"\n"
             + "              echo \"  output: 'export',\"\n"
             + "              echo \"  basePath: '$BASE',\"\n"
             + "              echo \"  images: { unoptimized: true },\"\n"
             + "              echo \"};\"\n"
             + "              echo \"module.exports = nextConfig;\"\n"
             + "            } > next.config.js\n"
             + "            echo \"next.config.js 생성 완료 (output: export, basePath: $BASE)\"\n"
             + "          else\n"
             + "            echo \"::warning::next.config 파일이 존재합니다. output: 'export' 와 basePath: '$BASE' 가 설정되어 있는지 확인하세요.\"\n"
             + "          fi\n\n";
    }

    /**
     * Vue CLI: publicPath 설정을 위해 vue.config.js 가 없으면 자동 생성.
     * Vue CLI 는 PUBLIC_URL 을 인식하지 않으므로 vue.config.js 에서 직접 지정해야 함.
     */
    private static String vueCliConfigStep() {
        return "      - name: Configure Vue CLI public path\n"
             + "        run: |\n"
             + "          BASE=\"${{ steps.base.outputs.path }}\"\n"
             + "          if [ ! -f \"vue.config.js\" ] && [ ! -f \"vue.config.ts\" ]; then\n"
             + "            {\n"
             + "              echo \"module.exports = {\"\n"
             + "              echo \"  publicPath: '$BASE',\"\n"
             + "              echo \"};\"\n"
             + "            } > vue.config.js\n"
             + "            echo \"vue.config.js 생성 완료 (publicPath: $BASE)\"\n"
             + "          else\n"
             + "            echo \"::warning::vue.config 파일이 존재합니다. publicPath: '$BASE' 가 설정되어 있는지 확인하세요.\"\n"
             + "          fi\n\n";
    }

    /**
     * SvelteKit: 정적 배포를 위해 adapter-static 과 svelte.config.js 를 자동 설정.
     * adapter-static 없이는 빌드 결과물이 정적 파일로 생성되지 않음.
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
             + "          if [ ! -f \"svelte.config.js\" ] && [ ! -f \"svelte.config.ts\" ]; then\n"
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
             + "          else\n"
             + "            echo \"::warning::svelte.config 파일이 존재합니다. adapter-static 과 paths.base: '$BASE' 가 설정되어 있는지 확인하세요.\"\n"
             + "          fi\n\n";
    }

    /**
     * Gatsby: pathPrefix 설정을 위해 gatsby-config.js 가 없으면 자동 생성.
     * --prefix-paths 플래그 없이 빌드하면 pathPrefix 가 무시됨.
     */
    private static String gatsbyConfigStep() {
        return "      - name: Configure Gatsby path prefix\n"
             + "        run: |\n"
             + "          BASE=\"${{ steps.base.outputs.base }}\"\n"
             + "          if [ -z \"$BASE\" ]; then exit 0; fi\n"
             + "          CONFIG_EXISTS=false\n"
             + "          for f in gatsby-config.js gatsby-config.ts gatsby-config.mjs; do\n"
             + "            if [ -f \"$f\" ]; then CONFIG_EXISTS=true; break; fi\n"
             + "          done\n"
             + "          if [ \"$CONFIG_EXISTS\" = \"false\" ]; then\n"
             + "            {\n"
             + "              echo \"module.exports = {\"\n"
             + "              echo \"  pathPrefix: '$BASE',\"\n"
             + "              echo \"};\"\n"
             + "            } > gatsby-config.js\n"
             + "            echo \"gatsby-config.js 생성 완료 (pathPrefix: $BASE)\"\n"
             + "          else\n"
             + "            echo \"::warning::gatsby-config 파일이 존재합니다. pathPrefix: '$BASE' 가 설정되어 있는지 확인하세요.\"\n"
             + "          fi\n\n";
    }

    /**
     * Astro: base 설정을 위해 astro.config.mjs 가 없으면 자동 생성.
     * base 없이 빌드하면 서브경로에서 asset 404 발생.
     */
    private static String astroConfigStep() {
        return "      - name: Configure Astro base path\n"
             + "        run: |\n"
             + "          BASE=\"${{ steps.base.outputs.base }}\"\n"
             + "          CONFIG_EXISTS=false\n"
             + "          for f in astro.config.mjs astro.config.js astro.config.ts; do\n"
             + "            if [ -f \"$f\" ]; then CONFIG_EXISTS=true; break; fi\n"
             + "          done\n"
             + "          if [ \"$CONFIG_EXISTS\" = \"false\" ]; then\n"
             + "            {\n"
             + "              echo \"import { defineConfig } from 'astro/config';\"\n"
             + "              echo \"export default defineConfig({\"\n"
             + "              echo \"  base: '$BASE',\"\n"
             + "              echo \"  output: 'static',\"\n"
             + "              echo \"});\"\n"
             + "            } > astro.config.mjs\n"
             + "            echo \"astro.config.mjs 생성 완료 (base: $BASE)\"\n"
             + "          else\n"
             + "            echo \"::warning::astro.config 파일이 존재합니다. base: '$BASE' 와 output: 'static' 이 설정되어 있는지 확인하세요.\"\n"
             + "          fi\n\n";
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
             + "              if git show FETCH_HEAD:CNAME > /tmp/dvely-cname 2>/dev/null; then\n"
             + "                cp /tmp/dvely-cname " + outDir + "/CNAME\n"
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
