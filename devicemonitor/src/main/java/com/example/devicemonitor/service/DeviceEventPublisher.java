package com.example.devicemonitor.service;

import com.example.devicemonitor.model.DeviceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Service
public class DeviceEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(DeviceEventPublisher.class);

    // Thread-safe bounded queue - max 1000 events
    private final LinkedBlockingQueue<DeviceEvent> queue =
            new LinkedBlockingQueue<>();

    public boolean publish(DeviceEvent event) {
        boolean accepted = queue.offer(event);
        if (!accepted) {
            logger.warn("Event queue full - dropped event for device{}",
                    event.getDeviceId());
        }
        return accepted;
    }

    public DeviceEvent poll(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public int size(){
        return queue.size();
    }
}
