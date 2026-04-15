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

@Service
@Profile("aws")
public class SqsEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(SqsEventPublisher.class);

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    @Value("${aws.sqs.queue-url}")
    private String queueUrl;

    public SqsEventPublisher(SqsClient sqsClient) {
        this.sqsClient = sqsClient;
        this.objectMapper = new ObjectMapper();
    }

    public void publish(DeviceEvent event){
        try {
            String messageBody = objectMapper.writeValueAsString(event);

            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .build());

            logger.info("Published event to SQS {}", event.getEventType());
        } catch (Exception e) {
            logger.error("Failed to publish event to SQS {}", event.getEventType(), e);
        }
    }
}
