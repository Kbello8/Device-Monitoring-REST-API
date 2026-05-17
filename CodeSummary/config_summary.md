# Config Package Summary

## Package Purpose

The `com.example.devicemonitor.config` package contains Spring `@Configuration` classes responsible for wiring AWS-backed infrastructure beans into the application context. These classes are intentionally separated from business logic so that environment-specific infrastructure concerns (cloud services, connections, credentials) are centralised and swappable without touching service or controller code.

---

## Files

### `AwsConfig.java`

**Location:** `devicemonitor/src/main/java/com/example/devicemonitor/config/AwsConfig.java`

**Responsibility:** Declares all Spring beans required when the application runs in an AWS environment — secrets from Secrets Manager, a Redis connection backed by ElastiCache, a general-purpose `RedisTemplate`, and an SQS client.

---

## Key Design Decisions and Patterns

### Profile-based activation (`@Profile("aws")`)

The entire class is annotated with `@Profile("aws")`, which means Spring only instantiates these beans when the application is started with `spring.profiles.active=aws`. During local development and unit-test runs the profile is absent, so the class is skipped entirely. This is a deliberate separation-of-concerns choice: developers can run and test the application without AWS credentials, network access, or any cloud services. The application falls back to its default in-memory infrastructure (H2 database, no Redis, no SQS).

### Constructor injection for mandatory dependencies

`AwsSecretsService` is injected through the constructor rather than via `@Autowired` field injection. This is Spring's recommended practice because it makes the dependency mandatory (Spring will not create the bean if `AwsSecretsService` is missing), makes the class easier to unit-test (you can pass a mock directly), and keeps the dependency visible at a glance.

### Centralised secret fetch

Rather than having each consuming service call AWS Secrets Manager independently, `awsSecrets()` fetches the entire `devicemonitor/prod` secret bundle once and publishes it as a shared `Map<String, String>` bean. This eliminates duplicate API calls, reduces Secrets Manager costs, and gives a single place to change the secret path when environments are added.

### Lettuce over Jedis

The Redis connection uses Lettuce (`LettuceConnectionFactory`), the default Spring Data Redis client. Lettuce shares a single thread-safe connection across the application, whereas Jedis uses a connection pool. For an application that may eventually adopt reactive patterns, Lettuce is the forward-compatible choice.

### Standalone Redis topology

`RedisStandaloneConfiguration` targets a single Redis primary (no clustering, no sentinel). This matches an ElastiCache deployment with cluster mode disabled — the typical starting point for a small production service. Port 6379 is hardcoded because it is the universal Redis default and never varies; only the hostname is externalised via the `spring.data.redis.host` property.

### SDK v2 default credential provider chain

`SqsClient` is built without explicit credentials. The AWS SDK v2 default credential provider chain resolves credentials automatically from the environment: the IAM role attached to the ECS task in production, environment variables in CI, or `~/.aws/credentials` locally. This avoids embedding long-lived access keys anywhere in the codebase or configuration files.

### Property externalisation

Both `redisHost` and `awsRegion` are bound via `@Value` from application properties. This means the same compiled artifact can be deployed to any region or pointed at any ElastiCache cluster by changing configuration alone — no recompile needed.

---

## Notable Methods

| Method | What it does |
|---|---|
| `awsSecrets()` | Calls `AwsSecretsService.getSecrets("devicemonitor/prod")` and exposes the result as a `Map<String,String>` bean consumed by other components that need individual secret values. |
| `redisConnectionFactory()` | Builds a `LettuceConnectionFactory` aimed at the configured ElastiCache host on port 6379. Consumed by `redisTemplate()`. |
| `redisTemplate(LettuceConnectionFactory)` | Creates the `RedisTemplate<String,Object>` used application-wide for caching reads/writes. Delegates connection management to the factory passed in as a parameter. |
| `sqsClient()` | Builds an `SqsClient` scoped to the configured AWS region using the SDK's default credential provider. Used by SQS producers/consumers elsewhere in the application. |

---

## Interactions with Other Packages

| This class uses | Role |
|---|---|
| `com.example.devicemonitor.service.AwsSecretsService` | Performs the actual AWS Secrets Manager API call; `AwsConfig` delegates to it and re-exports the result as a bean. |
| `spring.data.redis.*` properties (application config) | Supplies the ElastiCache hostname. |
| `aws.region` property (application config) | Supplies the AWS region for the SQS client. |

| Other packages consume | What they get |
|---|---|
| Any service needing secrets | The `Map<String,String> awsSecrets` bean — look up individual values by key. |
| Any service doing caching | The `RedisTemplate<String,Object>` bean for Redis operations. |
| SQS producers/consumers | The `SqsClient` bean for sending/receiving messages. |

The config package has no dependency on controllers, repositories, or domain entities — it sits at the infrastructure layer and is consumed upward by services.
