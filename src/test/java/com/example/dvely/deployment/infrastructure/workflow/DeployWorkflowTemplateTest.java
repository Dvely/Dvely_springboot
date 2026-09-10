package com.example.dvely.deployment.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deployment.domain.value.PackageManager;
import org.junit.jupiter.api.Test;

class DeployWorkflowTemplateTest {

    /**
     * Next.js 는 커밋된 config 가 이겨 배포가 끝까지 가지 못했다.
     *
     * create-next-app 은 스캐폴딩 때 next.config.mjs 를 항상 만든다. 예전 스텝은 파일이 있으면
     * 경고만 하고 넘어갔는데, 그 config 에는 output: 'export' 가 없으므로 next build 가
     * .next 만 만들고 ./out 은 생기지 않는다 — publish 스텝이 그 디렉터리를 찾다 실패한다.
     * 자산 404 이전에 배포 자체가 실패하는 것이다.
     */
    @Test
    void generate_nextjsWrapsACommittedConfigInsteadOfOnlyWarning() {
        String workflow = DeployWorkflowTemplate.generate("nextjs", null, PackageManager.NPM, "20");

        // 커밋된 config 를 발견해도 그냥 넘어가지 않는다.
        assertThat(workflow).doesNotContain("::warning::next.config 파일이 존재합니다");
        // 사용자의 config 는 지우지 않고 옆으로 옮겨 감싼다 — 다른 설정이 살아남아야 한다.
        assertThat(workflow).contains("mv \"$USER_CONFIG\" \"$WRAPPED\"");
        assertThat(workflow).contains("...resolved,");
        // 우리가 소유하는 세 필드는 배포 시점 값으로 확정한다.
        assertThat(workflow).contains("output: 'export',");
        assertThat(workflow).contains("basePath: base,");
        assertThat(workflow).contains("unoptimized: true");
    }

    /**
     * basePath 는 trailing slash 가 없어야 한다. BASE_PATH/PUBLIC_URL 은 slash 를 포함하므로
     * (Vite --base, CRA homepage 가 그 형태를 요구한다) 그대로 쓰면 경로가 "//" 로 겹친다.
     */
    @Test
    void generate_nextjsReadsTheSlashlessBaseFromItsOwnEnvVar() {
        String workflow = DeployWorkflowTemplate.generate("nextjs", null, PackageManager.NPM, "20");

        assertThat(workflow).contains("QEPLOY_BASE_PATH: ${{ steps.base.outputs.base }}");
        assertThat(workflow).contains("process.env.QEPLOY_BASE_PATH");
        // path 쪽(슬래시 포함)을 basePath 로 읽지 않는다.
        assertThat(workflow).doesNotContain("basePath: process.env.BASE_PATH");
    }

    /**
     * TypeScript config 는 확장자를 떼고 import 해야 한다.
     *
     * ESM(.mjs)은 확장자를 반드시 적어야 하는데, TypeScript 는 반대로 '.ts' 확장자 import 를
     * allowImportingTsExtensions 없이는 허용하지 않는다. 같은 문자열로 둘 다 만들면 한쪽이 깨진다.
     */
    @Test
    void generate_nextjsStripsTheTsExtensionWhenImportingTheWrappedConfig() {
        String workflow = DeployWorkflowTemplate.generate("nextjs", null, PackageManager.NPM, "20");

        assertThat(workflow).contains("if [ \"$EXT\" = \"ts\" ]; then IMPORT_PATH=\"./${WRAPPED%.ts}\"");
        assertThat(workflow).contains("import userConfig from '$IMPORT_PATH';");
    }

    /**
     * config 가 아예 없는 저장소에서는 예전처럼 새로 만든다. 이 경로는 감싸는 대상이 없다.
     */
    @Test
    void generate_nextjsStillCreatesAConfigWhenTheRepositoryHasNone() {
        String workflow = DeployWorkflowTemplate.generate("nextjs", null, PackageManager.NPM, "20");

        assertThat(workflow).contains("if [ -z \"$USER_CONFIG\" ]; then");
        assertThat(workflow).contains("next.config.js 생성 완료");
    }

