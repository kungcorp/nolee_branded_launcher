package ai.nolee.brandedlauncher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Base64
import org.json.JSONObject

/** Local ADB-only recipient setup. No exported activity extras or general profile write API. */
class ProvisioningProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // Defence in depth: even an app with DUMP must not impersonate the provisioning shell.
        check(Binder.getCallingUid() in setOf(0, 2000)) { "ADB shell required" }
        val prefs = requireNotNull(context).getSharedPreferences("profile", 0)
        return try {
            when (method) {
                "set_recipient" -> {
                    require(!arg.isNullOrEmpty() && arg.length <= 1024) { "Recipient required" }
                    val data = JSONObject(String(Base64.decode(arg, Base64.NO_WRAP), Charsets.UTF_8))
                    require(data.keys().asSequence().toSet() == setOf("name", "country")) { "Name and country required" }
                    val patch = ProfileUpdates.parse(mapOf(
                        "name" to data.getString("name"), "country" to data.getString("country")))
                    require(patch != null && !patch[ProfileField.Name].isNullOrBlank()) { "Invalid recipient" }
                    val editor = prefs.edit()
                    patch.forEach { (field, value) -> editor.putString(field.name, value) }
                    check(editor.commit()) { "Profile could not be saved" }
                }
                "get_recipient" -> Unit
                else -> error("Unknown provisioning method")
            }
            val data = JSONObject().put("name", prefs.getString("Name", ""))
                .put("country", prefs.getString("Country", ""))
            Bundle().apply {
                putBoolean("ok", true)
                putString("recipient", Base64.encodeToString(data.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            }
        } catch (e: Exception) {
            Bundle().apply { putBoolean("ok", false); putString("reason", e.message) }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
