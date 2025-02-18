/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.services.s3.crt;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static software.amazon.awssdk.testutils.service.S3BucketUtils.temporaryBucketName;

import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.checksums.Algorithm;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3IntegrationTestBase;
import software.amazon.awssdk.services.s3.internal.crt.S3CrtAsyncClient;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.s3.utils.ChecksumUtils;
import software.amazon.awssdk.testutils.RandomTempFile;
import software.amazon.awssdk.testutils.service.AwsTestBase;

public class CrtChecksumIntegrationTest extends S3IntegrationTestBase {
    private static final String TEST_BUCKET = temporaryBucketName(CrtChecksumIntegrationTest.class);
    private static final String TEST_KEY = "10mib_file.dat";
    private static final int OBJ_SIZE = 10 * 1024 * 1024;

    private static RandomTempFile testFile;

    private static String testFileSha1;
    private static String testFileCrc32;

    private static S3AsyncClient s3Crt;

    @BeforeAll
    public static void setup() throws Exception {
        S3IntegrationTestBase.setUp();
        S3IntegrationTestBase.createBucket(TEST_BUCKET);

        testFile = new RandomTempFile(TEST_KEY, OBJ_SIZE);
        testFileSha1 = ChecksumUtils.calculatedChecksum(testFile.toPath(), Algorithm.SHA1);
        testFileCrc32 = ChecksumUtils.calculatedChecksum(testFile.toPath(), Algorithm.CRC32);

        s3Crt = S3CrtAsyncClient.builder()
                                .credentialsProvider(CREDENTIALS_PROVIDER_CHAIN)
                                .region(DEFAULT_REGION)
                                // make sure we don't do a multipart upload, it will mess with validation against the precomputed
                                // checksums above
                                .thresholdInBytes(2L * OBJ_SIZE)
                                .build();
    }

    @AfterAll
    public static void teardown() throws IOException {
        S3IntegrationTestBase.deleteBucketAndAllContents(TEST_BUCKET);
        Files.delete(testFile.toPath());
        s3Crt.close();

    }

    @Test
    void noChecksumCustomization_crc32ShouldBeUsed() {
        AsyncRequestBody body = AsyncRequestBody.fromFile(testFile.toPath());
        s3Crt.putObject(r -> r.bucket(TEST_BUCKET).key(TEST_KEY), body).join();

        ResponseBytes<GetObjectResponse> getObjectResponseResponseBytes =
            s3Crt.getObject(r -> r.bucket(TEST_BUCKET).key(TEST_KEY), AsyncResponseTransformer.toBytes()).join();
        String getObjectChecksum = getObjectResponseResponseBytes.response().checksumCRC32();
        assertThat(getObjectChecksum).isEqualTo(testFileCrc32);
    }

    @Test
    void putObject_checksumProvidedInRequest_shouldTakePrecendence() {
        AsyncRequestBody body = AsyncRequestBody.fromFile(testFile.toPath());
        s3Crt.putObject(r -> r.bucket(TEST_BUCKET).key(TEST_KEY).checksumAlgorithm(ChecksumAlgorithm.SHA1), body).join();

        ResponseBytes<GetObjectResponse> getObjectResponseResponseBytes =
            s3Crt.getObject(r -> r.bucket(TEST_BUCKET).key(TEST_KEY), AsyncResponseTransformer.toBytes()).join();
        String getObjectChecksum = getObjectResponseResponseBytes.response().checksumSHA1();
        assertThat(getObjectChecksum).isEqualTo(testFileSha1);
    }

    @Test
    void checksumDisabled_shouldNotPerformChecksumValidationByDefault() {

        try (S3AsyncClient s3Crt = S3CrtAsyncClient.builder()
                                                   .credentialsProvider(AwsTestBase.CREDENTIALS_PROVIDER_CHAIN)
                                                   .region(S3IntegrationTestBase.DEFAULT_REGION)
                                                   .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                                                   .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                                                   .build()) {
            AsyncRequestBody body = AsyncRequestBody.fromFile(testFile.toPath());
            PutObjectResponse putObjectResponse =
                s3Crt.putObject(r -> r.bucket(TEST_BUCKET).key(TEST_KEY), body).join();
            assertThat(putObjectResponse.checksumCRC32()).isNull();

            ResponseBytes<GetObjectResponse> getObjectResponseResponseBytes =
                s3Crt.getObject(r -> r.bucket(TEST_BUCKET).key(TEST_KEY), AsyncResponseTransformer.toBytes()).join();
            assertThat(getObjectResponseResponseBytes.response().checksumCRC32()).isNull();
        }
    }

    @Test
    void nonStreamingOperation_specifyChecksum_shouldWork() {
        s3Crt.putObject(p -> p.bucket(TEST_BUCKET).key(TEST_KEY), AsyncRequestBody.fromString("helloworld")).join();

        // checksum is required for this operation
        PutObjectTaggingResponse putBucketAclResponse =
            s3Crt.putObjectTagging(p -> p.bucket(TEST_BUCKET).key(TEST_KEY)
                                         .checksumAlgorithm(ChecksumAlgorithm.SHA1)
                                         .tagging(Tagging.builder().tagSet(Tag.builder().key("test").
                                                                              value("value").build()).build()))
                 .join();
        assertThat(putBucketAclResponse).isNotNull();
    }
}