    /**
     * publish 디렉터리는 프레임워크 어휘(cra/nextjs/gatsby...)로만 결정된다. 콘텐츠 템플릿
     * 이름(landing, portfolio 등)이 흘러 들어오면 알 수 없는 값이라 기본값으로 떨어지는데,
     * 그 프로젝트가 실제로 Next.js(./out)나 CRA(./build)를 만들었다면 산출물이 빈 채로 배포된다.
     * 배포 자체는 성공으로 끝나 알아채기 어렵다.
     *
     * 그래서 두 어휘를 섞지 않는다 — 배포 경로는 저장소 감지 결과만 쓴다.
     */
    /**
     * 나머지 프레임워크도 커밋된 config 를 이긴다.
     *
     * 이 다섯은 Qeploy 가 스캐폴딩하지 않는다 — CODE 프롬프트가 만드는 것은 Vite+React·CRA·
     * Next.js·create-vue·순수 HTML 뿐이다. 즉 이들은 <b>연결된 저장소로만</b> 들어오고, 그
     * 저장소에는 스캐폴더가 첫날 써 둔 config 가 반드시 있다. 예전 스텝은 그때 경고만 하고
     * 넘어갔으므로, 이 경로로 들어온 프로젝트는 사실상 항상 base 가 어긋난 채 배포됐다.
     */
    @Test
    void generate_everyFrameworkOverridesACommittedBaseInsteadOfWarning() {
        record Case(String type, String warning, String override) {}
        var cases = new Case[]{
                new Case("vue-cli", "::warning::vue.config", "publicPath: basePath,"),
                new Case("sveltekit", "::warning::svelte.config", "paths: { ...(resolved.kit?.paths ?? {}), base }"),
                new Case("gatsby", "::warning::gatsby-config", "pathPrefix: base,"),
                new Case("astro", "::warning::astro.config", "base: base || '/',"),
        };
        for (Case c : cases) {
            String workflow = DeployWorkflowTemplate.generate(c.type(), null, PackageManager.NPM, "20");
            assertThat(workflow).as(c.type() + " 는 더 이상 경고만 하지 않는다").doesNotContain(c.warning());
            assertThat(workflow).as(c.type() + " 가 base 를 확정한다").contains(c.override());
            assertThat(workflow).as(c.type() + " 가 사용자 설정을 보존한다").contains("...resolved,");
        }
    }

    /**
     * Nuxt 는 분기 자체가 없어 커밋된 nuxt.config 이 유일한 진실이었다.
     */
    @Test
    void generate_nuxtNowHasABaseStepAtAll() {
        String workflow = DeployWorkflowTemplate.generate("nuxt", null, PackageManager.NPM, "20");

        assertThat(workflow).contains("Configure Nuxt base URL");
        // baseURL 은 trailing slash 를 포함해야 한다 — Nuxt 가 자산 URL 앞에 그대로 이어 붙인다.
        assertThat(workflow).contains("baseURL: basePath");
        assertThat(workflow).contains("nuxt.config.qeploy-user.$EXT");
    }

    /**
     * 확장자만으로 모듈 종류를 정하면 SvelteKit 에서 깨진다.
     *
     * svelte.config.js 는 확장자가 js 지만 SvelteKit 프로젝트는 항상 "type": "module" 이라
     * ESM 이다. 확장자만 보고 CJS 로 감싸면 require 가 ESM 을 읽다 그 자리에서 죽는다.
     */
    @Test
    void generate_decidesModuleKindByPackageJsonNotOnlyByExtension() {
        String workflow = DeployWorkflowTemplate.generate("sveltekit", null, PackageManager.NPM, "20");

        assertThat(workflow).contains("grep -q '\"type\"[[:space:]]*:[[:space:]]*\"module\"' package.json");
        assertThat(workflow).contains("case \"$EXT\" in mjs|ts) IS_ESM=true;; esac");
    }

    /**
     * 커스텀 도메인이면 base 는 비어야 하는데, Gatsby 는 그 경우 스텝을 통째로 건너뛰었다.
     *
     * 건너뛰면 커밋된 pathPrefix 가 그대로 남아 자산 앞에 "/repo" 가 붙는다. 그 경로에는 아무것도
     * 없으므로 전부 404 다. 비어 있는 것도 확정해야 할 값이다.
     */
    @Test
    void generate_gatsbyDoesNotSkipItselfWhenTheBaseIsEmpty() {
        String workflow = DeployWorkflowTemplate.generate("gatsby", null, PackageManager.NPM, "20");

        assertThat(workflow).doesNotContain("if [ -z \"$BASE\" ]; then exit 0; fi");
        assertThat(workflow).contains("pathPrefix: base,");
    }

