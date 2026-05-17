package com.example.devicemonitor.config;

import com.example.devicemonitor.service.AwsSecretsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import software.amazon.awssdk.awscore.AwsClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Map;

/**
 * AWS infrastructure configuration for the Device Monitor application.
 *
 * <p>This class wires together all AWS-backed infrastructure beans that are required
 * when the application runs in a cloud environment: secrets from AWS Secrets Manager,
 * a Redis connection backed by ElastiCache, and an SQS client for message queueing.
 *
 * <p>The {@code @Profile("aws")} annotation ensures that none of these beans are
 * instantiated during local development or unit-test runs, where AWS credentials and
 * network access are not available. Developers run the application with
 * {@code spring.profiles.active=aws} only when targeting a real AWS environment (e.g.
 * ECS tasks, EC2 instances). Without that profile, Spring simply skips this entire
 * configuration class and the application falls back to its default, in-memory
 * infrastructure (H2 database, no Redis, no SQS).
 *
 * <p>Dependencies:
 * <ul>
 *   <li>{@link AwsSecretsService} — fetches key/value pairs from AWS Secrets Manager.
 *   <li>{@code spring.data.redis.host} property — hostname of the Redis/ElastiCache node.
 *   <li>{@code aws.region} property — AWS region string (e.g. {@code "us-east-1"}).
 * </ul>
 */
@Configuration
@Profile("aws")
public class AwsConfig {

    /**
     * Service that communicates with AWS Secrets Manager.
     * Injected via constructor to satisfy Spring's recommended practice of using
     * constructor injection for mandatory dependencies (makes the class easier to
     * unit-test and ensures the dependency is never null).
     */
    private final AwsSecretsService awsSecretsService;

    /**
     * Hostname of the Redis standalone node (or ElastiCache primary endpoint).
     * Bound from the {@code spring.data.redis.host} application property so that
     * the value can differ between environments without recompiling the application.
     */
    @Value("${spring.data.redis.host}")
    private String redisHost;

    /**
     * AWS region identifier (e.g. {@code "us-east-1"}).
     * Bound from the {@code aws.region} application property so the same artifact
     * can be deployed to any region by changing configuration alone.
     */
    @Value("${aws.region}")
    private String awsRegion;

    /**
     * Constructs an {@code AwsConfig} with the required secrets service.
     *
     * @param secretsService the service used to retrieve secrets from AWS Secrets Manager;
     *                       must not be {@code null}
     */
    public AwsConfig(AwsSecretsService secretsService) {
        this.awsSecretsService = secretsService;
    }

    /**
     * Fetches the production secret bundle from AWS Secrets Manager and exposes it
     * as a Spring bean so other components can obtain individual secret values by key.
     *
     * <p>The secret path {@code "devicemonitor/prod"} follows the conventional
     * {@code <application>/<environment>} naming scheme used in Secrets Manager.
     * Centralising the fetch here means every consumer receives the same
     * {@code Map} instance for the lifetime of the application context; there is
     * no need for each service to call Secrets Manager independently, which would
     * multiply API calls and costs.
     *
     * <p>The returned map is typically consumed by other configuration beans (e.g.
     * database password, third-party API keys) by injecting the bean and calling
     * {@code awsSecrets.get("some-key")}.
     *
     * @return an immutable map of secret key → secret value pairs for the
     *         {@code devicemonitor/prod} secret
     */
    @Bean
    public Map<String,String> awsSecrets(){
        // Retrieve all key/value pairs stored under the "devicemonitor/prod"
        // secret path. The AwsSecretsService handles the AWS SDK call and JSON
        // deserialisation, so this bean method stays declarative.
        return awsSecretsService.getSecrets("devicemonitor/prod");
    }

    /**
     * Creates the Lettuce-based Redis connection factory used by {@link #redisTemplate}.
     *
     * <p>Lettuce is the default, non-blocking Redis client bundled with Spring Data Redis.
     * It is preferred over Jedis in this project because it supports reactive programming
     * and shares a single thread-safe connection across the application, reducing overhead.
     *
     * <p>{@link RedisStandaloneConfiguration} targets a single Redis primary node (as
     * opposed to a cluster or sentinel configuration). For ElastiCache without cluster
     * mode enabled, standalone mode is the correct choice. Port {@code 6379} is the
     * Redis default and is hardcoded here because it never varies across environments;
     * only the hostname changes, which is externalised via {@link #redisHost}.
     *
     * @return a configured {@link LettuceConnectionFactory} ready to be injected into
     *         {@link RedisTemplate} and other Redis-aware components
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(){
        // Build a standalone Redis configuration pointing at the ElastiCache endpoint.
        // Port 6379 is the universal Redis default; only the host varies per environment.
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(redisHost, 6379);
        return new LettuceConnectionFactory(config);
    }

    /**
     * Creates the primary {@link RedisTemplate} used throughout the application for
     * reading and writing cached data to Redis.
     *
     * <p>{@code RedisTemplate<String, Object>} is the conventional Spring abstraction
     * over raw Redis commands. Typing the key as {@code String} is standard practice;
     * typing the value as {@code Object} keeps the template flexible enough to store
     * arbitrary serialised payloads (e.g. JSON-serialised {@code Device} records).
     *
     * <p>The template is wired to the {@link LettuceConnectionFactory} produced by
     * {@link #redisConnectionFactory()}, which Spring injects automatically via the
     * method parameter. This explicit parameter dependency (rather than relying on
     * autowiring by type alone) makes the bean dependency graph visible in the source
     * code and avoids ambiguous-bean issues if additional factories are added later.
     *
     * <p>No custom serialisers are configured here. Spring Data Redis will default to
     * JDK serialisation. If human-readable keys or JSON values are required in a future
     * iteration, {@code StringRedisSerializer} and {@code Jackson2JsonRedisSerializer}
     * can be set on the template before returning.
     *
     * @param redisConnectionFactory the Lettuce factory to back this template
     * @return a fully initialised {@link RedisTemplate} bound to the Redis connection
     */
    @Bean
    public RedisTemplate<String,Object> redisTemplate(
            LettuceConnectionFactory redisConnectionFactory) {
        RedisTemplate<String,Object> redisTemplate = new RedisTemplate<>();

        // Bind the template to the Lettuce factory so every Redis operation goes
        // through the configured ElastiCache endpoint.
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        return redisTemplate;
    }

    /**
     * Creates the AWS SQS client used to send and receive messages from SQS queues.
     *
     * <p>The AWS SDK v2 builder pattern is used here. Credentials are NOT explicitly
     * configured: the SDK's default credential provider chain automatically discovers
     * credentials from the environment (IAM role attached to the ECS task, environment
     * variables, or the instance metadata service). This is the recommended approach for
     * applications running inside AWS because it avoids embedding long-lived access keys
     * in configuration files.
     *
     * <p>The region is resolved from the {@link #awsRegion} property rather than being
     * hardcoded, so the same deployment artifact works in any AWS region by changing
     * only the application configuration.
     *
     * @return a thread-safe {@link SqsClient} scoped to the configured AWS region
     */
    @Bean
    public SqsClient sqsClient(){
        // Use the AWS SDK v2 builder. No explicit credentials are provided here;
        // the SDK's default provider chain handles credential resolution at runtime
        // (IAM task role on ECS, environment variables, ~/.aws/credentials locally).
        return SqsClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
