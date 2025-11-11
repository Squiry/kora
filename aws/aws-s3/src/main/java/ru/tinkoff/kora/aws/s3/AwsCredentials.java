package ru.tinkoff.kora.aws.s3;

import java.util.Objects;

public interface AwsCredentials {
    String accessKey();

    String secretKey();

    static AwsCredentials of(String accessKey, String secretKey) {
        return new SimpleAwsCredentials(accessKey, secretKey);
    }

    record SimpleAwsCredentials(String accessKey, String secretKey) implements AwsCredentials {
        public SimpleAwsCredentials {
            Objects.requireNonNull(accessKey, "accessKey");
            Objects.requireNonNull(secretKey, "secretKey");
        }
    }
}
