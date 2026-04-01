package com.example.devicemonitor.model;

public class DeviceStatusSummary {

    private String status;
    private long deviceCount;
    private long rank;

    public DeviceStatusSummary(Object[] row){
        this.status = (String) row[0];
        this.deviceCount = ((Number) row[1]).longValue();
        this.rank = ((Number) row[2]).longValue();
    }

    public String getStatus() {return status;}
    public long getDeviceCount() {return deviceCount;}
    public long getRank() {return rank;}
}
