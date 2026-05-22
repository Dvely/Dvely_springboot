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
        assertThat(workflow).contains("git show FETCH_HEAD:CNAME > /tmp/dvely-cname");
        assertThat(workflow).contains("cp /tmp/dvely-cname ./dist/CNAME");
        assertThat(workflow).containsSubsequence(
                "      - name: Preserve custom domain",
                "      - name: Deploy to gh-pages"
        );
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
}