    /**
     * config 가 없는 저장소에서는 예전처럼 새로 만든다 — 지금 동작하는 경로다.
     */
    @Test
    void generate_stillCreatesAConfigWhenTheRepositoryHasNone() {
        for (String type : new String[]{"vue-cli", "sveltekit", "gatsby", "astro", "nuxt"}) {
            String workflow = DeployWorkflowTemplate.generate(type, null, PackageManager.NPM, "20");
            assertThat(workflow).as(type).contains("if [ -z \"$USER_CONFIG\" ]; then");
            assertThat(workflow).as(type).contains("생성 완료");
        }
    }

    /**
     * SvelteKit 은 사용자의 adapter 를 갈아치우지 않는다.
     *
     * 정적 어댑터가 아니면 배포가 성립하지 않지만 그건 base 문제가 아니고, 남의 어댑터를 바꾸는
     * 것은 이 스텝이 할 일보다 훨씬 큰 개입이다. 설치와 경고까지만 한다.
     */
    @Test
    void generate_sveltekitKeepsTheUsersAdapterWhenWrapping() {
        String workflow = DeployWorkflowTemplate.generate("sveltekit", null, PackageManager.NPM, "20");

        // 감싼 config 는 kit 를 펼쳐 넣으므로 adapter 가 살아남는다.
        assertThat(workflow).contains("kit: { ...(resolved.kit ?? {})");
        // 덮어쓰는 것은 paths.base 뿐이다.
        assertThat(workflow).doesNotContain("adapter: adapter({ fallback: '404.html' }),\\n\" + \"              echo \"  kit");
    }

    @Test
    void generate_contentTemplateNamesAreNotFrameworkVocabulary() {
        for (String contentTemplate : new String[]{"landing", "portfolio", "e-commerce"}) {
            assertThat(DeployWorkflowTemplate.generate(contentTemplate, null, PackageManager.NPM, "20"))
                    .as("contentTemplate=%s", contentTemplate)
                    .contains("publish_dir: ./dist");
        }
    }

    @Test
    void generate_fallsBackToDistWhenFrameworkIsUnknown() {
        // 감지 실패 시 null 이 그대로 넘어온다. Vite 산출물이 dist 라 기본값이 이것이다.
        assertThat(DeployWorkflowTemplate.generate(null, null, PackageManager.NPM, "20"))
                .contains("publish_dir: ./dist");
    }

    @Test
    void generate_preservesExistingCustomDomainBeforePublishingGhPages() {
        String workflow = DeployWorkflowTemplate.generate("vue", null, PackageManager.NPM, "20");

        assertThat(workflow).contains("      - name: Preserve custom domain");
        assertThat(workflow).contains("CNAME=\"${{ steps.base.outputs.cname }}\"");
        assertThat(workflow).contains("printf '%s\\n' \"$CNAME\" > ./dist/CNAME");
        assertThat(workflow).contains("git fetch origin gh-pages --depth=1");
        assertThat(workflow).contains("git show FETCH_HEAD:CNAME > /tmp/qeploy-cname");
        assertThat(workflow).contains("cp /tmp/qeploy-cname ./dist/CNAME");
        assertThat(workflow).containsSubsequence(
                "      - name: Preserve custom domain",
                "      - name: Deploy to gh-pages"
        );
    }

    @Test
    void generate_usesQeployBrandAndKeepsLegacyWorkflowCompatibility() {
        String workflow = DeployWorkflowTemplate.generate("vue", null, PackageManager.NPM, "20");

        assertThat(DeployWorkflowTemplate.fileName()).isEqualTo("qeploy-deploy.yml");
        assertThat(DeployWorkflowTemplate.legacyFileName()).isEqualTo("dvely-deploy.yml");
        assertThat(DeployWorkflowTemplate.isQeployWorkflowName("Qeploy Deploy to GitHub Pages")).isTrue();
        assertThat(DeployWorkflowTemplate.isQeployWorkflowName("Dvely Deploy to GitHub Pages")).isTrue();
        assertThat(workflow).contains("name: Qeploy Deploy to GitHub Pages");
        assertThat(workflow).doesNotContain("Dvely");
    }

    @Test
    void acceptsTheRunNameThatWebhooksActuallyCarry() {
        // workflow_run.name 은 파일의 name: 이 아니라 run-name: 이 적용된 값으로 온다. 이걸
        // 받지 못하면 핸들러가 가드에서 조용히 빠져나가 배포 이력이 IN_PROGRESS 에 영원히 멈춘다
        // — GitHub 에서는 성공하고 사이트도 뜨는데 경고 로그조차 남지 않는다(2026-08-18 운영 실측).
        String workflow = DeployWorkflowTemplate.generate("vue", null, PackageManager.NPM, "20");
        assertThat(workflow).contains("run-name: Qeploy deployment ${{ inputs.deployment_id }}");

        String runName = DeployWorkflowTemplate.runTitle("8354b81f-77c8-479b-8301-b61a906ddb32");
        assertThat(DeployWorkflowTemplate.isQeployWorkflowName(runName)).isTrue();
        assertThat(DeployWorkflowTemplate.correlationIdFromRunTitle(runName))
                .isEqualTo("8354b81f-77c8-479b-8301-b61a906ddb32");
    }

