package com.example.devicemonitor;

import com.example.devicemonitor.exception.DeviceNotFoundException;
import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import com.example.devicemonitor.repository.DeviceRepository;
import com.example.devicemonitor.service.DeviceCacheService;
import com.example.devicemonitor.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DevicemonitorApplicationTests {

	@Mock
	private DeviceRepository repository;

	@Mock
	private DeviceCacheService cacheService;

	@InjectMocks
	private DeviceService service;

	private Device testDevice;

	@BeforeEach
	void setUp() {
		testDevice = new Device("Workstation-01", "192.168.1.10");
		testDevice.setId(1L);
		testDevice.setStatus(DeviceStatus.UNKNOWN);
	}

	// --- registerDevice ---

	@Test
	void registerDevice_savesAndReturnsDevice() {
		when(repository.existsByIpAddress("192.168.1.10")).thenReturn(false);
		when(repository.save(any(Device.class))).thenReturn(testDevice);

		Device result = service.registerDevice(testDevice);

		assertThat(result.getName()).isEqualTo("Workstation-01");
		verify(repository, times(1)).save(testDevice);
	}

	@Test
	void registerDevice_throwsWhenDuplicateIp() {
		when(repository.existsByIpAddress("192.168.1.10")).thenReturn(true);

		assertThatThrownBy(() -> service.registerDevice(testDevice))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("192.168.1.10");
	}

	// --- getDeviceById ---

	@Test
	void getDeviceById_returnsDeviceWhenFound() {
		when(repository.findById(1L)).thenReturn(Optional.of(testDevice));

		Device result = service.getDeviceById(1L);

		assertThat(result.getId()).isEqualTo(1L);
	}

	@Test
	void getDeviceById_throwsWhenNotFound() {
		when(repository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getDeviceById(99L))
				.isInstanceOf(DeviceNotFoundException.class)
				.hasMessageContaining("99");
	}

	// --- getAllDevices ---

	@Test
	void getAllDevices_returnsAllWhenNoFilter() {
		Device second = new Device("Aardvark-01", "192.168.1.11");
		when(repository.findAll()).thenReturn(List.of(testDevice, second));

		List<Device> results = service.getAllDevices(Optional.empty());

		// Should be sorted by name — Aardvark before Workstation
		assertThat(results).hasSize(2);
		assertThat(results.get(0).getName()).isEqualTo("Aardvark-01");
	}

	@Test
	void getAllDevices_filtersWhenStatusProvided() {
		when(repository.findByStatus(DeviceStatus.ONLINE))
				.thenReturn(List.of(testDevice));

		List<Device> results = service.getAllDevices(Optional.of(DeviceStatus.ONLINE));

		assertThat(results).hasSize(1);
		verify(repository, never()).findAll();
	}

	// --- updateDevice ---

	@Test
	void updateDevice_updatesOnlyProvidedFields() {
		when(repository.findById(1L)).thenReturn(Optional.of(testDevice));
		when(repository.save(any(Device.class))).thenReturn(testDevice);

		Device updates = new Device();
		updates.setStatus(DeviceStatus.ONLINE);

		Device result = service.updateDevice(1L, updates);

		assertThat(result.getStatus()).isEqualTo(DeviceStatus.ONLINE);
		// Name should be unchanged
		assertThat(result.getName()).isEqualTo("Workstation-01");
	}

	// --- deleteDevice ---

	@Test
	void deleteDevice_callsRepositoryDelete() {
		when(repository.findById(1L)).thenReturn(Optional.of(testDevice));

		service.deleteDevice(1L);

		verify(repository, times(1)).delete(testDevice);
	}

	@Test
	void deleteDevice_throwsWhenDeviceNotFound() {
		when(repository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.deleteDevice(99L))
				.isInstanceOf(DeviceNotFoundException.class);
	}

	@Test
	void cacheHandlesConcurrentReadsWithoutCorruption() throws InterruptedException {
		//Arrange
		when(repository.findById(1L)).thenReturn(Optional.of(testDevice));

		int threadCount = 20;
		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
		CountDownLatch countDownLatch = new CountDownLatch(threadCount);
		AtomicInteger successCount = new AtomicInteger(0);

		// Act - 20 threads all request the same device simultaneously
		for (int i = 0; i < threadCount; i++) {
			executorService.submit(() -> {
				try {
					Device result = service.getDeviceById(1L);
					if (result != null) successCount.incrementAndGet();
				} finally {
					countDownLatch.countDown();
				}
			});
		}

		countDownLatch.await(5, TimeUnit.SECONDS);
		executorService.shutdown();

		// Assert - all 20 threads got a result with no corruption
		assertThat(successCount.get()).isEqualTo(threadCount);

	}
}