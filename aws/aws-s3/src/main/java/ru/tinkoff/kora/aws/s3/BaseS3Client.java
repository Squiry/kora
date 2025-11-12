package ru.tinkoff.kora.aws.s3;

import jakarta.annotation.Nullable;
import ru.tinkoff.kora.aws.s3.exception.S3ClientException;
import ru.tinkoff.kora.aws.s3.model.HeadObjectResult;
import ru.tinkoff.kora.aws.s3.model.ListMultipartUploadsResult;
import ru.tinkoff.kora.http.client.common.response.HttpClientResponse;

import java.util.List;

public interface BaseS3Client {
    /**
     * The HEAD operation retrieves metadata from an object without returning the object itself. This operation is useful if you're interested only in an object's metadata.
     *
     * @param credentials
     * @param bucket      The bucket name containing the object.
     * @param key         Key of the object to get.
     * @return object metadata or null if required is false and object is not found
     * @throws ru.tinkoff.kora.aws.s3.exception.S3ClientResponseException on 404 if required is true
     * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html">HeadObject</a>
     */
    @Nullable
    HeadObjectResult headObject(AwsCredentials credentials, String bucket, String key, boolean required) throws S3ClientException;


    @Nullable
    HttpClientResponse getObject(AwsCredentials credentials, String bucket, String key, boolean required);

    /**
     * Removes an object from a bucket.
     *
     * @param credentials
     * @param bucket      The bucket name of the bucket containing the object.
     * @param key         Key name of the object to delete.
     * @param versionId   Version ID used to reference a specific version of the object.
     * @throws S3ClientException
     * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html">DeleteObject</a>
     */
    void deleteObject(AwsCredentials credentials, String bucket, String key, @Nullable String versionId) throws S3ClientException;

    /**
     * This operation enables you to delete multiple objects from a bucket using a single HTTP request.<br>
     * If you know the object keys that you want to delete, then this operation provides a suitable alternative to sending individual delete requests, reducing per-request overhead.
     * The request can contain a list of up to 1,000 keys that you want to delete.
     * In the XML, you provide the object key names, and optionally, version IDs if you want to delete a specific version of the object from a versioning-enabled bucket.
     * For each key, Amazon S3 performs a delete operation and returns the result of that delete, success or failure, in the response.
     * If the object specified in the request isn't found, Amazon S3 confirms the deletion by returning the result as deleted.
     *
     * @param credentials
     * @param bucket      The bucket name of the bucket containing the object.
     * @param keys        The objects to delete.
     * @throws S3ClientException
     * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObjects.html">DeleteObjects</a>
     */
    void deleteObjects(AwsCredentials credentials, String bucket, List<String> keys) throws S3ClientException;

    /**
     * The HEAD operation retrieves metadata from an object without returning the object itself. This operation is useful if you're interested only in an object's metadata.
     *
     * @param credentials
     * @param bucket          The name of the bucket where the multipart upload is initiated and where the object is uploaded.
     * @param key             Object key for which the multipart upload is to be initiated.
     * @param contentEncoding Specifies what content encodings have been applied to the object and thus what decoding mechanisms must be applied to obtain the media-type referenced by the Content-Type header field.
     * @param contentType     A standard MIME type describing the format of the object data.
     * @return ID for the initiated multipart upload.
     * @throws ru.tinkoff.kora.aws.s3.exception.S3ClientResponseException
     * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateMultipartUpload.html">createMultipartUpload</a>
     */
    String createMultipartUpload(AwsCredentials credentials, String bucket, String key, @Nullable String contentType, @Nullable String contentEncoding) throws S3ClientException;

    /**
     * @param bucket   The bucket name to which the upload was taking place.
     * @param key      Key of the object for which the multipart upload was initiated.
     * @param uploadId Upload ID that identifies the multipart upload.
     * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_AbortMultipartUpload.html">AbortMultipartUpload</a>
     */
    void abortMultipartUpload(AwsCredentials credentials, String bucket, String key, String uploadId) throws S3ClientException;

    /**
     * This operation lists in-progress multipart uploads in a bucket.
     * An in-progress multipart upload is a multipart upload that has been initiated by the CreateMultipartUpload request, but has not yet been completed or aborted.<br>
     * The ListMultipartUploads operation returns a maximum of 1,000 multipart uploads in the response. The limit of 1,000 multipart uploads is also the default value.
     * You can further limit the number of uploads in a response by specifying the max-uploads request parameter.
     * If there are more than 1,000 multipart uploads that satisfy your ListMultipartUploads request, the response returns an IsTruncated element with the value of true, a NextKeyMarker element, and a NextUploadIdMarker element.
     * To list the remaining multipart uploads, you need to make subsequent ListMultipartUploads requests. In these requests, include two query parameters: key-marker and upload-id-marker.
     * Set the value of key-marker to the NextKeyMarker value from the previous response. Similarly, set the value of upload-id-marker to the NextUploadIdMarker value from the previous response.
     *
     * @param credentials
     * @param bucket      The name of the bucket to which the multipart upload was initiated.
     * @param keyMarker   Specifies the multipart upload after which listing should begin.
     * @param prefix      Lists in-progress uploads only for those keys that begin with the specified prefix.
     *                    You can use prefixes to separate a bucket into different grouping of keys.
     *                    (You can think of using prefix to make groups in the same way that you'd use a folder in a file system.)
     * @param delimiter   Character you use to group keys.
     * @param maxUploads  Sets the maximum number of multipart uploads, from 1 to 1,000, to return in the response body. 1,000 is the maximum number of uploads that can be returned in a response.
     * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListMultipartUploads.html">ListMultipartUploads</a>
     */
    ListMultipartUploadsResult listMultipartUploads(AwsCredentials credentials, String bucket, @Nullable String keyMarker, @Nullable String prefix, @Nullable String delimiter, @Nullable Integer maxUploads) throws S3ClientException;
}
