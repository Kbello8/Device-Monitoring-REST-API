# Device-Monitoring-REST-API
Study project to practice modern Java, Clean code, SQL, System Design, Testability, Agentic Learning. Java8+(streams, lambdas), Collections,Clean OOP + SOLID, Async Health-check, SQL/Postgres, System Design, Unit testing(JUnit)

Device Monitoring REST API — Code Overview
==========================================
A Spring Boot study project for practicing modern Java, REST API design,
ORM, async programming, SQL window functions, and unit testing.


FILE SUMMARIES
==============

1. DevicemonitorApplication.java
---------------------------------
WHAT IT DOES:
  The entry point for the entire application. Contains a single main() method
  that bootstraps the Spring Boot application.

WHY IT WAS WRITTEN:
  Every Spring Boot application needs this class. It is the "on switch" —
  running it starts the embedded Tomcat server, initializes all beans,
  configures the database, and begins serving HTTP requests.

CONCEPTS TAUGHT:
  - @SpringBootApplication (combines @Configuration, @EnableAutoConfiguration, @ComponentScan)
  - Spring Boot auto-configuration: how a single annotation wires up an entire app
  - Embedded server: no separate Tomcat install required
  - SpringApplication.run(): the bootstrap sequence


2. model/DeviceStatus.java
---------------------------
WHAT IT DOES:
  Defines the three possible states a device can be in: ONLINE, OFFLINE, UNKNOWN.

WHY IT WAS WRITTEN:
  Using raw strings to represent states ("online", "ONLINE") is error-prone —
  typos compile silently and cause runtime bugs. An enum makes invalid states
  impossible at compile time and makes the code self-documenting.

CONCEPTS TAUGHT:
  - Enums: fixed sets of named constants
  - Type safety: how enums prevent invalid values at compile time
  - EnumType.STRING in JPA: why storing enum names is safer than storing ordinal integers


3. model/Device.java
---------------------
WHAT IT DOES:
  The central data model. Represents a single network device with fields for
  id, name, ipAddress, status, and lastSeenAt. Maps directly to the "devices"
  database table and is also used as the JSON request/response body.

WHY IT WAS WRITTEN:
  Defines the schema of the data the entire application revolves around.
  Also demonstrates how a single class can serve as both a database entity
  and an API data model (though in larger systems these roles are often separated).

CONCEPTS TAUGHT:
  - JPA / ORM: mapping a Java class to a database table with @Entity, @Table
  - Primary keys and auto-generation: @Id, @GeneratedValue(GenerationType.IDENTITY)
  - Bean Validation: @NotBlank for request body validation
  - @Enumerated(EnumType.STRING): safe enum persistence
  - Instant vs LocalDateTime: why UTC timestamps are preferred in backend systems
  - Encapsulation: private fields with public getters/setters
  - Why JPA requires a no-arg constructor (reflection-based instantiation)


4. model/DeviceStatusSummary.java
----------------------------------
WHAT IT DOES:
  A read-only data object that holds one row of the status summary query result:
  a status label, a count of devices with that status, and a rank.

WHY IT WAS WRITTEN:
  The summary query uses GROUP BY and window functions — it produces aggregated
  data that doesn't fit the Device model. A separate class was needed to
  carry those results from the repository to the API response cleanly.

CONCEPTS TAUGHT:
  - DTO (Data Transfer Object): objects whose only job is to move data between layers
  - Native query result mapping: why JPA returns Object[] for non-entity queries
  - Safe numeric casting: why casting to Number before calling .longValue() avoids
    ClassCastException when the DB returns different numeric types


5. repository/DeviceRepository.java
-------------------------------------
WHAT IT DOES:
  The data access layer. Provides all database operations for Device objects:
  save, find, delete, count, and a custom SQL query for the status summary.

WHY IT WAS WRITTEN:
  Centralizes all database interaction in one place. The rest of the application
  never writes SQL or calls JDBC directly — they call methods on this interface.
  Spring Data generates the implementation automatically.

