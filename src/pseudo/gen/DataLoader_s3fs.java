package pseudo.gen;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class DataLoader_s3fs {
    public static final String BUCKET_NAME = System.getenv("PSEUDO_PFLOW_BUCKET") != null ? 
        System.getenv("PSEUDO_PFLOW_BUCKET") : "pseudo-pflow"; //  S3 Bucket name

    private static final S3Client s3 = createS3Client();

    /**
     * Create S3Client with optional MinIO/localstack support.
     *
     * When AWS_ENDPOINT_URL is set (e.g. http://localhost:9000 for MinIO),
     * configures endpointOverride and forcePathStyle so the S3 client
     * connects to the local object store instead of real AWS S3.
     */
    private static S3Client createS3Client() {
        S3ClientBuilder builder = S3Client.builder();
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        if (endpointUrl != null && !endpointUrl.isEmpty()) {
            try {
                builder.endpointOverride(new URI(endpointUrl));
                builder.forcePathStyle(true);
                System.err.println("[DataLoader_s3fs] Using custom endpoint: " + endpointUrl + " (path-style)");
            } catch (Exception e) {
                System.err.println("[DataLoader_s3fs] Failed to parse AWS_ENDPOINT_URL: " + endpointUrl);
                e.printStackTrace();
            }
        }
        return builder.build();
    }


    public static List<String> readS3File(String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(s3.getObject(getObjectRequest)))) {
            return reader.lines().collect(Collectors.toList());
        } catch (Exception e) {
            System.err.println("Error reading S3 file: " + key);
            e.printStackTrace();
            return Collections.emptyList(); 
        }
    }

    public static boolean fileExists(String s3Path) {
        try {
            String key = s3Path.replace("s3://" + BUCKET_NAME + "/", ""); // 去掉 S3 前缀
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build();
            s3.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            System.err.println("Error checking file existence: " + s3Path);
            e.printStackTrace();
            return false;
        }
    }

    // save data to S3
    public static void saveToS3(String key, String content) {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(BUCKET_NAME)
                    .key(key)
                    .build();

            s3.putObject(putObjectRequest, RequestBody.fromString(content));
            System.out.println("Successfully uploaded to S3: " + key);
        } catch (Exception e) {
            System.err.println("Error saving to S3: " + key);
            e.printStackTrace();
        }
    }

    // Close S3Client properly to prevent lingering threads
    public static void closeS3Client() {
        if (s3 != null) {
            try {
                System.out.println("Shutting down S3Client...");
                s3.close();
            } catch (Exception e) {
                System.err.println("Error closing S3Client");
                e.printStackTrace();
            }
        }
    }

}