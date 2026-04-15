package com.example.devicemonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.util.Map;

@Service
public class AwsSecretsService {

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    public AwsSecretsService() {
        this.secretsManagerClient = SecretsManagerClient.builder()
                .region(Region.US_EAST_2)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getSecrets(String secretName){
        try {
            String secretValue = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(secretName)
                            .build()
            ).secretString();

            return objectMapper.readValue(secretValue, Map.class);

        } catch (Exception e){
            throw new RuntimeException("Failed to retrieve secret: " + secretName + e);
        }
    }
}
