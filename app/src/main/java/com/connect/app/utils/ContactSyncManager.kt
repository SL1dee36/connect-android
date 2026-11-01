package com.connect.app.utils

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.connect.app.ConnectApplication
import com.connect.app.data.model.DeviceContact
import com.connect.app.data.model.MatchedContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ContactSyncManager {

    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun normalizePhone(phone: String): String {
        var clean = phone.replace(Regex("[^0-9+]"), "")
        if (clean.startsWith("8") && clean.length == 11) {
            clean = "+7" + clean.substring(1)
        } else if (clean.startsWith("7") && clean.length == 11) {
            clean = "+$clean"
        }
        return clean
    }

    suspend fun getDeviceContacts(context: Context): List<DeviceContact> = withContext(Dispatchers.IO) {
        if (!hasContactsPermission(context)) return@withContext emptyList()

        val list = mutableListOf<DeviceContact>()
        val seenPhones = mutableSetOf<String>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val id = if (idIdx >= 0) it.getString(idIdx) else ""
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "Без имени" else "Без имени"
                    val number = if (numberIdx >= 0) it.getString(numberIdx) ?: "" else ""

                    val normalized = normalizePhone(number)
                    if (normalized.length >= 5 && !seenPhones.contains(normalized)) {
                        seenPhones.add(normalized)
                        list.add(
                            DeviceContact(
                                id = id,
                                name = name,
                                phoneNumber = number,
                                normalizedPhone = normalized
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext list
    }

    suspend fun matchContactsOnServer(
        context: Context,
        deviceContacts: List<DeviceContact>
    ): List<MatchedContact> = withContext(Dispatchers.IO) {
        if (deviceContacts.isEmpty()) return@withContext emptyList()

        val app = ConnectApplication.instance
        val token = app.prefs.getToken() ?: return@withContext emptyList()
        val authHeader = "Bearer $token"

        val phones = deviceContacts.map { it.normalizedPhone }

        return@withContext try {
            val response = app.apiClient.service.matchPhones(authHeader, mapOf("phones" to phones))
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.matches
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
