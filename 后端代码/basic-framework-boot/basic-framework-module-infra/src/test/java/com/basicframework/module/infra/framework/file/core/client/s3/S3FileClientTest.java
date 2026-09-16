package com.basicframework.module.infra.framework.file.core.client.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.infra.framework.file.core.client.FileObjectMetadata;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class S3FileClientTest {

    @Test
    void publicRuntime_supportsObjectLifecycleWithoutSigningReads() throws Exception {
        S3Client s3Client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        S3FileClient client = clientWithRuntime(publicConfig(), s3Client, presigner, "https://cdn.example.com");
        byte[] content = {1, 2, 3};
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(
                        GetObjectResponse.builder().build(),
                        AbortableInputStream.create(new ByteArrayInputStream(content))));
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder()
                        .contentLength(3L)
                        .contentType("text/plain")
                        .build());

        assertThat(client.upload(content, "documents/a.txt", "text/plain"))
                .isEqualTo("https://cdn.example.com/documents/a.txt");
        assertThat(client.getContent("documents/a.txt")).containsExactly(content);
        assertThat(client.getMetadata("documents/a.txt")).isEqualTo(new FileObjectMetadata(3L, "text/plain"));
        assertThat(client.supportsPrivatePresignedUpload()).isFalse();
        assertThat(client.supportsPrivateRead()).isFalse();
        client.delete("documents/a.txt");

        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));
        assertThat(putCaptor.getValue().bucket()).isEqualTo("bucket-a");
        assertThat(putCaptor.getValue().key()).isEqualTo("documents/a.txt");
        assertThat(putCaptor.getValue().contentLength()).isEqualTo(3L);
        verify(s3Client)
                .deleteObject(DeleteObjectRequest.builder()
                        .bucket("bucket-a")
                        .key("documents/a.txt")
                        .build());
    }

    @Test
    void metadata_returnsNullOnlyForNotFound() {
        S3Client s3Client = mock(S3Client.class);
        S3FileClient client =
                clientWithRuntime(publicConfig(), s3Client, mock(S3Presigner.class), "https://cdn.example.com");
        S3Exception notFound = (S3Exception)
                S3Exception.builder().statusCode(404).message("missing").build();
        S3Exception unavailable = (S3Exception)
                S3Exception.builder().statusCode(503).message("down").build();
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(notFound)
                .thenThrow(unavailable);

        assertThat(client.getMetadata("missing.txt")).isNull();
        assertThatThrownBy(() -> client.getMetadata("unavailable.txt")).isSameAs(unavailable);
    }

    @Test
    void privateRuntime_signsUploadsAndReadsWithExplicitContracts() throws Exception {
        S3Client s3Client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        PresignedPutObjectRequest signedPut = mock(PresignedPutObjectRequest.class);
        PresignedGetObjectRequest signedGet = mock(PresignedGetObjectRequest.class);
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(signedPut);
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(signedGet);
        when(signedPut.url()).thenReturn(new URL("https://upload.example.com/signed"));
        when(signedGet.url()).thenReturn(new URL("https://download.example.com/signed"));
        S3FileClientConfig config = publicConfig().setEnablePublicAccess(false);
        S3FileClient client = clientWithRuntime(config, s3Client, presigner, "https://private.example.com");

        assertThat(client.presignPutUrl("staging/a.txt", 12L, "text/plain", Duration.ofMinutes(5)))
                .isEqualTo("https://upload.example.com/signed");
        assertThat(client.presignGetUrl("https://private.example.com/final/a.txt?old=1", 90))
                .isEqualTo("https://download.example.com/signed");
        assertThat(client.supportsPrivatePresignedUpload()).isTrue();
        assertThat(client.supportsPrivateRead()).isTrue();

        ArgumentCaptor<PutObjectPresignRequest> putCaptor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(presigner).presignPutObject(putCaptor.capture());
        assertThat(putCaptor.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(putCaptor.getValue().putObjectRequest().contentLength()).isEqualTo(12L);
        assertThat(putCaptor.getValue().putObjectRequest().contentType()).isEqualTo("text/plain");
        ArgumentCaptor<GetObjectPresignRequest> getCaptor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(getCaptor.capture());
        assertThat(getCaptor.getValue().signatureDuration()).isEqualTo(Duration.ofSeconds(90));
        assertThat(getCaptor.getValue().getObjectRequest().key()).isEqualTo("final/a.txt");
    }

    @ParameterizedTest
    @CsvSource({
        "s3.us-west-2.amazonaws.com,us-west-2,https://bucket-a.s3.us-west-2.amazonaws.com",
        "s3.amazonaws.com,us-east-1,https://bucket-a.s3.amazonaws.com",
        "oss-cn-beijing.aliyuncs.com,cn-beijing,https://bucket-a.oss-cn-beijing.aliyuncs.com",
        "cos.ap-shanghai.myqcloud.com,ap-shanghai,https://bucket-a.cos.ap-shanghai.myqcloud.com",
        "http://localhost:9000,us-east-1,http://localhost:9000/bucket-a"
    })
    void init_derivesRegionAndDomainWithoutNetworkCalls(String endpoint, String region, String expectedDomain) {
        S3FileClientConfig config = baseConfig(endpoint).setEnablePublicAccess(false);
        S3FileClient client = new S3FileClient(11L, config);

        try {
            client.init();
            String signedUrl = client.presignGetUrl("folder/a.txt", 60);

            assertThat(signedUrl).contains("X-Amz-Signature=");
            assertThat(signedUrl).contains(region);
            String endpointHost =
                    endpoint.startsWith("http") ? URI.create(endpoint).getHost() : endpoint;
            assertThat(URI.create(signedUrl).getHost()).contains(endpointHost);
            assertThat(runtime(client).domain()).isEqualTo(expectedDomain);
            assertThat(config.getDomain()).isNull();
        } finally {
            client.close();
        }
    }

    @Test
    void close_releasesBothResourcesEvenWhenOneCloseFails() {
        S3Client s3Client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        S3FileClient client = clientWithRuntime(publicConfig(), s3Client, presigner, "https://cdn.example.com");
        doThrow(new IllegalStateException("client close failed")).when(s3Client).close();

        client.close();

        verify(s3Client).close();
        verify(presigner).close();
        assertThatThrownBy(() -> client.delete("a.txt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");
    }

    @Test
    void close_releasesPresignerAndPropagatesJvmError() {
        S3Client s3Client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        S3FileClient client = clientWithRuntime(publicConfig(), s3Client, presigner, "https://cdn.example.com");
        AssertionError fatalError = new AssertionError("fatal close failure");
        doThrow(fatalError).when(s3Client).close();

        assertThatThrownBy(client::close).isSameAs(fatalError);

        verify(s3Client).close();
        verify(presigner).close();
    }

    @Test
    void close_swallowsPresignerCloseFailureAfterClientClosed() {
        S3Client s3Client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        S3FileClient client = clientWithRuntime(publicConfig(), s3Client, presigner, "https://cdn.example.com");
        doThrow(new IllegalStateException("presigner close failed"))
                .when(presigner)
                .close();

        client.close();

        verify(s3Client).close();
        verify(presigner).close();
    }

    @Test
    void init_usesConfiguredRegionAndExplicitDomain() {
        S3FileClientConfig config = baseConfig("https://s3.example.net")
                .setDomain("https://cdn.example.net")
                .setRegion("eu-central-1")
                .setEnablePublicAccess(false);
        S3FileClient client = new S3FileClient(12L, config);

        try {
            client.init();
            String signedUrl = client.presignGetUrl("folder/a.txt", 60);

            assertThat(signedUrl).contains("eu-central-1");
            assertThat(runtime(client).domain()).isEqualTo("https://cdn.example.net");
        } finally {
            client.close();
        }
    }

    private static S3FileClient clientWithRuntime(
            S3FileClientConfig config, S3Client s3Client, S3Presigner presigner, String domain) {
        S3FileClient client = new S3FileClient(9L, config);
        ReflectionTestUtils.setField(
                client, "runtime", new S3FileClient.RuntimeState(s3Client, presigner, config, domain));
        return client;
    }

    private static S3FileClient.RuntimeState runtime(S3FileClient client) {
        return (S3FileClient.RuntimeState) ReflectionTestUtils.getField(client, "runtime");
    }

    private static S3FileClientConfig publicConfig() {
        return baseConfig("https://s3.example.com")
                .setDomain("https://cdn.example.com")
                .setEnablePublicAccess(true);
    }

    private static S3FileClientConfig baseConfig(String endpoint) {
        return new S3FileClientConfig()
                .setEndpoint(endpoint)
                .setBucket("bucket-a")
                .setAccessKey("access-key")
                .setAccessSecret("access-secret")
                .setEnablePathStyleAccess(false)
                .setEnablePublicAccess(true);
    }
}
