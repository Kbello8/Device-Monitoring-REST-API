package com.example.devicemonitor;

import com.example.devicemonitor.exception.DeviceNotFoundException;
import com.example.devicemonitor.model.Device;
import com.example.devicemonitor.model.DeviceStatus;
import com.example.devicemonitor.repository.DeviceRepository;
import com.example.devicemonitor.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeviceService — the business logic layer.
 *
 * CONCEPT — Unit Testing:
 * Unit tests verify that a single unit of code (here, one class) behaves correctly
 * in isolation. "Isolation" means dependencies (like DeviceRepository) are replaced
 * with controlled fakes so tests don't hit a real database. This makes tests:
 *   - Fast: no DB startup, no network, no disk
 *   - Deterministic: no shared state between tests
 *   - Focused: failures point directly to logic bugs, not infrastructure issues
 *
 * CONCEPT — @ExtendWith(MockitoExtension.class):
 * Integrates the Mockito framework with JUnit 5. This enables Mockito annotations
 * (@Mock, @InjectMocks) to be processed automatically before each test runs.
 * Without this, you'd have to call MockitoAnnotations.openMocks(this) manually.
 *
 * CONCEPT — Mockito:
 * Mockito is a mocking framework. It creates fake ("mock") implementations of
 * interfaces or classes that record calls and return values you configure.
 * This lets you test DeviceService without a real database.
 */
@ExtendWith(MockitoExtension.class)
class DevicemonitorApplicationTests {

	/**
	 * A Mockito-generated fake implementation of DeviceRepository.
	 * By default, all methods return empty/null unless configured with when().
	 *
	 * CONCEPT — @Mock:
	 * Creates a mock object. The mock records every method call made to it,
	 * so you can later verify interactions with verify().
	 */
	@Mock
	private DeviceRepository repository;

	/**
	 * The real DeviceService instance being tested.
	 *
	 * CONCEPT — @InjectMocks:
	 * Creates a real DeviceService and automatically injects the @Mock fields
	 * into it via constructor injection. This is how the mock repository ends up
	 * inside the service without us manually constructing it.
	 */
	@InjectMocks
	private DeviceService service;

	// A shared test Device reused across multiple tests
	private Device testDevice;

	/**
	 * Runs before EACH test method. Resets testDevice to a clean state.
	 *
	 * CONCEPT — @BeforeEach:
	 * JUnit 5 calls this method before every @Test method in the class.
	 * This prevents test pollution — one test modifying testDevice won't
	 * affect the starting state of the next test.
	 */
	@BeforeEach
	void setUp() {
		testDevice = new Device("Workstation-01", "192.168.1.10");
		testDevice.setId(1L);
		testDevice.setStatus(DeviceStatus.UNKNOWN);
	}

	// -------------------------------------------------------------------------
	// registerDevice tests
	// -------------------------------------------------------------------------

	/**
	 * Happy path: valid device with a new IP should be saved and returned.
	 *
	 * CONCEPT — when().thenReturn() (Stubbing):
	 * Configures the mock to return a specific value when a specific method is called.
	 * when(repository.existsByIpAddress("192.168.1.10")).thenReturn(false)
	 * means: "if existsByIpAddress is called with this argument, return false."
	 * The real repository method is never called — the mock intercepts it.
	 *
	 * CONCEPT — assertThat() (AssertJ):
	 * AssertJ provides a fluent assertion API. assertThat(result.getName()).isEqualTo("...")
	 * reads like English and produces clear failure messages if it fails.
	 *
	 * CONCEPT — verify():
	 * Checks that a mock method was called a specific number of times.
	 * verify(repository, times(1)).save(testDevice) asserts that save() was called
	 * exactly once with testDevice as the argument. If the service forgot to call
	 * save(), or called it twice, the test fails.
	 */
	@Test
	void registerDevice_savesAndReturnsDevice() {
		when(repository.existsByIpAddress("192.168.1.10")).thenReturn(false);  // IP is not taken
		when(repository.save(any(Device.class))).thenReturn(testDevice);        // mock save returning the device

		Device result = service.registerDevice(testDevice);

		assertThat(result.getName()).isEqualTo("Workstation-01");
		verify(repository, times(1)).save(testDevice);  // assert save was called exactly once
	}

	/**
	 * Sad path: duplicate IP should throw IllegalArgumentException, not save.
	 *
	 * CONCEPT — assertThatThrownBy():
	 * Asserts that the lambda throws a specific exception type.
	 * .isInstanceOf() checks the class. .hasMessageContaining() checks that the
	 * exception message includes the expected text.
	 * This is safer than @Test(expected = ...) because it also lets you inspect
	 * the exception message, not just its type.
	 */
	@Test
	void registerDevice_throwsWhenDuplicateIp() {
		when(repository.existsByIpAddress("192.168.1.10")).thenReturn(true);  // IP already exists

		assertThatThrownBy(() -> service.registerDevice(testDevice))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("192.168.1.10");  // error message should name the duplicate IP
	}

