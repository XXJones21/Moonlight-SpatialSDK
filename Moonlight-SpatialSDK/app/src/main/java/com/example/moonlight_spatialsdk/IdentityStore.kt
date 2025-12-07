package com.example.moonlight_spatialsdk

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

object IdentityStore {
  private const val PREFS_NAME = "moonlight_identity"
  private const val KEY_UNIQUE_ID = "unique_id"
  private const val CERT_PREFIX = "cert_"

  fun getOrCreateUniqueId(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val existing = prefs.getString(KEY_UNIQUE_ID, null)
    if (existing != null) return existing

    val random = SecureRandom()
    val bytes = ByteArray(8)
    random.nextBytes(bytes)
    val newId = bytes.joinToString("") { "%02x".format(it) }
    prefs.edit().putString(KEY_UNIQUE_ID, newId).apply()
    return newId
  }

  fun saveServerCert(context: Context, host: String, cert: X509Certificate) {
    val encoded = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(CERT_PREFIX + host, encoded)
        .apply()
  }

  fun loadServerCert(context: Context, host: String): X509Certificate? {
    val encoded =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(CERT_PREFIX + host, null)
            ?: return null
    return try {
      val bytes = Base64.decode(encoded, Base64.NO_WRAP)
      val cf = CertificateFactory.getInstance("X.509")
      cf.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    } catch (e: Exception) {
      null
    }
  }

  fun clearAll(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
  }
}


