package com.example.devicemonitor.repository;

import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface DeviceRepository extends JpaRepository<Device, Long>{
    List<Device> findByStatus(DeviceStatus status);

    boolean existsByIpAddress(String ipAddress);
}
