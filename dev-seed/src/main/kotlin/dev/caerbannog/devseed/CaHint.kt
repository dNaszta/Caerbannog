package dev.caerbannog.devseed

/**
 * A wrong CA makes the stream client fail with `StreamException: null`; an empty message tells the developer nothing,
 * so this returns a hint for a null or empty [failureMessage] and null otherwise. It takes only the message, so no
 * password can reach it.
 */
fun caHint(failureMessage: String?, tls: Boolean = true): String? =
    if (failureMessage.isNullOrEmpty()) {
        "Hint: the failure has no message. The --ca file may not be the CA that signed the broker certificate " +
            "(dev:seed passes the fixture CA; check the --ca path and that the broker uses the matching certificate)."
    } else {
        null
    }
