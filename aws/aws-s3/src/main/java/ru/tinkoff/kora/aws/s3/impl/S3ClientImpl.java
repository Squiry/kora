package ru.tinkoff.kora.aws.s3.impl;

import io.opentelemetry.context.Context;
import jakarta.annotation.Nullable;
import ru.tinkoff.kora.aws.s3.AwsCredentials;
import ru.tinkoff.kora.aws.s3.S3Client;
import ru.tinkoff.kora.aws.s3.S3Config;
import ru.tinkoff.kora.aws.s3.exception.S3ClientErrorException;
import ru.tinkoff.kora.aws.s3.exception.S3ClientException;
import ru.tinkoff.kora.aws.s3.exception.S3ClientResponseException;
import ru.tinkoff.kora.aws.s3.exception.S3ClientUnknownException;
import ru.tinkoff.kora.aws.s3.impl.xml.DeleteObjectsRequest;
import ru.tinkoff.kora.aws.s3.impl.xml.DeleteObjectsResult;
import ru.tinkoff.kora.aws.s3.impl.xml.InitiateMultipartUploadResult;
import ru.tinkoff.kora.aws.s3.impl.xml.S3Error;
import ru.tinkoff.kora.aws.s3.model.HeadObjectResult;
import ru.tinkoff.kora.aws.s3.model.ListMultipartUploadsResult;
import ru.tinkoff.kora.aws.s3.model.ListMultipartUploadsResult.Upload;
import ru.tinkoff.kora.aws.s3.telemetry.NoopS3ClientTelemetry;
import ru.tinkoff.kora.aws.s3.telemetry.S3ClientTelemetry;
import ru.tinkoff.kora.common.telemetry.Observation;
import ru.tinkoff.kora.common.telemetry.OpentelemetryContext;
import ru.tinkoff.kora.http.client.common.HttpClient;
import ru.tinkoff.kora.http.client.common.request.HttpClientRequest;
import ru.tinkoff.kora.http.client.common.response.HttpClientResponse;
import ru.tinkoff.kora.http.common.body.HttpBody;
import ru.tinkoff.kora.http.common.body.HttpBodyInput;
import ru.tinkoff.kora.http.common.header.HttpHeaders;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class S3ClientImpl implements S3Client {
    private final S3ClientTelemetry telemetry = NoopS3ClientTelemetry.INSTANCE;
    private final HttpClient httpClient;
    private final S3Config config;
    private final UriHelper uriHelper;

    public S3ClientImpl(HttpClient httpClient, S3Config config) {
        this.httpClient = httpClient;
        this.config = config;
        this.uriHelper = new UriHelper(config);
    }

    @Nullable
    @Override
    public HeadObjectResult headObject(AwsCredentials credentials, String bucket, String key, boolean required) throws S3ClientException {
        var observation = this.telemetry.observe("HeadObject", bucket);
        observation.observeKey(key);
        return ScopedValue.where(Observation.VALUE, observation)
            .where(OpentelemetryContext.VALUE, Context.current().with(observation.span()))
            .call(() -> {
                var headers = HttpHeaders.of();
                var uri = this.uriHelper.uri(bucket, key);
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "HEAD", uri, Collections.emptySortedMap(), Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256);

                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256);

                var request = HttpClientRequest.of("HEAD", uri, "/{bucket}/{object}", headers, HttpBody.empty(), this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request)) {
                    var amxRequestId = rs.headers().getFirst("X-Amz-Request-Id");
                    observation.observeAwsRequestId(amxRequestId);
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == 200) {
                        var accessor = DateTimeFormatter.RFC_1123_DATE_TIME.parse(rs.headers().getFirst("Last-Modified"));
                        var modified = Instant.from(accessor);
                        var contentLength = rs.headers().getFirst("content-length");
                        var contentLengthLong = contentLength == null
                            ? 0L
                            : Long.parseLong(contentLength);
                        var etag = rs.headers().getFirst("ETag");
                        var versionId = rs.headers().getFirst("x-amz-version-id");
                        return new HeadObjectResult(bucket, key, etag, contentLengthLong, versionId, modified);
                    }
                    if (rs.code() == 404) {
                        if (required) {
                            throw new S3ClientErrorException(rs.code(), "NoSuchKey", "Object does not exist", amxRequestId);
                        } else {
                            return null;
                        }
                    }
                    // HEAD response cannot have body
                    try (var _ = rs.body()) {
                        throw new S3ClientResponseException("Unexpected response from s3: code=%s".formatted(rs.code()), rs.code());
                    }
                } catch (S3ClientException e) {
                    observation.observeError(e);
                    throw e;
                } catch (Exception e) {
                    observation.observeError(e);
                    throw new S3ClientUnknownException(e);
                } finally {
                    observation.end();
                }
            });
    }

    @Override
    public void deleteObject(AwsCredentials credentials, String bucket, String key, @Nullable String versionId) throws S3ClientException {
        var observation = this.telemetry.observe("DeleteObject", bucket);
        observation.observeKey(key);
        ScopedValue.where(Observation.VALUE, observation)
            .where(OpentelemetryContext.VALUE, Context.current().with(observation.span()))
            .run(() -> {
                var uri = this.uriHelper.uri(bucket, key);
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "DELETE", uri, Collections.emptySortedMap(), Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256);

                var headers = HttpHeaders.of();
                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256);

                var request = HttpClientRequest.of("DELETE", uri, "/{bucket}/{key}", headers, HttpBody.empty(), this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request);
                     var body = rs.body()) {
                    observation.observeAwsRequestId(rs.headers().getFirst("X-Amz-Request-Id"));
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == 204) {
                        return;
                    }
                    if (rs.code() == 404) { // no such bucket
                        return;
                    }
                    throw parseS3Exception(rs, body);
                } catch (S3ClientException e) {
                    observation.observeError(e);
                    throw e;
                } catch (Throwable e) {
                    observation.observeError(e);
                    throw new S3ClientUnknownException(e);
                } finally {
                    observation.end();
                }
            });
    }

    @Override
    public void deleteObjects(AwsCredentials credentials, String bucket, List<String> keys) throws S3ClientException {
        var observation = this.telemetry.observe("DeleteObjects", bucket);
        ScopedValue.where(Observation.VALUE, observation)
            .where(OpentelemetryContext.VALUE, Context.current().with(observation.span()))
            .run(() -> {
                var xml = DeleteObjectsRequest.toXml(keys.stream().map(DeleteObjectsRequest.S3Object::new)::iterator);
                var payloadSha256 = DigestUtils.sha256(xml, 0, xml.length).hex();
                var bodyMd5 = DigestUtils.md5(xml, 0, xml.length).base64();
                var headers = HttpHeaders.of(
                    "content-md5", bodyMd5
                );
                var uri = this.uriHelper.uri(bucket, "?delete=true");
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());

                var signature = signer.processRequest(this.config.region(), "s3", "POST", uri, new TreeMap<>(Map.of("delete", "true")), Map.of("content-md5", bodyMd5), payloadSha256);

                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", payloadSha256);

                var request = HttpClientRequest.of("POST", uri, "/{bucket}", headers, HttpBody.of(xml), this.config.requestTimeout());

                try (var rs = this.httpClient.execute(request);
                     var body = rs.body()) {
                    observation.observeAwsRequestId(rs.headers().getFirst("X-Amz-Request-Id"));
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == 200) {
                        var ignore = DeleteObjectsResult.fromXml(body.asInputStream());
                        return;
                    }
                    if (rs.code() == 404) { // no such bucket
                        return;
                    }
                    throw parseS3Exception(rs, body);
                } catch (S3ClientException e) {
                    observation.observeError(e);
                    throw e;
                } catch (Throwable e) {
                    observation.observeError(e);
                    throw new S3ClientUnknownException(e);
                } finally {
                    observation.end();
                }
            });
    }

    @Override
    public String createMultipartUpload(AwsCredentials credentials, String bucket, String key, @Nullable String contentType, @Nullable String contentEncoding) throws S3ClientException {
        var observation = this.telemetry.observe("CreateMultipartUpload", bucket);
        observation.observeKey(key);
        return ScopedValue.where(Observation.VALUE, observation)
            .where(OpentelemetryContext.VALUE, Context.current().with(observation.span()))
            .call(() -> {
                var headers = HttpHeaders.of();
                var uri = this.uriHelper.uri(bucket, key + "?uploads=true");
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "POST", uri, new TreeMap<>(Map.of("uploads", "true")), Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256);

                if (contentEncoding != null) {
                    headers.add("content-encoding", contentEncoding);
                }
                if (contentType != null) {
                    headers.add("content-type", contentType);
                }
                headers.add("x-amz-checksum-algorithm", "SHA256");
                headers.add("x-amz-checksum-type", "COMPOSITE");

                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256);

                var request = HttpClientRequest.of("POST", uri, "/{bucket}?uploads=true", headers, HttpBody.empty(), this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request);
                     var body = rs.body()) {
                    observation.observeAwsRequestId(rs.headers().getFirst("X-Amz-Request-Id"));
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == 200) {
                        var result = InitiateMultipartUploadResult.fromXml(body.asInputStream());
                        observation.observeUploadId(result.uploadId());
                        return result.uploadId();
                    }
                    throw S3ClientImpl.parseS3Exception(rs, body);
                } catch (S3ClientException e) {
                    observation.observeError(e);
                    throw e;
                } catch (Throwable e) {
                    observation.observeError(e);
                    throw new S3ClientUnknownException(e);
                } finally {
                    observation.end();
                }
            });
    }

    @Override
    public void abortMultipartUpload(AwsCredentials credentials, String bucket, String key, String uploadId) throws S3ClientException {
        var observation = this.telemetry.observe("AbortMultipartUpload", bucket);
        observation.observeKey(key);
        ScopedValue.where(Observation.VALUE, observation)
            .where(OpentelemetryContext.VALUE, Context.current().with(observation.span()))
            .run(() -> {
                var uri = this.uriHelper.uri(bucket, key + "?uploadId=" + uploadId);
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "DELETE", uri, new TreeMap<>(Map.of("uploadId", uploadId)), Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256);

                var headers = HttpHeaders.of();
                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256);

                var request = HttpClientRequest.of("DELETE", uri, "/{bucket}/{key}?uploadId={uploadId}", headers, HttpBody.empty(), this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request);
                     var body = rs.body()) {
                    observation.observeAwsRequestId(rs.headers().getFirst("X-Amz-Request-Id"));
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == 204) {
                        return;
                    }
                    throw S3ClientImpl.parseS3Exception(rs, body);
                } catch (S3ClientException e) {
                    observation.observeError(e);
                    throw e;
                } catch (Throwable e) {
                    observation.observeError(e);
                    throw new S3ClientUnknownException(e);
                } finally {
                    observation.end();
                }
            });
    }

    @Override
    public ListMultipartUploadsResult listMultipartUploads(AwsCredentials credentials, String bucket, @Nullable String keyMarker, @Nullable String prefix, @Nullable String delimiter, @Nullable Integer maxUploads) throws S3ClientException {
        var observation = this.telemetry.observe("ListMultipartUploads", bucket);
        return ScopedValue.where(Observation.VALUE, observation)
            .where(OpentelemetryContext.VALUE, Context.current().with(observation.span()))
            .call(() -> {
                var headers = HttpHeaders.of();
                var query = new StringBuilder("?uploads=true");
                var queryMap = new TreeMap<String, String>();
                queryMap.put("uploads", "true");
                if (keyMarker != null) {
                    query.append("?key-marker=").append(keyMarker);
                    queryMap.put("key-marker", keyMarker);
                }
                if (prefix != null) {
                    query.append("&prefix=").append(prefix);
                    queryMap.put("prefix", prefix);
                }
                if (delimiter != null) {
                    query.append("&delimiter=").append(delimiter);
                    queryMap.put("delimiter", delimiter);
                }
                if (maxUploads != null) {
                    query.append("&max-uploads=").append(maxUploads);
                    queryMap.put("max-uploads", String.valueOf(maxUploads));
                }
                var uri = this.uriHelper.uri(bucket, query.toString());
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "GET", uri, queryMap, Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256);

                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256);

                var request = HttpClientRequest.of("GET", uri, "/{bucket}/{object}", headers, HttpBody.empty(), this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request)) {
                    var amxRequestId = rs.headers().getFirst("X-Amz-Request-Id");
                    observation.observeAwsRequestId(amxRequestId);
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == 200) {
                        var xml = ru.tinkoff.kora.aws.s3.impl.xml.ListMultipartUploadsResult.fromXml(rs.body().asInputStream());
                        var uploads = new ArrayList<Upload>();
                        for (var upload : xml.uploads()) {
                            uploads.add(new Upload(
                                upload.key(),
                                upload.uploadId(),
                                upload.initiated()
                            ));
                        }
                        return new ListMultipartUploadsResult(
                            xml.nextKeyMarker(),
                            xml.nextUploadIdMarker(),
                            uploads
                        );
                    }
                    try (var body = rs.body()) {
                        throw S3ClientImpl.parseS3Exception(rs, body);
                    }
                } catch (S3ClientException e) {
                    observation.observeError(e);
                    throw e;
                } catch (Exception e) {
                    observation.observeError(e);
                    throw new S3ClientUnknownException(e);
                } finally {
                    observation.end();
                }
            });

    }

    static S3ClientException parseS3Exception(HttpClientResponse rs, HttpBodyInput body) {
        try (var is = body.asInputStream()) {
            var bytes = is.readAllBytes();
            try {
                var s3Error = S3Error.fromXml(new ByteArrayInputStream(bytes));
                throw new S3ClientErrorException(rs.code(), s3Error.code(), s3Error.message(), s3Error.requestId());
            } catch (S3ClientException e) {
                throw e;
            } catch (Exception e) {
                throw new S3ClientResponseException("Unexpected response from s3: code=%s, body=%s".formatted(rs.code(), new String(bytes, StandardCharsets.UTF_8)), e, rs.code());
            }
        } catch (IOException e) {
            throw new S3ClientUnknownException(e);
        }
    }
}
