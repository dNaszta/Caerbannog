# Test-only TLS material

`ca.pem`, `server.pem` and `server.key` are a throwaway self-signed CA ("Caerbannog Test CA") and a server certificate for `localhost`. They exist only so the live-broker tests can start a RabbitMQ container with TLS (`rabbitmq.conf`). The private key protects nothing: it was generated for this fixture, is public, and must never be used for anything real. Secret scanners will flag `server.key`; that is expected.