	// -------------------------------------------------------------------------
	// getDeviceById tests
	// -------------------------------------------------------------------------

	/**
	 * Happy path: repository finds the device → service returns it.
	 *
	 * CONCEPT — Optional.of() in test stubs:
	 * findById() returns Optional<Device>. The stub returns Optional.of(testDevice)
	 * to simulate a found result, or Optional.empty() to simulate not found.
	 */
	@Test
	void getDeviceById_returnsDeviceWhenFound() {
		when(repository.findById(1L)).thenReturn(Optional.of(testDevice));  // found

		Device result = service.getDeviceById(1L);

		assertThat(result.getId()).isEqualTo(1L);
	}

	/**
	 * Sad path: repository returns empty → service should throw DeviceNotFoundException.
	 */
	@Test
	void getDeviceById_throwsWhenNotFound() {
		when(repository.findById(99L)).thenReturn(Optional.empty());  // not found

		assertThatThrownBy(() -> service.getDeviceById(99L))
				.isInstanceOf(DeviceNotFoundException.class)
				.hasMessageContaining("99");  // message should include the ID that wasn't found
	}

	// -------------------------------------------------------------------------
	// getAllDevices tests
	// -------------------------------------------------------------------------

	/**
	 * When no status filter is provided, all devices should be returned sorted by name.
	 * This test also verifies the sort order — "Aardvark" should come before "Workstation".
	 */
	@Test
	void getAllDevices_returnsAllWhenNoFilter() {
		Device second = new Device("Aardvark-01", "192.168.1.11");
		when(repository.findAll()).thenReturn(List.of(testDevice, second));  // unsorted

		List<Device> results = service.getAllDevices(Optional.empty());  // no filter

		// Should be sorted by name — Aardvark before Workstation
		assertThat(results).hasSize(2);
		assertThat(results.get(0).getName()).isEqualTo("Aardvark-01");
	}

	/**
	 * When a status filter is provided, only matching devices should be returned,
	 * and findAll() should NOT be called (correct code path).
	 *
	 * CONCEPT — verify(repository, never()).findAll():
	 * Asserts that findAll() was never called. This confirms the service took the
	 * correct branch (findByStatus) instead of fetching everything and filtering in memory.
	 * Without this, the test would pass even if the service called findAll() unnecessarily.
	 */
	@Test
	void getAllDevices_filtersWhenStatusProvided() {
		when(repository.findByStatus(DeviceStatus.ONLINE))
				.thenReturn(List.of(testDevice));

		List<Device> results = service.getAllDevices(Optional.of(DeviceStatus.ONLINE));

		assertThat(results).hasSize(1);
		verify(repository, never()).findAll();  // confirm findAll was NOT called
	}

	// -------------------------------------------------------------------------
	// updateDevice tests
	// -------------------------------------------------------------------------

	/**
	 * Only the provided fields (status) should be updated.
	 * Fields not in the update object (name) should remain unchanged.
	 *
	 * CONCEPT — any(Device.class):
	 * A Mockito argument matcher. save() accepts a Device, but we don't care which
	 * exact instance it gets — we just want it to return testDevice.
	 * any(Device.class) matches any Device argument.
	 */
	@Test
	void updateDevice_updatesOnlyProvidedFields() {
		when(repository.findById(1L)).thenReturn(Optional.of(testDevice));
		when(repository.save(any(Device.class))).thenReturn(testDevice);

		// Build an update object with only status set (name is null)
		Device updates = new Device();
		updates.setStatus(DeviceStatus.ONLINE);  // only status is provided

		Device result = service.updateDevice(1L, updates);

		assertThat(result.getStatus()).isEqualTo(DeviceStatus.ONLINE);  // status should be updated
		assertThat(result.getName()).isEqualTo("Workstation-01");        // name should be unchanged
	}

	// -------------------------------------------------------------------------
	// deleteDevice tests
	// -------------------------------------------------------------------------

	/**
	 * A found device should result in repository.delete() being called once.
	 */
	@Test
	void deleteDevice_callsRepositoryDelete() {
		when(repository.findById(1L)).thenReturn(Optional.of(testDevice));

		service.deleteDevice(1L);

		verify(repository, times(1)).delete(testDevice);  // assert delete was called
	}

	/**
	 * Attempting to delete a non-existent device should throw, not silently succeed.
	 */
	@Test
	void deleteDevice_throwsWhenDeviceNotFound() {
		when(repository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.deleteDevice(99L))
				.isInstanceOf(DeviceNotFoundException.class);
	}
}