package dev.caerbannog.core.publish

/**
 * The reason shown where test publishing is switched off because the user has no write permission on [vhost]. One text for every surface that
 * gates publishing on the profile's declared permission (the AMQP dialog and the test message of an MQTT route).
 */
fun noWritePermission(vhost: String): String = "Test publishing is disabled: no write permission on virtual host $vhost."