    @Test
    void ignoresWorkflowsThatAreNotOurs() {
        // 저장소에는 우리 것이 아닌 workflow_run 웹훅도 계속 들어온다 — GitHub 이 Pages 를 올릴
        // 때마다 보내는 "pages build and deployment" 가 대표적이다.
        assertThat(DeployWorkflowTemplate.isQeployWorkflowName("pages build and deployment")).isFalse();
        assertThat(DeployWorkflowTemplate.isQeployWorkflowName("CI")).isFalse();
        assertThat(DeployWorkflowTemplate.isQeployWorkflowName(null)).isFalse();
    }

    @Test
    void generate_usesRootBasePathWhenGithubPagesHasCustomDomain() {
        String workflow = DeployWorkflowTemplate.generate("vue", null, PackageManager.NPM, "20");

        assertThat(workflow).contains("https://api.github.com/repos/${GITHUB_REPOSITORY}/pages");
        assertThat(workflow).contains("echo \"cname=${CNAME}\" >> $GITHUB_OUTPUT");
        assertThat(workflow).contains("if [ \"$REPO\" = \"${OWNER}.github.io\" ] || [ -n \"$CNAME\" ]; then");
        assertThat(workflow).contains("echo \"path=/\" >> $GITHUB_OUTPUT");
        assertThat(workflow).contains("echo \"path=/${REPO}/\" >> $GITHUB_OUTPUT");
    }

    @Test
    void generate_acceptsCheckoutRefInputForVersionBuilds() {
        String workflow = DeployWorkflowTemplate.generate("vue", null, PackageManager.NPM, "20");

        assertThat(workflow).contains("  workflow_dispatch:\n    inputs:");
        assertThat(workflow).contains("run-name: Qeploy deployment ${{ inputs.deployment_id }}");
        assertThat(workflow).contains("      deployment_id:");
        assertThat(workflow).contains("      checkout_ref:");
        assertThat(workflow).contains("          ref: ${{ inputs.checkout_ref || github.ref_name }}");
        assertThat(DeployWorkflowTemplate.correlationIdFromRunTitle(
                DeployWorkflowTemplate.runTitle("deployment-123")
        )).isEqualTo("deployment-123");
    }

    /**
     * 프레임워크 없이 만든 정적 사이트는 Node 도 의존성도 빌드도 없다. 그런데 워크플로는 늘
     * setup-node(cache 켬) + install + build 를 넣었고, cache 는 lock 파일을 요구해 그 자리에서
     * 죽었다 — 2026-09-08 dev 실측(dldnsgkr/static-todo-v2):
     * {@code Dependencies lock file is not found ... Supported file patterns: package-lock.json,
     * npm-shrinkwrap.json, yarn.lock}. 승인까지 다 끝난 배포가 마지막에 실패한다.
     */
    @Test
    void staticSiteSkipsNodeSetupInstallAndBuild() {
        String workflow = DeployWorkflowTemplate.generate("static", null, PackageManager.NPM, "20");

        assertThat(workflow)
                .doesNotContain("actions/setup-node")
                .doesNotContain("Install dependencies")
                .doesNotContain("- name: Build");
        // 올릴 파일이 이미 리포지토리에 있으므로 루트를 그대로 발행한다.
        assertThat(workflow).contains("publish_dir: .");
        // 체크아웃과 발행은 그대로 남아야 한다.
        assertThat(workflow).contains("actions/checkout@v4").contains("peaceiris/actions-gh-pages@v4");
    }

    /** 정적이 아닌 프로젝트는 예전 그대로 빌드한다(회귀 방지). */
    @Test
    void nonStaticProjectsStillSetUpNodeAndBuild() {
        String workflow = DeployWorkflowTemplate.generate("vue", null, PackageManager.NPM, "20");

        assertThat(workflow)
                .contains("actions/setup-node")
                .contains("Install dependencies")
                .contains("- name: Build")
                .contains("publish_dir: ./dist");
    }
}