CONCEPTS TAUGHT:
  - Repository pattern: abstracting DB access behind an interface
  - Spring Data JPA: how extending JpaRepository provides CRUD for free
  - Derived query methods: how Spring generates SQL from method names
    (findByStatus → SELECT * WHERE status=?, existsByIpAddress → SELECT COUNT(*) > 0)
  - @Query with nativeQuery=true: writing plain SQL when derived methods can't express the query
  - SQL window functions: RANK() OVER (ORDER BY COUNT(*) DESC) for ranking groups
  - SQL aggregation: GROUP BY with COUNT(*)


6. service/DataSeeder.java
---------------------------
WHAT IT DOES:
  Populates the database with 7 sample devices on application startup,
  but only if the database is empty.

WHY IT WAS WRITTEN:
  The database is in-memory (H2) and resets on every restart. Without seed data,
  every restart would leave the app with an empty database, making manual testing
  tedious. The seeder provides a consistent starting state for development.

CONCEPTS TAUGHT:
  - CommandLineRunner: Spring Boot's hook for running code after startup
  - @Component: registering a class as a Spring bean
  - Dependency injection via constructor: the preferred injection style
  - Idempotent startup logic: the count() > 0 guard prevents duplicate data
  - List.of(): creating immutable lists
  - saveAll(): batch inserts vs individual save() calls


7. service/DeviceService.java
------------------------------
WHAT IT DOES:
  The business logic layer. Implements all device operations: register, list,
  get by ID, update, delete, status summary, and health checks (both single
  and bulk). Enforces business rules like IP uniqueness and partial updates.

WHY IT WAS WRITTEN:
  Separating business rules into a service layer keeps controllers thin (they
  just translate HTTP) and keeps repositories thin (they just talk to the DB).
  It also makes the business logic independently testable without HTTP or database.

CONCEPTS TAUGHT:
  - Service layer: where business rules live in layered architecture
  - Optional<T>: representing optional values without null
  - Stream API: map(), filter(), sorted(), toList() for functional collection processing
  - Comparator.comparing(): sorting objects by a field
  - Method references: Device::getName as shorthand for d -> d.getName()
  - Optional.ofNullable().filter().ifPresent(): null-safe partial update pattern
  - CompletableFuture: async programming, non-blocking operations
  - supplyAsync() / thenApply() / exceptionally(): chaining async operations
  - ThreadLocalRandom: thread-safe random numbers for concurrent code
  - Fan-out / fan-in pattern: launching N concurrent tasks and waiting for all to finish
  - CompletableFuture.allOf(): barrier that waits for all futures to complete
  - Thread.currentThread().interrupt(): correct handling of InterruptedException


8. controller/DeviceController.java
-------------------------------------
WHAT IT DOES:
  The HTTP layer. Exposes 8 REST endpoints under /api/devices, delegates all
  work to DeviceService, and returns appropriate HTTP responses.

  Endpoints:
    POST   /api/devices              → register a new device
    GET    /api/devices              → list all devices (optional ?status= filter)
    GET    /api/devices/{id}         → get one device
    PUT    /api/devices/{id}         → update a device
    DELETE /api/devices/{id}         → delete a device
    GET    /api/devices/summary      → status count summary with rank
    POST   /api/devices/{id}/health-check  → async ping one device
    POST   /api/devices/health-check       → ping all devices concurrently

WHY IT WAS WRITTEN:
  Translates HTTP requests into service calls and service results into HTTP
  responses. Controllers should contain no business logic — only HTTP concerns.

CONCEPTS TAUGHT:
  - @RestController: combining @Controller and @ResponseBody
  - @RequestMapping, @GetMapping, @PostMapping, @PutMapping, @DeleteMapping
  - @PathVariable: binding URL path segments to method parameters
  - @RequestParam(required=false): optional query parameters
  - @RequestBody with @Valid: deserializing and validating JSON request bodies
  - ResponseEntity: controlling both HTTP status code and response body
  - HTTP status codes: 200 OK, 201 Created, 204 No Content
  - Returning CompletableFuture from a controller for async endpoints
  - Method references: ResponseEntity::ok


