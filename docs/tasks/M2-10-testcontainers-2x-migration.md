# Move the broker tests to Testcontainers 2.x (optional)

Milestone: M2 (optional, any time)    Depends on: none    Size: S

Branch: `m2-10-testcontainers-2x` from `main`. Needs Docker. Needs product owner approval of the dependency bump before starting (rule: dependency changes are decided).

## Goal
`data-rabbit` broker tests run on Testcontainers 2.x with no deprecation warnings from our own code and without the `api.version` pin, all `@broker` tests unchanged in behaviour.

## Why
`docs/spec.md` Risks (Testcontainers deprecation, Docker API pin). M2-02 spike answer 6: on 1.21.3 the warning is the compile-time deprecation of `withPluginsEnabled`; a trial of 2.0.5 passed the existing tests and made the pin unnecessary, but 2.x deprecates `org.testcontainers.containers.RabbitMQContainer` too, so the fix is a migration, not a bump. Low value: do it only when 1.x stops working with a new Docker Desktop or when the product owner wants a warning-free build.

## Scope
In: rename artifacts (`testcontainers-junit-jupiter`, `testcontainers-rabbitmq`, BOM `testcontainers-bom` 2.x; use a version older than two weeks at the time); move to `org.testcontainers.rabbitmq.RabbitMQContainer` (no `withPluginsEnabled`; it has `withAdminUser`, `withAdminPassword`, `withRabbitMQConfig*`); enable plugins by mounting an `enabled_plugins` file with `withCopyFileToContainer` (this also removes the deprecated method); try removing the `api.version` pin in the `brokerTest` task and keep it if the Docker engine still needs it; share one container-factory function in test code instead of the copy in each test class (`PermissionsEndpointSpikeTest`, `RabbitMqContainerSpikeTest`, `RealBrokerPermissionCheckTest`, `RealBrokerReachabilityTest`, `StreamManagementApiSpikeTest`) if it stays small.
Out: any production code; changing assertions of existing tests, except deleting the spike test `Q6 RabbitMQContainer withPluginsEnabled is annotated Deprecated...` in `StreamManagementApiSpikeTest` (it asserts the 1.x annotation and the class it reflects on moves).

## Acceptance
- `./gradlew :data-rabbit:brokerTest --rerun-tasks` green twice (same test count as before the change, 25 or more at the time of writing).
- Compile output of `:data-rabbit:compileTestKotlin` has no `withPluginsEnabled` or `RabbitMQContainer` deprecation warning.
- The two stream plugins are enabled and the TLS conf still applies (the spike's Q7 tests prove it).
- `docs/spec.md` Risks entries (Testcontainers deprecation, API version pin) updated with the result.

## Design notes
Test source set only. The container for "plugin missing" (Q7) still starts with only `rabbitmq_stream` in its `enabled_plugins` file.

## Open points
- Whether 2.x keeps needing the `api.version` pin on the Docker Desktop in use (the trial said no).
- 1.21.4 patch bump was not tried; it is not a fix for the warning.

## Definition of done
Tests green twice, no unrelated changes, spec Risks updated, task index row updated.
