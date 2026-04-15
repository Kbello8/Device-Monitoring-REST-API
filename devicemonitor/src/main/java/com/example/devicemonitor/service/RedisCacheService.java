package com.example.devicemonitor.service;

import com.example.devicemonitor.model.Device;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@Profile("aws")
public class RedisCacheService implements CacheService {

    private static final Logger logger = LoggerFactory.getLogger(RedisCacheService.class);

    private static final String KEY_PREFIX = "device:";
    private static final Duration TTL = Duration.ofSeconds(30);
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules(); // handles Instant serialization
    }

    public Optional<Device> get(Long id){
        try {
            Object value = redisTemplate.opsForValue().get(KEY_PREFIX + id);
            if(value == null) return Optional.empty();
            Device device = objectMapper.convertValue(value, Device.class);
            return Optional.of(device);
        } catch (Exception e) {
            logger.warn("Redis cache read failed for device {}",id,e);
            return Optional.empty();
        }
    }

    public void put(Long id, Device device){
        try{
            redisTemplate.opsForValue()
                    .set(KEY_PREFIX + id, device, TTL);
        } catch(Exception e){
            logger.warn("Redis cache write failed for device {}",id,e);
        }
    }

    public void invalidate(Long id){
        try{
            redisTemplate.delete(KEY_PREFIX + id);
        } catch(Exception e){
            logger.warn("Redis cache invalidation failed for device {}",id,e);
        }
    }

    public void invalidateAll(){
        try{
            var keys = redisTemplate.keys(KEY_PREFIX + "*");
            if(keys!=null && !keys.isEmpty()){
                redisTemplate.delete(keys);
            }
        } catch(Exception e){
            logger.warn("Redis cache clear failed");
        }
    }

    public int size(){
        try{
            var keys = redisTemplate.keys(KEY_PREFIX + "*");
            return keys != null ? keys.size() : 0;
        } catch(Exception e){
            logger.warn("Redis cache size check failed");
            return 0;
        }
    }

}
