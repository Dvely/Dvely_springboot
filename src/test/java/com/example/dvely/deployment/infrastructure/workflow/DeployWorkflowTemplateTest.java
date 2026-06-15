package com.example.dvely.deployment.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deployment.domain.value.PackageManager;
import org.junit.jupiter.api.Test;

class DeployWorkflowTemplateTest {

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
