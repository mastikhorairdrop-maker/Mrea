package com.example.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.provider.ContactsContract
import com.example.accessibility.ZoyaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ToolExecutionEngine(private val context: Context) {

    suspend fun execute(name: String, args: JsonObject): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "openApp" -> {
                    val appName = args["packageName"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing packageName"
                    openAppGeneric(appName)
                }
                "searchAndCallContact" -> {
                    val contactName = args["contactName"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing contactName"
                    val useDialer = args["useDialer"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    val simSlot = args["simSlot"]?.jsonPrimitive?.content?.toIntOrNull()
                    callContact(contactName, useDialer, simSlot)
                }
                "sendWhatsAppMessage" -> {
                    val contactName = args["contactName"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing contactName"
                    val message = args["message"]?.jsonPrimitive?.content ?: ""
                    sendWhatsApp(contactName, message)
                }
                "shareContact" -> {
                    val contactName = args["contactName"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing contactName"
                    val recipientName = args["recipientName"]?.jsonPrimitive?.content
                    shareContactDetails(contactName, recipientName)
                }
                "sendInstagramMessage" -> {
                    val usernameOrName = args["usernameOrName"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing usernameOrName"
                    val message = args["message"]?.jsonPrimitive?.content ?: ""
                    sendInstagram(usernameOrName, message)
                }
                "sendGmail" -> {
                    val recipient = args["recipientEmail"]?.jsonPrimitive?.content ?: ""
                    val subject = args["subject"]?.jsonPrimitive?.content ?: ""
                    val body = args["body"]?.jsonPrimitive?.content ?: ""
                    sendEmail(recipient, subject, body)
                }
                "searchYouTube", "playYouTube", "playSongOnYouTube" -> {
                    val query = args["query"]?.jsonPrimitive?.content
                        ?: args["songName"]?.jsonPrimitive?.content
                        ?: return@withContext "Error: Missing query"
                    playOrSearchYouTube(query, autoClick = true)
                }
                "adjustVolume" -> {
                    val direction = args["direction"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing direction (up/down/mute/unmute/max)"
                    adjustSystemVolume(direction)
                }
                "setVolumePercent" -> {
                    val percentStr = args["percent"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing percent"
                    val percent = percentStr.toIntOrNull() ?: 50
                    setVolumePercent(percent)
                }
                "toggleTorch" -> {
                    val state = args["state"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing state (on/off)"
                    toggleTorch(state)
                }
                "setBrightness" -> {
                    val levelStr = args["level"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing level"
                    val level = levelStr.toIntOrNull() ?: 50
                    setBrightness(level)
                }
                "openNotificationPanel" -> {
                    openNotificationPanel()
                }
                "openQuickSettings" -> {
                    val service = ZoyaAccessibilityService.instance
                    if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
                        "Quick settings panel opened."
                    } else "Failed to open quick settings."
                }
                "clickTextOnScreen" -> {
                    val text = args["text"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing text"
                    val success = ZoyaAccessibilityService.clickTextOnScreen(text)
                    if (success) "Clicked on '$text'." else "Failed to click on '$text'."
                }
                "pressHome" -> {
                    val service = ZoyaAccessibilityService.instance
                    if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) {
                        "Navigated to Home screen."
                    } else "Accessibility service not active."
                }
                "pressBack" -> {
                    val service = ZoyaAccessibilityService.instance
                    if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) {
                        "Pressed back."
                    } else "Accessibility service not active."
                }
                "getSimCardInfo" -> {
                    getSimCardInfo()
                }
                "getBatteryStatus" -> {
                    getBatteryStatus()
                }
                "playMedia" -> {
                    val query = args["query"]?.jsonPrimitive?.content ?: return@withContext "Error: Missing query"
                    playOrSearchYouTube(query, autoClick = true)
                }
                else -> "Error: Tool $name not found."
            }
        } catch (e: Exception) {
            "Error executing $name: ${e.message}"
        }
    }

    private fun openAppGeneric(appName: String): String {
        val lowerName = appName.lowercase().trim()

        if (lowerName == "camera" || lowerName == "kamera" || lowerName == "कैमरा") {
            val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return "Camera opened"
            } catch (e: Exception) {}
        }

        if (lowerName == "youtube" || lowerName == "yt") {
            return playOrSearchYouTube("", autoClick = false)
        }

        if (lowerName == "instagram" || lowerName == "insta") {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage("com.instagram.android")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Instagram opened."
            }
        }

        if (lowerName == "whatsapp") {
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage("com.whatsapp")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "WhatsApp opened."
            }
        }

        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)

        var targetPackage: String? = null
        for (app in packages) {
            val name = pm.getApplicationLabel(app).toString().lowercase()
            if (name.contains(lowerName)) {
                targetPackage = app.packageName
                break
            }
        }

        if (targetPackage == null) {
            return "Could not find an installed app matching '$appName'"
        }

        val launchIntent = pm.getLaunchIntentForPackage(targetPackage)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            return "App '$appName' launched successfully."
        }
        return "Could not launch app '$appName'"
    }

    private fun callContact(nameOrNumber: String, useDialer: Boolean = false, simSlot: Int? = null): String {
        val isNumber = nameOrNumber.count { it.isDigit() } >= 7 || nameOrNumber.matches(Regex("^[0-9+\\-*#]+$"))

        val number = if (isNumber) {
            nameOrNumber.replace(Regex("[^0-9+*#]"), "")
        } else {
            val matches = findContacts(nameOrNumber)
            if (matches.isEmpty()) return "Could not find a phone number for '$nameOrNumber'. Babu, please tell the correct name or number."
            matches.first().second
        }

        val action = if (useDialer) Intent.ACTION_DIAL else Intent.ACTION_CALL
        val callIntent = Intent(action).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!useDialer && simSlot != null) {
            try {
                val slotIndex = simSlot - 1
                callIntent.putExtra("com.android.phone.force.slot", true)
                callIntent.putExtra("com.android.phone.extra.slot", slotIndex)
                callIntent.putExtra("simSlot", slotIndex)

                if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                    val phoneAccounts = telecomManager.callCapablePhoneAccounts
                    if (slotIndex in 0 until phoneAccounts.size) {
                        callIntent.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccounts[slotIndex])
                    }
                }
            } catch (e: Exception) {}
        }

        try {
            context.startActivity(callIntent)
            return if (useDialer) "Opened dialer with $nameOrNumber ($number)" else "Calling $nameOrNumber ($number)..."
        } catch (e: SecurityException) {
            return "Missing CALL_PHONE permission."
        }
    }

    private fun sendWhatsApp(nameOrNumber: String, message: String): String {
        if (message.isBlank()) {
            return "NEED_MESSAGE: Babu, $nameOrNumber ko kya message bhejna hai WhatsApp par?"
        }

        val isNumber = nameOrNumber.count { it.isDigit() } >= 7 || nameOrNumber.matches(Regex("^[0-9+\\-*#]+$"))

        val number = if (isNumber) {
            nameOrNumber
        } else {
            val matches = findContacts(nameOrNumber)
            if (matches.isNotEmpty()) {
                matches.first().second
            } else {
                null
            }
        }

        if (number != null) {
            var cleanNumber = number.replace(Regex("[^0-9]"), "")
            if (cleanNumber.length == 10) {
                cleanNumber = "91$cleanNumber"
            } else if (cleanNumber.length == 11 && cleanNumber.startsWith("0")) {
                cleanNumber = "91${cleanNumber.substring(1)}"
            }

            val url = "https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(message)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                ZoyaAccessibilityService.shouldAutoClickWhatsApp = true
                context.startActivity(intent)
                return "Sending '$message' to $nameOrNumber on WhatsApp."
            } catch (e: Exception) {
                ZoyaAccessibilityService.shouldAutoClickWhatsApp = false
                return "WhatsApp is not installed on this device."
            }
        } else {
            // Contact not in address book: search directly inside WhatsApp
            try {
                ZoyaAccessibilityService.targetWhatsAppContact = nameOrNumber
                ZoyaAccessibilityService.targetWhatsAppMessage = message
                ZoyaAccessibilityService.shouldSearchAndSendWhatsApp = true

                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage("com.whatsapp")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return "Searching for $nameOrNumber in WhatsApp to send '$message'."
                } else {
                    return "WhatsApp is not installed on this device."
                }
            } catch (e: Exception) {
                return "Failed to open WhatsApp: ${e.message}"
            }
        }
    }

    private fun shareContactDetails(contactName: String, recipientName: String?): String {
        val matches = findContacts(contactName)
        if (matches.isEmpty()) {
            return "Could not find any contact for '$contactName' in phone book."
        }
        val found = matches.first()
        val shareText = "Contact details: ${found.first} - ${found.second}"

        if (!recipientName.isNullOrBlank()) {
            return sendWhatsApp(recipientName, shareText)
        } else {
            // Share via system share sheet
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share ${found.first}'s Contact").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return "Found ${found.first}'s contact (${found.second}). Sharing now!"
        }
    }

    private fun sendInstagram(usernameOrName: String, message: String): String {
        if (message.isBlank()) {
            return "NEED_MESSAGE: Babu, Instagram par $usernameOrName ko kya message bhejna hai?"
        }

        val cleanUser = usernameOrName.replace("@", "").trim()

        try {
            // Copy message to clipboard for easy pasting / auto-send
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Instagram Message", message)
            clipboard.setPrimaryClip(clip)

            ZoyaAccessibilityService.targetInstagramUser = cleanUser
            ZoyaAccessibilityService.targetInstagramMessage = message
            ZoyaAccessibilityService.shouldAutoClickInstagram = true

            // Open Instagram Direct URL
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ig.me/m/$cleanUser")).apply {
                setPackage("com.instagram.android")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(intent)
                return "Opened Instagram Direct with $usernameOrName to send '$message'."
            } catch (e: Exception) {
                // Fallback to direct web or profile
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/_u/$cleanUser")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                return "Opened $usernameOrName's Instagram. Message '$message' copied to clipboard."
            }
        } catch (e: Exception) {
            return "Instagram is not installed: ${e.message}"
        }
    }

    private fun playOrSearchYouTube(query: String, autoClick: Boolean = true): String {
        val trimmedQuery = query.trim()

        val url = if (trimmedQuery.isNotEmpty()) {
            "https://www.youtube.com/results?search_query=${Uri.encode(trimmedQuery)}"
        } else {
            "https://www.youtube.com"
        }

        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.youtube")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (autoClick && trimmedQuery.isNotEmpty()) {
            ZoyaAccessibilityService.shouldAutoClickYouTube = true
        }

        try {
            context.startActivity(intent)
            return if (trimmedQuery.isNotEmpty()) {
                "Searching YouTube for '$trimmedQuery' and auto-playing video."
            } else {
                "Opened YouTube."
            }
        } catch (e: Exception) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
                return "Playing '$trimmedQuery' in browser."
            } catch (ex: Exception) {
                return "Could not open YouTube: ${ex.message}"
            }
        }
    }

    private fun sendEmail(recipient: String, subject: String, body: String): String {
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(emailIntent)
            return "Opened email client with draft."
        } catch (e: Exception) {
            return "No email client found."
        }
    }

    private fun findContacts(namePattern: String): List<Pair<String, String>> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        try {
            val fallbackUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val fbProjection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(fallbackUri, fbProjection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                val exactMatches = mutableListOf<Pair<String, String>>()
                val startsWithMatches = mutableListOf<Pair<String, String>>()
                val containsMatches = mutableListOf<Pair<String, String>>()

                val cleanPattern = namePattern.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
                val searchWords = cleanPattern.split(" ").filter { it.isNotEmpty() }

                while (cursor.moveToNext()) {
                    val contactName = cursor.getString(nameIdx) ?: continue
                    val contactNum = cursor.getString(numIdx) ?: continue
                    val cleanContactName = contactName.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()

                    if (cleanContactName.isEmpty()) continue

                    val contactNameNoSpace = cleanContactName.replace(" ", "")
                    val patternNoSpace = cleanPattern.replace(" ", "")

                    if (contactNameNoSpace == patternNoSpace || cleanContactName == cleanPattern) {
                        exactMatches.add(Pair(contactName, contactNum))
                    } else if (contactNameNoSpace.startsWith(patternNoSpace) || cleanContactName.startsWith(cleanPattern)) {
                        startsWithMatches.add(Pair(contactName, contactNum))
                    } else if (searchWords.isNotEmpty() && searchWords.all { cleanContactName.contains(it) }) {
                        containsMatches.add(Pair(contactName, contactNum))
                    } else if (contactNameNoSpace.contains(patternNoSpace) && patternNoSpace.length > 2) {
                        containsMatches.add(Pair(contactName, contactNum))
                    }
                }

                if (exactMatches.isNotEmpty()) return exactMatches.distinctBy { it.second }
                if (startsWithMatches.isNotEmpty()) return startsWithMatches.distinctBy { it.second }
                if (containsMatches.isNotEmpty()) return containsMatches.distinctBy { it.second }
            }
        } catch (e: Exception) {
            android.util.Log.e("MayaTools", "Error finding contact", e)
        }
        return emptyList()
    }

    private fun adjustSystemVolume(direction: String): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val streamType = android.media.AudioManager.STREAM_MUSIC
        try {
            when (direction.lowercase()) {
                "up" -> {
                    audioManager.adjustStreamVolume(streamType, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                    return "Volume increased."
                }
                "down" -> {
                    audioManager.adjustStreamVolume(streamType, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                    return "Volume decreased."
                }
                "mute" -> {
                    audioManager.adjustStreamVolume(streamType, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
                    return "Volume muted."
                }
                "unmute" -> {
                    audioManager.adjustStreamVolume(streamType, android.media.AudioManager.ADJUST_UNMUTE, android.media.AudioManager.FLAG_SHOW_UI)
                    return "Volume unmuted."
                }
                "max" -> {
                    val maxVol = audioManager.getStreamMaxVolume(streamType)
                    audioManager.setStreamVolume(streamType, maxVol, android.media.AudioManager.FLAG_SHOW_UI)
                    return "Volume set to maximum."
                }
                else -> return "Unknown direction. Use up, down, mute, unmute, or max."
            }
        } catch (e: Exception) {
            return "Failed to adjust volume: ${e.message}"
        }
    }

    private fun setVolumePercent(percent: Int): String {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val streamType = android.media.AudioManager.STREAM_MUSIC
            val maxVol = audioManager.getStreamMaxVolume(streamType)
            val targetVol = (maxVol * percent.coerceIn(0, 100)) / 100
            audioManager.setStreamVolume(streamType, targetVol, android.media.AudioManager.FLAG_SHOW_UI)
            return "Volume set to $percent%."
        } catch (e: Exception) {
            return "Failed to set volume: ${e.message}"
        }
    }

    private fun toggleTorch(state: String): String {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return "No flashlight hardware available."
            val turnOn = state.lowercase() == "on"
            cameraManager.setTorchMode(cameraId, turnOn)
            return if (turnOn) "Flashlight turned ON." else "Flashlight turned OFF."
        } catch (e: Exception) {
            return "Failed to toggle flashlight: ${e.message}"
        }
    }

    private fun setBrightness(level: Int): String {
        try {
            if (!android.provider.Settings.System.canWrite(context)) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:" + context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return "Please grant Write Settings permission to adjust screen brightness."
            }

            val brightness = (level.coerceIn(0, 100) * 255) / 100
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
            return "Screen brightness set to $level%."
        } catch (e: Exception) {
            return "Failed to set brightness: ${e.message}"
        }
    }

    private fun openNotificationPanel(): String {
        val service = ZoyaAccessibilityService.instance
        return if (service != null && service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) {
            "Notification panel opened."
        } else {
            "Accessibility service not active."
        }
    }

    private fun getSimCardInfo(): String {
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "READ_PHONE_STATE permission not granted. Defaulting to SIM 1."
        }
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            val phoneAccounts = telecomManager.callCapablePhoneAccounts
            "The device has ${phoneAccounts.size} active calling SIM cards."
        } catch (e: Exception) {
            "Active SIM card info not readable: ${e.message}"
        }
    }

    private fun getBatteryStatus(): String {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            "Battery level is at $level%."
        } catch (e: Exception) {
            "Battery status unavailable."
        }
    }
}