9. exception/DeviceNotFoundException.java
------------------------------------------
WHAT IT DOES:
  A custom unchecked exception thrown by DeviceService when a device ID lookup
  returns no result. Carries a descriptive message including the missing ID.

WHY IT WAS WRITTEN:
  Using a custom exception allows GlobalExceptionHandler to catch this specific
  type and map it to HTTP 404 — without it, all "not found" errors would either
  be swallowed or result in a generic 500 error.

CONCEPTS TAUGHT:
  - Custom exceptions: why domain-specific exceptions beat generic ones
  - RuntimeException vs checked Exception: when to use each
  - Exception message conventions: including relevant context (the ID) in the message


10. exception/GlobalExceptionHandler.java
------------------------------------------
WHAT IT DOES:
  Catches exceptions thrown in any controller and converts them to structured
  JSON error responses with appropriate HTTP status codes:
    DeviceNotFoundException         → 404 Not Found
    IllegalArgumentException        → 400 Bad Request
    MethodArgumentNotValidException → 400 Bad Request

WHY IT WAS WRITTEN:
  Without it, unhandled exceptions would result in Spring's default HTML error
  page or a generic 500 response. This class centralizes error handling so every
  exception produces a consistent JSON response format and controllers stay clean.

  The validation handler builds a human-readable field error summary from
  BindingResult (e.g., "[name:Device name is required]") and returns it directly
  in the error response — giving API consumers a clear, actionable error message.

CONCEPTS TAUGHT:
  - @RestControllerAdvice: global exception interceptor for REST controllers
  - @ExceptionHandler: mapping specific exception types to handler methods
  - ResponseEntity: returning both a status code and a body from an error handler
  - Map.of(): building simple immutable maps for JSON response bodies
  - BindingResult / FieldErrors: inspecting validation failure details
  - Separation of concerns: keeping error-handling logic out of controllers


11. DevicemonitorApplicationTests.java (test)
----------------------------------------------
WHAT IT DOES:
  Unit tests for DeviceService. Tests all CRUD operations and verifies:
    - registerDevice saves a device and throws on duplicate IP
    - getDeviceById returns the device or throws if not found
    - getAllDevices returns all devices sorted, or filters by status
    - updateDevice only modifies provided fields
    - deleteDevice calls repository.delete() or throws if not found

WHY IT WAS WRITTEN:
  Verifies that DeviceService enforces its business rules correctly, in
  isolation from the database and HTTP layer. Tests also serve as documentation
  — they show exactly what behavior is expected in each scenario.

CONCEPTS TAUGHT:
  - Unit testing: testing one class in isolation
  - Mockito: creating mock implementations of dependencies
  - @Mock: creating a fake repository
  - @InjectMocks: injecting mocks into the class under test
  - @ExtendWith(MockitoExtension.class): integrating Mockito with JUnit 5
  - @BeforeEach: test setup that runs before every test
  - when().thenReturn(): stubbing — controlling what a mock returns
  - verify(): asserting that a mock method was called (or not called)
  - any(Device.class): Mockito argument matchers
  - assertThat() with AssertJ: fluent, readable assertions
  - assertThatThrownBy(): testing that code throws expected exceptions
  - Optional.of() / Optional.empty() in stubs: simulating found/not-found DB results


ARCHITECTURE SUMMARY
====================

Request flow:
  HTTP Request
      ↓
  DeviceController          (parse HTTP, call service, return ResponseEntity)
      ↓
  DeviceService             (enforce business rules, orchestrate operations)
      ↓
  DeviceRepository          (generated JPA implementation)
      ↓
  H2 In-Memory Database     (resets on restart; use /h2-console to inspect)

On startup:
  Spring Boot initializes → DataSeeder.run() → seeds 7 devices if DB is empty

Error flow:
  Service throws exception → propagates through controller → GlobalExceptionHandler
  converts it to a JSON error response with the correct HTTP status code

Async flow (health checks):
  Controller returns CompletableFuture → Spring MVC handles async response
  Service uses supplyAsync() to ping devices on background threads
  checkAllDevicesHealth() fans out N concurrent pings, waits via allOf().join()
