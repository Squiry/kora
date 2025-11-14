package ru.tinkoff.kora.aws.s3;

import jakarta.annotation.Nullable;
import ru.tinkoff.kora.aws.s3.exception.S3ClientException;
import ru.tinkoff.kora.aws.s3.model.GetObjectResult;
import ru.tinkoff.kora.aws.s3.model.HeadObjectResult;
import ru.tinkoff.kora.aws.s3.model.ListMultipartUploadsResult;

import java.util.Objects;

public interface S3Client extends BaseS3Client {
    default HeadObjectResult headObject(AwsCredentials credentials, String bucket, String key) throws S3ClientException {
        return Objects.requireNonNull(this.headObject(credentials, bucket, key, true));
    }

    @Nullable
    default HeadObjectResult headObjectOptional(AwsCredentials credentials, String bucket, String key) throws S3ClientException {
        return this.headObject(credentials, bucket, key, false);
    }

    default void deleteObject(AwsCredentials credentials, String bucket, String key) throws S3ClientException {
        this.deleteObject(credentials, bucket, key, null);
    }

    default String createMultipartUpload(AwsCredentials credentials, String bucket, String key) throws S3ClientException {
        return this.createMultipartUpload(credentials, bucket, key, null, null);
    }

    default ListMultipartUploadsResult listMultipartUploads(AwsCredentials credentials, String bucket, @Nullable String prefix) throws S3ClientException {
        return listMultipartUploads(credentials, bucket, null, prefix, null, null);
    }

    default GetObjectResult getObject(AwsCredentials credentials, String bucket, String key) {
        return this.getObject(credentials, bucket, key, null, true);
    }
}
