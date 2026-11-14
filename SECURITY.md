# Security policy

## Reporting a vulnerability

Please report security problems privately, using GitHub's private vulnerability reporting: open the **Security** tab of this repository and choose **Report a vulnerability**. Please do not open a public issue for a vulnerability.

Include what you found, how to reproduce it, and the version (see the installer name or the release tag). This is a small project maintained by one person, so replies can take a few days.

## Supported versions

Only the latest release receives fixes.

## What is in scope

Caerbannog is a desktop client for RabbitMQ and an MCP server. Reports about leaking a password (it must stay in the OS keychain and in memory), about the app reading, moving or changing queue messages outside the documented safeguards (see the Safety section of the README), or about writing to a RabbitMQ 3.x broker are especially welcome.
