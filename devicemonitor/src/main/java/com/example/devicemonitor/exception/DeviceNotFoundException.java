package com.example.devicemonitor.exception;

public class DeviceNotFoundException extends RuntimeException{

    public DeviceNotFoundException(Long id) {
        super("Device not found with id: " + id);
    }
}
