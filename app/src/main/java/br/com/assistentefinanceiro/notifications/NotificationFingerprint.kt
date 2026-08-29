package br.com.assistentefinanceiro.notifications

import java.security.MessageDigest

object NotificationFingerprint {
    fun create(
        packageName: String,
        title: String,
        body: String,
        postedAt: Long,
    ): String {
        val canonicalContent = listOf(
            packageName.trim(),
            title.trim(),
            body.trim(),
            postedAt.toString(),
        ).joinToString(separator = "\u001F")

        return MessageDigest.getInstance("SHA-256")
            .digest(canonicalContent.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
    }
}
