package com.example.devicemonitor.service;

import com.example.devicemonitor.model.DeviceEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * SqsEventPublisher serialises {@link DeviceEvent} objects to JSON and sends
 * them to an AWS Simple Queue Service (SQS) queue.
 *
 * <h2>Profile restriction</h2>
 * <p>This bean is only created when the {@code aws} Spring profile is active
 * ({@code @Profile("aws")}). This prevents the bean from being instantiated in
 * local development or CI environments where no real AWS credentials or SQS
 * queue exist. In all other profiles the internal-queue approach
 * ({@link DeviceEventPublisher} + {@link DeviceEventConsumer}) is used instead.</p>
 *
 * <h2>Serialisation</h2>
 * <p>A plain {@link ObjectMapper} (with no custom modules registered) is used
 * because {@link DeviceEvent} is expected to contain only simple types. If the
 * model later gains {@code java.time} fields, {@code objectMapper.findAndRegisterModules()}
 * should be called (as done in {@link RedisCacheService}) to handle
 * {@link java.time.Instant} serialisation correctly.</p>
 *
 * <h2>Error handling</h2>
 * <p>Failures are caught and logged rather than rethrown. This is a deliberate
 * "fire and forget" design: if SQS is temporarily unavailable, the calling
 * thread should not be blocked or cause a request to fail. Durability is
 * handled separately by the Transactional Outbox pattern — the event is already
 * stored in the database as a PENDING {@code OutboxEvent} and can be retried.</p>
 *
 * <h2>Configuration</h2>
 * <p>The queue URL is injected from application properties via
 * {@code @Value("${aws.sqs.queue-url}")}. This value must be present in
 * {@code application-aws.properties} (or equivalent) when running with the
 * "aws" profile.</p>
 */
@Service
@Profile("aws")
public class SqsEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(SqsEventPublisher.class);

    /** AWS SDK v2 SQS client — expected to be configured with credentials via the
     *  default credential provider chain (environment variables, IAM role, etc.). */
    private final SqsClient sqsClient;

    /** Jackson mapper for converting {@link DeviceEvent} to a JSON string suitable
     *  for the SQS message body. */
    private final ObjectMapper objectMapper;

    /**
     * The full HTTPS URL of the target SQS queue, e.g.:
     * {@code https://sqs.us-east-2.amazonaws.com/123456789012/device-events}.
     * Injected at startup from the {@code aws.sqs.queue-url} property.
     */
    @Value("${aws.sqs.queue-url}")
    private String queueUrl;

    /**
     * @param sqsClient pre-configured AWS SQS client (injected by Spring, expected
     *                  to be declared as a {@code @Bean} in an AWS configuration class).
     */
    public SqsEventPublisher(SqsClient sqsClient) {
        this.sqsClient = sqsClient;
        // ObjectMapper is created locally rather than injected to keep this bean
        // self-contained. A shared ObjectMapper bean would also be acceptable.
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Serialises {@code event} to JSON and sends it to the configured SQS queue.
     *
     * <p>The method uses the AWS SDK v2 fluent builder pattern to construct a
     * {@link SendMessageRequest}. The message body is the full JSON representation
     * of the {@link DeviceEvent}, which downstream consumers can deserialise
     * independently.</p>
     *
     * <p>Any exception during serialisation or the SQS API call is caught and
     * logged at ERROR level. The event is <em>not</em> retried here — retry logic
     * should be layered on top via the Transactional Outbox polling mechanism in
     * {@link DeviceEventConsumer}.</p>
     *
     * @param event the device lifecycle event to publish; must not be {@code null}.
     */
    public void publish(DeviceEvent event) {
        try {
            // Serialise the event object to a JSON string for the SQS message body.
            // Jackson throws JsonProcessingException if serialisation fails (e.g.,
            // if the object contains non-serialisable types).
            String messageBody = objectMapper.writeValueAsString(event);

            // Build and send the SQS message using the AWS SDK v2 immutable
            // request builder. No message group ID or deduplication ID is set,
            // so this targets a Standard queue (not a FIFO queue).
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .build());

            logger.info("Published event to SQS {}", event.getEventType());
        } catch (Exception e) {
            // Log and swallow — the caller should not crash because of a transient
            // SQS failure. The Transactional Outbox ensures durability separately.
            logger.error("Failed to publish event to SQS {}", event.getEventType(), e);
        }
    }
}
