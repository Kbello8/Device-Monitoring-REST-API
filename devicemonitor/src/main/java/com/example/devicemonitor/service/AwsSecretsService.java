package com.example.devicemonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.util.Map;

/**
 * AwsSecretsService provides a thin wrapper around the AWS Secrets Manager API,
 * enabling the application to retrieve secret values (such as database credentials
 * or API keys) at runtime without hardcoding them in configuration files.
 *
 * <h2>Why AWS Secrets Manager?</h2>
 * <p>Embedding credentials in {@code application.properties} or environment
 * variables risks leaking them through logs, version control, or process listings.
 * Secrets Manager stores sensitive values encrypted at rest and in transit, controls
 * access via IAM policies, and supports automatic rotation — making it the
 * recommended approach for credential management in AWS-deployed services.</p>
 *
 * <h2>Region hardcoding</h2>
 * <p>The client is currently hardcoded to {@link Region#US_EAST_2}. This is a
 * known limitation: if the application is deployed to a different region, the
 * region should be externalised to a configuration property (e.g.,
 * {@code @Value("${aws.region:us-east-2")}) or read from the EC2/ECS instance
 * metadata rather than being fixed at compile time.</p>
 *
 * <h2>Authentication</h2>
 * <p>The {@link SecretsManagerClient} is built with no explicit credentials
 * provider, which causes the AWS SDK to use the <em>default credential provider
 * chain</em>. In ECS, this resolves to the task's IAM role (injected automatically
 * by the ECS agent). In local development, it falls back to environment variables
 * ({@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY}) or the
 * {@code ~/.aws/credentials} file. No credential values are embedded in code.</p>
 *
 * <h2>Secret format assumption</h2>
 * <p>The service assumes all secrets are stored as JSON objects whose values are
 * strings (e.g., {@code {"username":"admin","password":"s3cr3t"}}). Secrets stored
 * as plain strings or with non-string values will cause a deserialisation error.</p>
 *
 * <h2>Profile note</h2>
 * <p>Unlike {@link SqsEventPublisher} and {@link RedisCacheService}, this bean
 * is not restricted to the "aws" profile. It will be instantiated in all profiles,
 * which means local development environments must either have valid AWS credentials
 * or avoid calling {@link #getSecrets} (e.g., by not wiring it into non-AWS code
 * paths). Adding {@code @Profile("aws")} would be a straightforward improvement.</p>
 */
@Service
public class AwsSecretsService {

    /**
     * AWS SDK v2 Secrets Manager client. Built once and reused for all requests.
     * The SDK manages connection pooling and request signing internally.
     */
    private final SecretsManagerClient secretsManagerClient;

    /**
     * Jackson mapper used to deserialise the secret's JSON string into a
     * {@code Map<String, String>}. A plain {@code ObjectMapper} is sufficient
     * here because secret values are expected to be simple JSON key-value pairs
     * with no {@code java.time} types.
     */
    private final ObjectMapper objectMapper;

    /**
     * Constructs the service and builds the Secrets Manager client.
     *
     * <p>The client is constructed in the constructor (rather than injected as a
     * Spring bean) to keep this service self-contained. In a larger application,
     * sharing a single {@code SecretsManagerClient} bean across multiple services
     * would be preferable to avoid creating multiple HTTP connection pools.</p>
     */
    public AwsSecretsService() {
        // Build the client without explicit credentials — relies on the default
        // credential provider chain (IAM role in ECS, env vars or ~/.aws locally).
        this.secretsManagerClient = SecretsManagerClient.builder()
                .region(Region.US_EAST_2)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Retrieves a secret from AWS Secrets Manager and deserialises its value
     * from JSON into a {@code Map<String, String>}.
     *
     * <h2>Usage</h2>
     * <p>Call this method with the name (or ARN) of a secret stored in Secrets
     * Manager. The secret's value must be a JSON object, e.g.:</p>
     * <pre>{@code
     * Map<String, String> dbCreds = awsSecretsService.getSecrets("prod/devicemonitor/db");
     * String username = dbCreds.get("username");
     * String password = dbCreds.get("password");
     * }</pre>
     *
     * <h2>Error handling</h2>
     * <p>Any exception from the Secrets Manager API call (network failure, IAM
     * access denied, secret not found) or from JSON deserialisation is caught and
     * rethrown as an unchecked {@link RuntimeException}. This is intentional:
     * if the application cannot retrieve its secrets at startup, it should fail
     * fast rather than start in a misconfigured state.</p>
     *
     * @param secretName the name or ARN of the secret to retrieve (e.g.,
     *                   {@code "prod/devicemonitor/db"} or the full ARN string).
     * @return a {@code Map<String, String>} containing the key-value pairs from
     *         the secret's JSON value.
     * @throws RuntimeException wrapping any AWS SDK or Jackson exception if the
     *                          secret cannot be retrieved or parsed.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getSecrets(String secretName) {
        try {
            // Issue the GetSecretValue API call. The SDK handles request signing,
            // retries, and error mapping automatically.
            String secretValue = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(secretName)
                            .build()
            // secretString() returns the plaintext secret value. Binary secrets
            // (secretBinary()) are not handled here.
            ).secretString();

            // Deserialise the JSON string into a raw Map. The @SuppressWarnings
            // suppresses the unchecked cast warning from the untyped readValue call.
            return objectMapper.readValue(secretValue, Map.class);

        } catch (Exception e) {
            // Fail fast — callers cannot function without the secret data, so
            // converting to a RuntimeException propagates the failure clearly.
            throw new RuntimeException("Failed to retrieve secret: " + secretName + e);
        }
    }
}
