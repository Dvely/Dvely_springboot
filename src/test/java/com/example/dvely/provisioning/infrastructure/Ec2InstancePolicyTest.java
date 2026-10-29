package com.example.dvely.provisioning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class Ec2InstancePolicyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aTargetBuildMayWriteOnlyItsOwnBuildStatus() throws Exception {
        JsonNode policy = mapper.readTree(Ec2InstanceRoleProvisioner.instancePolicy(42L, "qeploy-artifacts-x", false, true));

        JsonNode put = null;
        for (JsonNode statement : policy.get("Statement")) {
            if (statement.get("Action").toString().contains("ssm:PutParameter")) {
                put = statement;
            }
        }
        assertThat(put).isNotNull();
        assertThat(put.get("Resource").asText()).isEqualTo("arn:aws:ssm:*:*:parameter/qeploy/build-status/42/*");
    }

    @Test
    void otherwiseTheInstanceWritesNothingToSsm() throws Exception {
        String policy = Ec2InstanceRoleProvisioner.instancePolicy(42L, "qeploy-artifacts-x", true, false);

        assertThat(mapper.readTree(policy).get("Statement").isArray()).isTrue();
        assertThat(policy).doesNotContain("ssm:PutParameter");
    }
}
