package ru.tinkoff.kora.aws.s3;

import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import ru.tinkoff.kora.aws.s3.exception.S3ClientErrorException;
import ru.tinkoff.kora.aws.s3.exception.S3ClientResponseException;
import ru.tinkoff.kora.aws.s3.impl.S3ClientImpl;
import ru.tinkoff.kora.http.client.ok.OkHttpClient;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3ClientTest {
    static GenericContainer<?> minio = new GenericContainer<>(DockerImageName.parse("minio/minio"))
        .withCommand("server", "/home/shared")
        .withEnv("SERVICES", "s3")
        .withStartupTimeout(Duration.ofMinutes(1))
        .withNetworkAliases("s3")
        .withExposedPorts(9000);
    static okhttp3.OkHttpClient ok = new okhttp3.OkHttpClient.Builder()
//        .addInterceptor(new HttpLoggingInterceptor(System.out::println).setLevel(HttpLoggingInterceptor.Level.HEADERS))
        .build();
    static MinioClient minioClient;

    AwsCredentials credentials = AwsCredentials.of("minioadmin", "minioadmin");
    AwsCredentials invalidCredentials = AwsCredentials.of("test", "test");
    S3Config config;

    @BeforeAll
    static void beforeAll() throws Exception {
        minio.start();
        minioClient = MinioClient.builder()
            .httpClient(ok)
            .endpoint("http://" + minio.getHost() + ":" + minio.getMappedPort(9000))
            .credentials("minioadmin", "minioadmin")
            .build();
        minioClient.makeBucket(MakeBucketArgs.builder()
            .bucket("test")
            .build());
    }

    @AfterAll
    static void afterAll() {
        minio.stop();
    }


    @BeforeEach
    void setUp() {
        this.config = mock(S3Config.class);
        when(config.endpoint()).thenReturn("http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        when(config.addressStyle()).thenReturn(S3Config.AddressStyle.PATH);
        when(config.region()).thenReturn("us-east-1");
        when(config.upload()).thenReturn(Mockito.mock());
        when(config.upload().singlePartUploadLimit()).thenCallRealMethod();
        when(config.upload().chunkSize()).thenCallRealMethod();
        when(config.upload().partSize()).thenCallRealMethod();
    }

    S3Client s3Client(String accessKey, String secretKey) {

        var httpClient = new OkHttpClient(ok);
        return new S3ClientImpl(httpClient, config);
    }

    S3Client s3Client() {
        return s3Client("minioadmin", "minioadmin");
    }

    @Nested
    class HeadObject {

        @Test
        void testHeadObjectThrowsErrorOnUnknownObject() throws Exception {
            assertThatThrownBy(() -> s3Client().headObject(credentials, "test", UUID.randomUUID().toString()))
                .isInstanceOf(S3ClientErrorException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NoSuchKey")
                .hasFieldOrPropertyWithValue("errorMessage", "Object does not exist");
        }

        @Test
        void testHeadObjectThrowsErrorOnUnknownBucket() throws Exception {
            // HEAD throws 404 without a body (because HEAD has no body), so we cannot read code and message and detect if it's no bucket or no key
            assertThatThrownBy(() -> s3Client().headObject(credentials, UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(S3ClientErrorException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NoSuchKey")
                .hasFieldOrPropertyWithValue("errorMessage", "Object does not exist");
        }

        @Test
        void testHeadObjectForbidden() throws Exception {
            assertThatThrownBy(() -> s3Client().headObject(invalidCredentials, UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                .isInstanceOf(S3ClientResponseException.class)
                .hasFieldOrPropertyWithValue("httpCode", 403);
        }

        @Test
        void testHeadObjectOptionalObjectReturnsNullOnUnknownObjects() {
            var object = s3Client().headObjectOptional(credentials, "test", UUID.randomUUID().toString());
            assertThat(object).isNull();
        }

        @Test
        void testHeadObjectOptionalObjectReturnsNullOnUnknownBucket() {
            var object = s3Client().headObjectOptional(credentials, UUID.randomUUID().toString(), UUID.randomUUID().toString());
            assertThat(object).isNull();
        }

        @Test
        void testHeadObjectdataValidObject() throws Exception {
            var key = UUID.randomUUID().toString();
            var content = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
            minioClient.putObject(PutObjectArgs.builder()
                .bucket("test")
                .object(key)
                .contentType("text/plain")
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .build());
            var metadata = s3Client().headObject(credentials, "test", key);
            assertThat(metadata).isNotNull();
            assertThat(metadata.bucket()).isEqualTo("test");
            assertThat(metadata.key()).isEqualTo(key);
            assertThat(metadata.size()).isEqualTo(content.length);
        }

        @Test
        void testGetOptionalMetadataValidObject() throws Exception {
            var key = UUID.randomUUID().toString();
            var content = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
            minioClient.putObject(PutObjectArgs.builder()
                .bucket("test")
                .object(key)
                .contentType("text/plain")
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .build());
            var metadata = s3Client().headObjectOptional(credentials, "test", key);
            assertThat(metadata).isNotNull();
            assertThat(metadata.bucket()).isEqualTo("test");
            assertThat(metadata.key()).isEqualTo(key);
            assertThat(metadata.size()).isEqualTo(content.length);
        }
    }

    @Nested
    class Delete {
        @Test
        void testDeleteObjectSuccessOnValidObject() throws Exception {
            var key = UUID.randomUUID().toString();
            var content = randomBytes(1024);
            minioClient.putObject(PutObjectArgs.builder()
                .bucket("test")
                .object(key)
                .contentType("text/plain")
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .build());

            s3Client().deleteObject(credentials, "test", key);

            assertThatThrownBy(() -> minioClient.getObject(GetObjectArgs.builder()
                .bucket("test")
                .object(key)
                .build()))
                .isInstanceOf(ErrorResponseException.class)
                .extracting("errorResponse")
                .hasFieldOrPropertyWithValue("code", "NoSuchKey");
        }

        @Test
        void testDeleteObjectSuccessOnObjectThatDoesNotExist() throws Exception {
            var key = UUID.randomUUID().toString();

            s3Client().deleteObject(credentials, "test", key);
        }

        @Test
        void testDeleteObjectSuccessOnBucketThatDoesNotExist() throws Exception {
            var key = UUID.randomUUID().toString();

            s3Client().deleteObject(credentials, key, key);
        }

        @Test
        void testDeleteObjectAccessError() {
            var key = UUID.randomUUID().toString();
            assertThatThrownBy(() -> s3Client().deleteObject(invalidCredentials, key, key))
                .isInstanceOf(S3ClientResponseException.class)
                .hasFieldOrPropertyWithValue("httpCode", 403);
        }

        @Test
        void testDeleteObjects() throws Exception {
            var key1 = UUID.randomUUID().toString();
            var key2 = UUID.randomUUID().toString();
            var key3 = UUID.randomUUID().toString();
            var content = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
            minioClient.putObject(PutObjectArgs.builder()
                .bucket("test")
                .object(key1)
                .contentType("text/plain")
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .build());
            minioClient.putObject(PutObjectArgs.builder()
                .bucket("test")
                .object(key2)
                .contentType("text/plain")
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .build());

            s3Client().deleteObjects(credentials, "test", List.of(key1, key2, key3));

            assertThatThrownBy(() -> minioClient.getObject(GetObjectArgs.builder()
                .bucket("test")
                .object(key1)
                .build()))
                .isInstanceOf(ErrorResponseException.class)
                .extracting("errorResponse")
                .hasFieldOrPropertyWithValue("code", "NoSuchKey");
            assertThatThrownBy(() -> minioClient.getObject(GetObjectArgs.builder()
                .bucket("test")
                .object(key2)
                .build()))
                .isInstanceOf(ErrorResponseException.class)
                .extracting("errorResponse")
                .hasFieldOrPropertyWithValue("code", "NoSuchKey");
            assertThatThrownBy(() -> minioClient.getObject(GetObjectArgs.builder()
                .bucket("test")
                .object(key3)
                .build()))
                .isInstanceOf(ErrorResponseException.class)
                .extracting("errorResponse")
                .hasFieldOrPropertyWithValue("code", "NoSuchKey");
        }
    }

    @Nested
    class Multipart {
        @Test
        void testCreateMultipartUpload() throws Exception {
            var prefix = UUID.randomUUID().toString();
            var key = UUID.randomUUID().toString();

            var uploadId = s3Client().createMultipartUpload(credentials, "test", prefix + "/" + key);

            assertThat(uploadId).isNotNull();

            var listResult = s3Client().listMultipartUploads(credentials, "test", null);

            assertThat(listResult.uploads()).hasSize(1);
            assertThat(listResult.uploads().getFirst().uploadId()).isEqualTo(uploadId);
            assertThat(listResult.uploads().getFirst().key()).isEqualTo(prefix + "/" + key);

            s3Client().abortMultipartUpload(credentials, "test", prefix + "/" + key, uploadId);
        }

        @Test
        void testAbortMultipartUpload() throws Exception {
            var prefix = UUID.randomUUID().toString();
            var key = UUID.randomUUID().toString();

            var uploadId = s3Client().createMultipartUpload(credentials, "test", prefix + "/" + key);
            s3Client().abortMultipartUpload(credentials, "test", prefix + "/" + key, uploadId);

            var afterListResult = s3Client().listMultipartUploads(credentials, "test", null);

            assertThat(afterListResult.uploads()).isEmpty();
        }

    }


    byte[] randomBytes(long len) {
        var bytes = new byte[Math.toIntExact(len)];
        ThreadLocalRandom.current().nextBytes(bytes);
        return bytes;
    }
}
