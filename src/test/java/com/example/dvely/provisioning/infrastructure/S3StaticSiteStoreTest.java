package com.example.dvely.provisioning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.dvely.cloudconnection.domain.model.CloudConnection;
import com.example.dvely.cloudconnection.infrastructure.external.AwsCredentialsResolver;
import org.junit.jupiter.api.Test;

class S3StaticSiteStoreTest {

    private final S3StaticSiteStore store = new S3StaticSiteStore(mock(AwsCredentialsResolver.class));

    @Test
    void bucketNameForIncludesAccountRegionAndProjectForGlobalUniqueness() {
        CloudConnection connection = mock(CloudConnection.class);
        when(connection.getAccountId()).thenReturn("123456789012");
        when(connection.getRegion()).thenReturn("ap-northeast-2");

        assertThat(store.bucketNameFor(connection, 7L))
                .isEqualTo("qeploy-site-123456789012-ap-northeast-2-7");
    }

    @Test
    void bucketNameForRejectsAConnectionWithoutAnAccountId() {
        CloudConnection connection = mock(CloudConnection.class);
        when(connection.getAccountId()).thenReturn("");

        assertThatThrownBy(() -> store.bucketNameFor(connection, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("계정 ID");
    }

    @Test
    void websiteEndpointUsesDashFormatForOlderRegions() {
        // us-east-1 은 s3-website-{region}(대시) 형식.
        assertThat(store.websiteEndpoint("qeploy-site-1-us-east-1-7", "us-east-1"))
                .isEqualTo("http://qeploy-site-1-us-east-1-7.s3-website-us-east-1.amazonaws.com");
    }

    @Test
    void websiteEndpointUsesDotFormatForNewerRegions() {
        // ap-northeast-2(서울)는 s3-website.{region}(점) 형식 — 대시로 만들면 실제 접속만 실패한다.
        assertThat(store.websiteEndpoint("qeploy-site-1-ap-northeast-2-7", "ap-northeast-2"))
                .isEqualTo("http://qeploy-site-1-ap-northeast-2-7.s3-website.ap-northeast-2.amazonaws.com");
    }
}
