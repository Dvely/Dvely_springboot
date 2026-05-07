package com.example.dvely.deployment.infrastructure.workflow;

import com.example.dvely.deployment.domain.value.PackageManager;

public class DeployWorkflowTemplate {

    private static final String WORKFLOW_FILE = "dvely-deploy.yml";

    public static String fileName() {
        return WORKFLOW_FILE;
    }

    /**
     * @param templateType  프로젝트 프레임워크 종류
     * @param publishDir    빌드 결과물 경로 (null 이면 templateType 으로 추론)
     * @param pm            감지된 패키지 매니저
     * @param nodeVersion   감지된 Node.js 주 버전 (예: "20")
     */
    public static String generate(String templateType, String publishDir,
                                  PackageManager pm, String nodeVersion) {
        String buildCmd = resolveBuildCommand(templateType, pm);
        String outDir   = publishDir != null ? publishDir : resolvePublishDir(templateType);

        StringBuilder w = new StringBuilder();

        // ── 헤더 ─────────────────────────────────────────────────────────────
        w.append("name: Dvely Deploy to GitHub Pages\n\n");
        w.append("on:\n");
        w.append("  workflow_dispatch:\n\n");
        w.append("permissions:\n");
        w.append("  contents: write\n");
        w.append("  pages: write\n\n");
        w.append("jobs:\n");
        w.append("  deploy:\n");
        w.append("    runs-on: ubuntu-latest\n");
        w.append("    steps:\n");

        // ── Checkout ─────────────────────────────────────────────────────────
        w.append("      - name: Checkout\n");
        w.append("        uses: actions/checkout@v4\n\n");

        // ── 런타임 설정 (pnpm/bun setup + Node.js) ───────────────────────────
        w.append(runtimeSetupSteps(pm, nodeVersion));

        // ── base path 해석 ────────────────────────────────────────────────────
        w.append("      - name: Resolve base path\n");
        w.append("        id: base\n");
        w.append("        run: |\n");
        w.append("          REPO=\"${{ github.event.repository.name }}\"\n");
        w.append("          OWNER=\"${{ github.repository_owner }}\"\n");
        w.append("          if [ \"$REPO\" = \"${OWNER}.github.io\" ]; then\n");
        w.append("            echo \"path=/\" >> $GITHUB_OUTPUT\n");
        w.append("          else\n");
        w.append("            echo \"path=/${REPO}/\" >> $GITHUB_OUTPUT\n");
        w.append("          fi\n\n");

        // ── 의존성 설치 ───────────────────────────────────────────────────────
        w.append("      - name: Install dependencies\n");
        w.append("        run: ").append(pm.installCommand()).append("\n\n");

        // ── 빌드 ─────────────────────────────────────────────────────────────
        w.append("      - name: Build\n");
        w.append("        run: ").append(buildCmd).append("\n");
        w.append("        env:\n");
        w.append("          PUBLIC_URL: ${{ steps.base.outputs.path }}\n");
        w.append("          VITE_BASE_URL: ${{ steps.base.outputs.path }}\n");
        w.append("          BASE_PATH: ${{ steps.base.outputs.path }}\n");
        w.append("\n");

        // ── SPA 라우팅 404 대응 ───────────────────────────────────────────────
        w.append("      - name: Copy index.html to 404.html\n");
        w.append("        run: cp ").append(outDir).append("/index.html ")
         .append(outDir).append("/404.html\n\n");

        // ── gh-pages 배포 ─────────────────────────────────────────────────────
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

    // ── 빌드 명령어 ───────────────────────────────────────────────────────────

    private static String resolveBuildCommand(String templateType, PackageManager pm) {
        if (templateType == null) return viteBuildCommand(pm);
        return switch (templateType.toLowerCase()) {
            case "cra", "create-react-app"          -> pm.runScript("build");
            case "nextjs", "next"                   -> pm.runScript("build");
            case "gatsby"                           -> pm.runScript("build");
            case "svelte", "sveltekit"              -> pm.runScript("build");
            case "nuxt", "nuxtjs", "nuxt3"          -> pm.runScript("generate");
            case "astro"                            -> pm.runScript("build");
            case "vue", "vue-cli", "vue3"           -> pm.runScript("build");
            default                                 -> viteBuildCommand(pm);
        };
    }

    private static String viteBuildCommand(PackageManager pm) {
        return pm.execBin("vite build --base=${{ steps.base.outputs.path }}");
    }

    // ── 빌드 결과물 디렉토리 ─────────────────────────────────────────────────

    private static String resolvePublishDir(String templateType) {
        if (templateType == null) return "./dist";
        return switch (templateType.toLowerCase()) {
            case "cra", "create-react-app"          -> "./build";
            case "nextjs", "next"                   -> "./out";
            case "gatsby"                           -> "./public";
            case "svelte", "sveltekit"              -> "./build";
            case "nuxt", "nuxtjs"                   -> "./dist";
            case "nuxt3"                            -> "./.output/public";
            default                                 -> "./dist";
        };
    }
}
