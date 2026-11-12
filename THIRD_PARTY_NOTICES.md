# Third-party software

Caerbannog is MIT licensed (see `LICENSE`). The installers bundle the components below. Every one is permissively
licensed, or dual-licensed with a permissive option, which is the option Caerbannog uses. Checked against the
desktop app's runtime classpath (versions pinned in the Gradle build files).

| Component | License used |
|---|---|
| Kotlin stdlib, kotlinx (coroutines, serialization, datetime), Compose Multiplatform, Skiko, AndroidX/JetBrains lifecycle and savedstate, Material icons | Apache-2.0 |
| Ktor client, Netty, Gson, Snappy, LZ4, HKDF, Apache Qpid Proton-J, RabbitMQ stream client and credentials | Apache-2.0 (the RabbitMQ stream client is offered under Apache-2.0 or MPL-2.0) |
| Eclipse Paho MQTT 5 client | Eclipse Distribution License 1.0 (BSD-3-Clause); also offered under EPL-2.0 |
| JNA | Apache-2.0 (also offered under LGPL-2.1-or-later) |
| java-keyring, jkeychain, zstd-jni | BSD-3-Clause / BSD-2-Clause |
| dbus-java, secret-service, SLF4J | MIT |
| OpenJDK runtime embedded by jpackage | GPL-2.0 with Classpath Exception |

Test-only dependencies (JUnit, Testcontainers, Ktor mock engine, and, from M8-13, `com.rabbitmq:amqp-client` 5.37.0, the RabbitMQ Java AMQP 0-9-1 client; used under its Apache-2.0 license option, the client being offered under Apache-2.0, MPL-2.0 or GPL-2.0-or-later) are not shipped in the installers. `amqp-client` is added to test source sets only (approved by the product owner on 2026-11-09) and must never appear on the runtime classpath of `desktopApp` or `mcp-server`.
