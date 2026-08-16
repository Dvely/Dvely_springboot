package com.example.dvely.deployment.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deployment.domain.value.PackageManager;
import org.junit.jupiter.api.Test;

class DeployWorkflowTemplateTest {

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
