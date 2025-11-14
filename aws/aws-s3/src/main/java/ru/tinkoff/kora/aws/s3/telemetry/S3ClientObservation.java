package ru.tinkoff.kora.aws.s3.telemetry;

import ru.tinkoff.kora.common.telemetry.Observation;

public interface S3ClientObservation extends Observation {
    void observeKey(String key);

    void observeUploadId(String uploadId);

    void observeAwsRequestId(String amxRequestId);

    void observeAwsExtendedId(String amxRequestId);
}
