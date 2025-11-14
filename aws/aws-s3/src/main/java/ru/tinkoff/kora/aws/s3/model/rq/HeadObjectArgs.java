package ru.tinkoff.kora.aws.s3.model.rq;

import jakarta.annotation.Nullable;
import ru.tinkoff.kora.http.common.header.MutableHttpHeaders;

import java.util.Map;

/**
 * @see <a href="HeadObject">https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html</a>
 */
public class HeadObjectArgs {
    /**
     * Part number of the object being read.
     * This is a positive integer between 1 and 10,000.
     * Effectively performs a 'ranged' HEAD request for the part specified.
     * Useful querying about the size of the part and the number of parts in this object.
     */
    @Nullable
    public Integer partNumber;

    /**
     * Sets the Cache-Control header of the response.
     */
    @Nullable
    public String responseCacheControl;

    /**
     * Sets the Content-Disposition header of the response.
     */
    @Nullable
    public String responseContentDisposition;

    /**
     * Sets the Content-Encoding header of the response.
     */
    @Nullable
    public String responseContentEncoding;

    /**
     * Sets the Content-Language header of the response.
     */
    @Nullable
    public String responseContentLanguage;

    /**
     * Sets the Content-Type header of the response.
     */
    @Nullable
    public String responseContentType;

    /**
     * Sets the Expires header of the response.
     */
    @Nullable
    public String responseExpires;

    /**
     * Version ID used to reference a specific version of the object.
     */
    @Nullable
    public String versionId;

    public void writeHeaders(MutableHttpHeaders headers) {

    }

    public void writeHeadersMap(Map<String, String> headers) {
    }

    public String toQueryString() {
        return "";
    }

    public

    /*
    HEAD /Key+
    ?partNumber=PartNumber
    &response-cache-control=ResponseCacheControl
    &response-content-disposition=ResponseContentDisposition
    &response-content-encoding=ResponseContentEncoding
    &response-content-language=ResponseContentLanguage
    &response-content-type=ResponseContentType
    &response-expires=ResponseExpires
    &versionId=VersionId
     HTTP/1.1
Host: Bucket.s3.amazonaws.com
If-Match: IfMatch
If-Modified-Since: IfModifiedSince
If-None-Match: IfNoneMatch
If-Unmodified-Since: IfUnmodifiedSince
Range: Range
x-amz-server-side-encryption-customer-algorithm: SSECustomerAlgorithm
x-amz-server-side-encryption-customer-key: SSECustomerKey
x-amz-server-side-encryption-customer-key-MD5: SSECustomerKeyMD5
x-amz-request-payer: RequestPayer
x-amz-expected-bucket-owner: ExpectedBucketOwner
x-amz-checksum-mode: ChecksumMode
     */


}
