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
import ru.tinkoff.kora.aws.s3.impl.xml.*;
import ru.tinkoff.kora.aws.s3.model.*;
import ru.tinkoff.kora.aws.s3.model.ListMultipartUploadsResult;
import ru.tinkoff.kora.aws.s3.model.ListMultipartUploadsResult.Upload;
import ru.tinkoff.kora.aws.s3.model.ListPartsResult;
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
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
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
                var uri = this.uriHelper.uri(bucket, key, null);
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "HEAD", uri, Collections.emptySortedMap(), Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                var request = HttpClientRequest.of("HEAD", uri, "/{bucket}/{object}", headers, HttpBody.empty(), this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request)) {
                    var amxRequestId = rs.headers().getFirst("X-Amz-Request-Id");
                    observation.observeAwsRequestId(amxRequestId);
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == HttpURLConnection.HTTP_OK) {
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
                    if (rs.code() == HttpURLConnection.HTTP_NOT_FOUND) {
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

    @Nullable
    @Override
    public GetObjectResult getObject(AwsCredentials credentials, String bucket, String key, @Nullable RangeData range, boolean required) {
        var observation = this.telemetry.observe("GetObject", bucket);
        observation.observeKey(key);
        return ScopedValue.where(Observation.VALUE, observation)
            .where(OpentelemetryContext.VALUE, Context.current().with(observation.span()))
            .call(() -> {
                var headers = HttpHeaders.of();
                var uri = this.uriHelper.uri(bucket, key, null);
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());

                switch (range) {
                    case RangeData.Range(var from, var to) -> headers.add("range", "bytes=" + from + "-" + to);
                    case RangeData.StartFrom(var from) -> headers.add("range", "bytes=" + from + "-");
                    case RangeData.LastN(var bytes) -> headers.add("range", "bytes=-" + bytes);
                    case null -> {}
                }
                var signature = signer.processRequest(this.config.region(), "s3", "GET", uri, Collections.emptySortedMap(), Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                var request = HttpClientRequest.of("GET", uri, "/{bucket}/{object}", headers, HttpBody.empty(), this.config.requestTimeout());
                try {
                    var rs = this.httpClient.execute(request);
                    var amxRequestId = rs.headers().getFirst("X-Amz-Request-Id");
                    observation.observeAwsRequestId(amxRequestId);
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == HttpURLConnection.HTTP_OK || rs.code() == HttpURLConnection.HTTP_PARTIAL) {
                        return new GetObjectResultImpl(rs);
                    }
                    try (rs) {
                        if (rs.code() == HttpURLConnection.HTTP_NOT_FOUND && !required) {
                            return null;
                        }
                        try (var body = rs.body()) {
                            throw parseS3Exception(rs, body);
                        }
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
                var uri = this.uriHelper.uri(bucket, key, null);
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "DELETE", uri, Collections.emptySortedMap(), Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                var headers = HttpHeaders.of();
                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

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
                var uri = this.uriHelper.uri(bucket, "/", "delete=true");
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
                    if (rs.code() == HttpURLConnection.HTTP_OK) {
                        var ignore = DeleteObjectsResult.fromXml(body.asInputStream());
                        return;
                    }
                    if (rs.code() == HttpURLConnection.HTTP_NOT_FOUND) { // no such bucket
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
                var uri = this.uriHelper.uri(bucket, key, "uploads=true");
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "POST", uri, new TreeMap<>(Map.of("uploads", "true")), Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

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
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                var request = HttpClientRequest.of("POST", uri, "/{bucket}?uploads=true", headers, HttpBody.empty(), this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request);
                     var body = rs.body()) {
                    observation.observeAwsRequestId(rs.headers().getFirst("X-Amz-Request-Id"));
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == HttpURLConnection.HTTP_OK) {
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
                var uri = this.uriHelper.uri(bucket, key, "uploadId=" + uploadId);
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "DELETE", uri, new TreeMap<>(Map.of("uploadId", uploadId)), Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                var headers = HttpHeaders.of();
                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                var request = HttpClientRequest.of("DELETE", uri, "/{bucket}/{key}?uploadId={uploadId}", headers, HttpBody.empty(), this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request);
                     var body = rs.body()) {
                    observation.observeAwsRequestId(rs.headers().getFirst("X-Amz-Request-Id"));
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == HttpURLConnection.HTTP_NO_CONTENT) {
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
    public String completeMultipartUpload(AwsCredentials credentials, String bucket, String key, String uploadId, List<UploadedPart> parts) throws S3ClientException {
        var observation = this.telemetry.observe("CompleteMultipartUpload", bucket);
        observation.observeKey(key);
        return ScopedValue.where(Observation.VALUE, observation)
            .where(OpentelemetryContext.VALUE, Context.current().with(observation.span()))
            .call(() -> {
                var rqParts = new ArrayList<CompleteMultipartUploadRequest.Part>();
                for (var part : parts) {
                    rqParts.add(new CompleteMultipartUploadRequest.Part(
                        part.checksumCRC32(),
                        part.checksumCRC32C(),
                        part.checksumCRC64NVME(),
                        part.checksumSHA1(),
                        part.checksumSHA256(),
                        part.eTag(),
                        part.partNumber()
                    ));
                }
                var completeRequest = new CompleteMultipartUploadRequest(rqParts);
                var xml = completeRequest.toXml();
                var sha256 = DigestUtils.sha256(xml, 0, xml.length).hex();
                var uri = this.uriHelper.uri(bucket, key, "uploadId=" + uploadId);
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var completeSize = Long.toString(parts.stream().mapToLong(UploadedPart::size).sum());
                var signature = signer.processRequest(this.config.region(), "s3", "POST", uri, new TreeMap<>(Map.of("uploadId", uploadId)), Map.of("x-amz-mp-object-size", completeSize), sha256);

                var headers = HttpHeaders.of();
                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", sha256);
                headers.set("x-amz-mp-object-size", completeSize);

                var request = HttpClientRequest.of("POST", uri, "/{bucket}/{key}?uploadId={uploadId}", headers, HttpBody.of("text/xml", xml), this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request);
                     var body = rs.body()) {
                    observation.observeAwsRequestId(rs.headers().getFirst("X-Amz-Request-Id"));
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == HttpURLConnection.HTTP_OK) {
                        var result = CompleteMultipartUploadResult.fromXml(body.asInputStream());
                        return result.etag();
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
                var query = new StringBuilder("uploads=true");
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
                var uri = this.uriHelper.uri(bucket, "", query.toString());
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "GET", uri, queryMap, Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                var request = HttpClientRequest.of("GET", uri, "/{bucket}/{object}", headers, HttpBody.empty(), this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request)) {
                    var amxRequestId = rs.headers().getFirst("X-Amz-Request-Id");
                    observation.observeAwsRequestId(amxRequestId);
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == HttpURLConnection.HTTP_OK) {
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

    @Override
    public UploadedPart uploadPart(AwsCredentials credentials, String bucket, String key, String uploadId, int partNumber, byte[] data, int off, int len) throws S3ClientException {
        var observation = this.telemetry.observe("UploadPart", bucket);
        observation.observeKey(key);
        return ScopedValue.where(Observation.VALUE, observation)
            .where(OpentelemetryContext.VALUE, Context.current().with(observation.span()))
            .call(() -> {
                var sha256 = DigestUtils.sha256(data, 0, len);
                var sha256Hex = sha256.hex();
                var sha256Base64 = sha256.base64();
                var md5 = DigestUtils.md5(data, 0, len).base64();
                var headersMap = Map.of(
                    "content-length", Integer.toString(len),
                    "content-md5", md5,
                    "x-amz-checksum-sha256", sha256Base64
                );
                var queryParams = new TreeMap<String, String>();
                queryParams.put("partNumber", Integer.toString(partNumber));
                queryParams.put("uploadId", uploadId);
                var uri = this.uriHelper.uri(bucket, key, "partNumber=" + partNumber + "&uploadId=" + uploadId);
                var httpBody = HttpBody.of(ByteBuffer.wrap(data, off, len));
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "PUT", uri, queryParams, headersMap, sha256Hex);

                var headers = HttpHeaders.of();
                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", sha256Hex);
                headers.set("content-md5", md5);
                headers.set("x-amz-checksum-sha256", sha256Base64);

                var request = HttpClientRequest.of("PUT", uri, "/{bucket}/{key}?partNumber={partNumber}&uploadId={uploadId}", headers, httpBody, this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request);
                     var body = rs.body()) {
                    observation.observeAwsRequestId(rs.headers().getFirst("X-Amz-Request-Id"));
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == HttpURLConnection.HTTP_OK) {
                        var etag = rs.headers().getFirst("ETag");
                        return new UploadedPart(
                            null, null, null, null, sha256Base64, etag, partNumber, len
                        );
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
    public UploadedPart uploadPart(AwsCredentials credentials, String bucket, String key, String uploadId, int partNumber, ContentWriter contentWriter, long len) throws S3ClientException {
        var observation = this.telemetry.observe("UploadPart", bucket);
        observation.observeKey(key);
        return ScopedValue.where(Observation.VALUE, observation)
            .where(OpentelemetryContext.VALUE, Context.current().with(observation.span()))
            .call(() -> {
                var payloadSha256Hex = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER";
                var headersMap = Map.of(
                    "x-amz-trailer", "x-amz-checksum-sha256",
                    "x-amz-decoded-content-length", Long.toString(len),
                    "expect", "100-continue",
                    "content-encoding", "aws-chunked"
                );
                var queryParams = new TreeMap<String, String>();
                queryParams.put("partNumber", Integer.toString(partNumber));
                queryParams.put("uploadId", uploadId);
                var uri = this.uriHelper.uri(bucket, key, "partNumber=" + partNumber + "&uploadId=" + uploadId);
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());

                var signature = signer.processRequest(this.config.region(), "s3", "PUT", uri, queryParams, headersMap, payloadSha256Hex);

                var headers = HttpHeaders.of();
                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", payloadSha256Hex);
                headers.set("x-amz-trailer", "x-amz-checksum-sha256");
                headers.set("x-amz-decoded-content-length", Long.toString(len));
                headers.set("expect", "100-continue");
                headers.set("content-encoding", "aws-chunked");

                var httpBody = new KnownSizeAwsChunkedHttpBody(
                    signer,
                    this.config.region(),
                    (int) this.config.upload().chunkSize().toBytes(),
                    "application/octet-stream",
                    signature.signature(),
                    contentWriter,
                    len,
                    null
                );
                var request = HttpClientRequest.of("PUT", uri, "/{bucket}/{key}?partNumber={partNumber}&uploadId={uploadId}", headers, httpBody, this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request);
                     var body = rs.body()) {
                    observation.observeAwsRequestId(rs.headers().getFirst("X-Amz-Request-Id"));
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == HttpURLConnection.HTTP_OK) {
                        var etag = rs.headers().getFirst("ETag");
                        return new UploadedPart(
                            null, null, null, null, httpBody.sha256(), etag, partNumber, len
                        );
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
    public ListPartsResult listParts(AwsCredentials credentials, String bucket, String key, String uploadId, @Nullable Integer maxParts, @Nullable Integer partNumberMarker) throws S3ClientException {
        var observation = this.telemetry.observe("ListParts", bucket);
        observation.observeKey(key);
        return ScopedValue.where(Observation.VALUE, observation)
            .where(OpentelemetryContext.VALUE, Context.current().with(observation.span()))
            .call(() -> {
                var headers = HttpHeaders.of();
                var queryParams = new TreeMap<String, String>();
                queryParams.put("uploadId", uploadId);
                var query = new StringBuilder("uploadId=").append(uploadId);
                if (maxParts != null) {
                    var str = Integer.toString(maxParts);
                    query.append("&max-parts=").append(str);
                    queryParams.put("max-parts", str);
                }
                if (partNumberMarker != null) {
                    var str = Integer.toString(partNumberMarker);
                    query.append("&part-number-marker=").append(str);
                    queryParams.put("part-number-marker", str);
                }
                var uri = this.uriHelper.uri(bucket, key, query.toString());
                var signer = credentials instanceof AwsRequestSigner s
                    ? s
                    : new AwsRequestSigner(credentials.accessKey(), credentials.secretKey());
                var signature = signer.processRequest(this.config.region(), "s3", "GET", uri, queryParams, Map.of(), AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                headers.set("x-amz-date", signature.amzDate());
                headers.set("authorization", signature.authorization());
                headers.set("host", uri.getAuthority());
                headers.set("x-amz-content-sha256", AwsRequestSigner.EMPTY_PAYLOAD_SHA256_HEX);

                var request = HttpClientRequest.of("GET", uri, "/{bucket}/{key}?uploadId={uploadId}", headers, HttpBody.empty(), this.config.requestTimeout());
                try (var rs = this.httpClient.execute(request);
                     var body = rs.body()) {
                    observation.observeAwsRequestId(rs.headers().getFirst("X-Amz-Request-Id"));
                    observation.observeAwsExtendedId(rs.headers().getFirst("x-amz-id-2"));
                    if (rs.code() == HttpURLConnection.HTTP_OK) {
                        var listPartsResult = ru.tinkoff.kora.aws.s3.impl.xml.ListPartsResult.fromXml(body.asInputStream());
                        observation.observeUploadId(listPartsResult.uploadId());
                        var parts = new ArrayList<UploadedPart>(listPartsResult.parts().size());
                        for (var part : listPartsResult.parts()) {
                            parts.add(new UploadedPart(
                                part.checksumCRC32(),
                                part.checksumCRC32C(),
                                part.checksumCRC64NVME(),
                                part.checksumSHA1(),
                                part.checksumSHA256(),
                                part.eTag(),
                                part.partNumber(),
                                part.size()
                            ));
                        }
                        return new ListPartsResult(
                            listPartsResult.partNumberMarker(),
                            listPartsResult.nextPartNumberMarker(),
                            listPartsResult.isTruncated(),
                            parts
                        );
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
