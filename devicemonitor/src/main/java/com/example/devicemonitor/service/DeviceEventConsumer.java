package com.example.devicemonitor.service;

import com.example.devicemonitor.model.DeviceEvent;
import com.example.devicemonitor.model.OutboxEvent;
import com.example.devicemonitor.repository.OutboxEventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class DeviceEventConsumer {

    private static final Logger logger =  LoggerFactory.getLogger(DeviceEventConsumer.class);

    private final DeviceEventPublisher publisher;
    private final OutboxEventRepository outboxEventRepository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean running = true;

    public DeviceEventConsumer(DeviceEventPublisher publisher, OutboxEventRepository outboxEventRepository) {
        this.publisher = publisher;
        this.outboxEventRepository = outboxEventRepository;
    }

    @PostConstruct
    public void init(){
        executor.submit(this::consumeLoop);
        logger.info("Device Event Consumer started");
    }

    @PreDestroy
    public void stop(){
        running = false;
        executor.shutdown();
        try{
            if(!executor.awaitTermination(5,TimeUnit.SECONDS)){
                executor.shutdownNow();
            }
        } catch(InterruptedException e){
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("Device Event Consumer stopped");
    }

    private void consumeLoop(){
        while(running){
            try {
                DeviceEvent event = publisher.poll(500);
                if(event != null){
                    processEvent(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e){
                logger.error("Error processing event", e);
            }
        }
    }

    private void processEvent(DeviceEvent event){
        logger.info("Processing event: {}",event);

        OutboxEvent outboxEvent = outboxEventRepository
                .findByStatus(OutboxEvent.Status.PENDING)
                .stream()
                .filter(e -> e.getDeviceId().equals(event.getDeviceId())
                    && e.getEventType().equals(event.getEventType()))
                .findFirst()
                .orElse(null);

        if(outboxEvent != null){
            try{
                //simulate downstream processing
                // SQS publish later
                logger.info("Dispatching to downstream: {}", event);
            } catch (Exception e) {
                outboxEvent.markFailed();
                logger.error("Error processing event {}", event.getDeviceId(), e);
            }
            outboxEventRepository.save(outboxEvent);
        }
    }
}
