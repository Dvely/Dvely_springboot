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
}
