package com.asapp

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Email + password login through Firebase Authentication (REST, free Spark plan). */
object AuthApi {
    class Res(val ok: Boolean, val json: JSONObject?, val err: String)

    private fun call(url: String, body: String, ct: String = "application/json"): Res {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 10000; c.readTimeout = 12000
            c.doOutput = true; c.setRequestProperty("Content-Type", ct)
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "{}"
            val o = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (code in 200..299) Res(true, o, "")
            else Res(false, null, o.optJSONObject("error")?.optString("message", "ERROR") ?: "ERROR")
        } catch (e: Exception) { Res(false, null, "NETWORK") }
    }

    private fun idt(path: String) = "https://identitytoolkit.googleapis.com/v1/accounts:$path?key=${Keys.FB_API_KEY}"

    fun signUp(email: String, pw: String) =
        call(idt("signUp"), JSONObject().put("email", email).put("password", pw).put("returnSecureToken", true).toString())
    fun signIn(email: String, pw: String) =
        call(idt("signInWithPassword"), JSONObject().put("email", email).put("password", pw).put("returnSecureToken", true).toString())
    fun sendVerify(idToken: String) =
        call(idt("sendOobCode"), JSONObject().put("requestType", "VERIFY_EMAIL").put("idToken", idToken).toString())
    fun sendReset(email: String) =
        call(idt("sendOobCode"), JSONObject().put("requestType", "PASSWORD_RESET").put("email", email).toString())
    fun refresh(rt: String) =
        call("https://securetoken.googleapis.com/v1/token?key=${Keys.FB_API_KEY}", "grant_type=refresh_token&refresh_token=$rt", "application/x-www-form-urlencoded")

    /** true = verified, false = not yet, null = could not check */
    fun lookupVerified(idToken: String): Boolean? {
        val r = call(idt("lookup"), JSONObject().put("idToken", idToken).toString())
        if (!r.ok) return null
        return r.json?.optJSONArray("users")?.optJSONObject(0)?.optBoolean("emailVerified", false)
    }

    fun friendly(err: String): String = when {
        err == "NETWORK" -> "NO INTERNET. TRY AGAIN WHEN YOU ARE ONLINE."
        err.startsWith("EMAIL_EXISTS") -> "THIS EMAIL ALREADY HAS AN ACCOUNT. USE LOGIN."
        err.startsWith("INVALID_LOGIN_CREDENTIALS") || err.startsWith("INVALID_PASSWORD") || err.startsWith("EMAIL_NOT_FOUND") -> "WRONG EMAIL OR PASSWORD."
        err.startsWith("WEAK_PASSWORD") -> "PASSWORD MUST HAVE AT LEAST 6 CHARACTERS."
        err.startsWith("INVALID_EMAIL") -> "THIS EMAIL IS NOT VALID."
        err.startsWith("TOO_MANY_ATTEMPTS") -> "TOO MANY TRIES. WAIT A WHILE AND TRY AGAIN."
        err.startsWith("OPERATION_NOT_ALLOWED") -> "EMAIL LOGIN IS NOT TURNED ON IN FIREBASE (AUTHENTICATION > SIGN-IN METHOD > EMAIL/PASSWORD)."
        err.startsWith("USER_DISABLED") -> "THIS ACCOUNT IS DISABLED."
        err.startsWith("API_KEY") || err.startsWith("INVALID_API_KEY") -> "FIREBASE API KEY IS WRONG (KEYS.KT)."
        else -> "ERROR: $err"
    }

    // ---- crypto helpers ----
    fun rnd(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    fun pbkdf2(pw: String, salt: ByteArray, iter: Int = 60000): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(pw.toCharArray(), salt, iter, 256)).encoded
    fun same(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)
    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun b64d(s: String) = android.util.Base64.decode(s, android.util.Base64.DEFAULT)

    /** Same account (uid) always gives the same AS-ID. n only changes if two people collide. */
    fun candId(uid: String, n: Int): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val h = MessageDigest.getInstance("SHA-256").digest("$uid:$n".toByteArray())
        return "AS-" + (0 until 6).map { chars[(h[it].toInt() and 0xFF) % chars.length] }.joinToString("")
    }

    /** Private chat key, locked with the password, stored in the user's own Firebase space. */
    fun makeBackup(pw: String, privB64: String, pubB64: String): JSONObject {
        val salt = rnd(16); val iv = rnd(12)
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pbkdf2(pw, salt), "AES"), GCMParameterSpec(128, iv))
        return JSONObject().put("salt", b64(salt)).put("iv", b64(iv)).put("ct", b64(ci.doFinal(privB64.toByteArray()))).put("pub", pubB64)
    }
    /** returns Pair(privB64, pubB64) or null if the password cannot open it */
    fun openBackup(pw: String, o: JSONObject): Pair<String, String>? = try {
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.DECRYPT_MODE, SecretKeySpec(pbkdf2(pw, b64d(o.getString("salt"))), "AES"), GCMParameterSpec(128, b64d(o.getString("iv"))))
        Pair(String(ci.doFinal(b64d(o.getString("ct")))), o.getString("pub"))
    } catch (e: Exception) { null }
}
