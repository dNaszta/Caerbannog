# Third-party software

Caerbannog is MIT licensed (see `LICENSE`). The installers bundle the components below. Every one is permissively
licensed, or dual-licensed with a permissive option, which is the option Caerbannog uses. Checked against the
desktop app's runtime classpath (versions pinned in the Gradle build files).

| Component | License used |
|---|---|
| Kotlin stdlib, kotlinx (coroutines, serialization, datetime), Compose Multiplatform, Skiko, AndroidX/JetBrains lifecycle and savedstate, Material icons, JSpecify annotations (a runtime dependency of RE2/J) | Apache-2.0 |
| Ktor client, Netty, Gson, Snappy, LZ4, HKDF, Apache Qpid Proton-J, RabbitMQ stream client and credentials | Apache-2.0 (the RabbitMQ stream client is offered under Apache-2.0 or MPL-2.0) |
| Eclipse Paho MQTT 5 client | Eclipse Distribution License 1.0 (BSD-3-Clause); also offered under EPL-2.0 |
| JNA | Apache-2.0 (also offered under LGPL-2.1-or-later) |
| java-keyring, jkeychain, zstd-jni, RE2/J 1.9 (derived from Google RE2 and Go; Copyright The Go Authors and Google; the match preview of the Access view; in `core`, so on the runtime classpath of `desktopApp` and `mcp-server`) | BSD-3-Clause / BSD-2-Clause |
| dbus-java, secret-service, SLF4J | MIT |
| OpenJDK runtime embedded by jpackage | GPL-2.0 with Classpath Exception |

The BSD-3-Clause notice of RE2/J (required for binary redistribution, which the installers are) is kept in `licenses/RE2J-LICENSE.txt`. The licence of RE2/J was read from the GitHub tag `re2j-1.9` (the jar carries no LICENSE file; its pom says "Go License", the BSD-3-Clause text of the Go project); the licence of JSpecify 1.0.1 (Apache-2.0) from its pom, both on 2026-11-27.

Test-only dependencies (JUnit, Testcontainers 2.0.5 (MIT, from M2-10; modules `testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-rabbitmq`), Ktor mock engine, and, from M8-13, `com.rabbitmq:amqp-client` 5.37.0, the RabbitMQ Java AMQP 0-9-1 client; used under its Apache-2.0 license option, the client being offered under Apache-2.0, MPL-2.0 or GPL-2.0-or-later) are not shipped in the installers. `amqp-client` is added to test source sets only (approved by the product owner on 2026-11-09) and must never appear on the runtime classpath of `desktopApp` or `mcp-server`.

The application icon (`desktopApp/src/main/resources/rabbit.png` and `rabbit.icns`) is original artwork made for this project and covered by the MIT license; it is not the RabbitMQ logo.
