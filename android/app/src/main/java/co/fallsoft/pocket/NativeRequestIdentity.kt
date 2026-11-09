package co.fallsoft.pocket

/** Shared request revision for draft isolation and attention. A native ID can
 * be replaced or reused; source-scoped callers must compare its content too.
 * Retains the existing RequestCard fingerprint and persisted draft keys.
 */
internal fun nativeRequestFingerprint(serialized:String):String =
    java.security.MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray())
        .take(12).joinToString(""){"%02x".format(it)}
