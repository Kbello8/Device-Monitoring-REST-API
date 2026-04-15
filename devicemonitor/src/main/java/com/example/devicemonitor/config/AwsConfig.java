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

@Configuration
@Profile("aws")
public class AwsConfig {
    private final AwsSecretsService awsSecretsService;

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${aws.region}")
    private String awsRegion;

    public AwsConfig(AwsSecretsService secretsService) {
        this.awsSecretsService = secretsService;
    }

    @Bean
    public Map<String,String> awsSecrets(){
        return awsSecretsService.getSecrets("devicemonitor/prod");
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(){
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(redisHost, 6379);
        return new LettuceConnectionFactory(config);
    }

    @Bean
    public RedisTemplate<String,Object> redisTemplate(
            LettuceConnectionFactory redisConnectionFactory) {
        RedisTemplate<String,Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        return redisTemplate;
    }

    @Bean
    public SqsClient sqsClient(){
        return SqsClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
