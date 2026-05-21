package com.example.dvely.deployment.infrastructure.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dvely.deployment.domain.value.PackageManager;
import org.junit.jupiter.api.Test;

class DeployWorkflowTemplateTest {

    @Test
    void generate_preservesExistingCustomDomainBeforePublishingGhPages() {
        String workflow = DeployWorkflowTemplate.generate("vue", null, PackageManager.NPM, "20");

        assertThat(workflow).contains("      - name: Preserve custom domain");
        assertThat(workflow).contains("git fetch origin gh-pages --depth=1");
        assertThat(workflow).contains("git show FETCH_HEAD:CNAME > /tmp/dvely-cname");
        assertThat(workflow).contains("cp /tmp/dvely-cname ./dist/CNAME");
        assertThat(workflow).containsSubsequence(
                "      - name: Preserve custom domain",
                "      - name: Deploy to gh-pages"
        );
    }
}
