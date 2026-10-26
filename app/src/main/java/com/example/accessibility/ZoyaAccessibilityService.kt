package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ZoyaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ZoyaAccessibilityService? = null

        // WhatsApp flags
        var shouldAutoClickWhatsApp = false
            set(value) {
                field = value
                if (value) {
                    mainHandler.postDelayed({ field = false }, 12000)
                    scheduleWhatsAppSendRetry(0)
                }
            }

        var shouldSearchAndSendWhatsApp = false
        var targetWhatsAppContact: String? = null
        var targetWhatsAppMessage: String? = null

        // Instagram flags
        var shouldAutoClickInstagram = false
            set(value) {
                field = value
                if (value) {
                    mainHandler.postDelayed({ field = false }, 12000)
                    scheduleInstagramSendRetry(0)
                }
            }
        var targetInstagramUser: String? = null
        var targetInstagramMessage: String? = null

        // YouTube flags
        var shouldAutoClickYouTube = false
            set(value) {
                field = value
                if (value) {
                    mainHandler.postDelayed({ field = false }, 12000)
                    scheduleYouTubeClickRetry(0)
                }
            }

        private val mainHandler = Handler(Looper.getMainLooper())

        fun isServiceRunning(): Boolean = instance != null

        fun dispatchGestureClick(x: Float, y: Float): Boolean {
            val inst = instance ?: return false
            if (x <= 0f || y <= 0f) return false
            val path = Path()
            path.moveTo(x, y)
            path.lineTo(x, y)

            val builder = GestureDescription.Builder()
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            val gesture = builder.build()

            return inst.dispatchGesture(gesture, null, null)
        }

        fun clickTextOnScreen(text: String): Boolean {
            val inst = instance ?: return false
            val root = inst.rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    val bounds = Rect()
                    current.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty && (current.isClickable || current == node)) {
                        val x = bounds.centerX().toFloat()
                        val y = bounds.centerY().toFloat()
                        if (dispatchGestureClick(x, y)) return true
                    }
                    current = current.parent
                }
            }
            return false
        }

        private fun scheduleWhatsAppSendRetry(attempt: Int) {
            if (!shouldAutoClickWhatsApp || attempt > 15) return
            mainHandler.postDelayed({
                val inst = instance
                if (inst != null && shouldAutoClickWhatsApp) {
                    val root = inst.rootInActiveWindow
                    if (root != null) {
                        val clicked = inst.searchAndClickSendButton(root)
                        if (clicked) {
                            Log.d("MayaAccessibility", "WhatsApp send button clicked on retry attempt $attempt")
                            shouldAutoClickWhatsApp = false
                            return@postDelayed
                        }
                    }
                    scheduleWhatsAppSendRetry(attempt + 1)
                }
            }, 350)
        }

        private fun scheduleInstagramSendRetry(attempt: Int) {
            if (!shouldAutoClickInstagram || attempt > 15) return
            mainHandler.postDelayed({
                val inst = instance
                if (inst != null && shouldAutoClickInstagram) {
                    val root = inst.rootInActiveWindow
                    if (root != null) {
                        val clicked = inst.searchAndClickInstagramSend(root)
                        if (clicked) {
                            Log.d("MayaAccessibility", "Instagram send button clicked on retry $attempt")
                            shouldAutoClickInstagram = false
                            return@postDelayed
                        }
                    }
                    scheduleInstagramSendRetry(attempt + 1)
                }
            }, 400)
        }

        private fun scheduleYouTubeClickRetry(attempt: Int) {
            if (!shouldAutoClickYouTube || attempt > 15) return
            mainHandler.postDelayed({
                val inst = instance
                if (inst != null && shouldAutoClickYouTube) {
                    val root = inst.rootInActiveWindow
                    if (root != null) {
                        val clicked = inst.searchAndClickYouTubeFirstVideo(root)
                        if (clicked) {
                            Log.d("MayaAccessibility", "YouTube first video clicked on retry attempt $attempt")
                            shouldAutoClickYouTube = false
                            return@postDelayed
                        }
                    }
                    scheduleYouTubeClickRetry(attempt + 1)
                }
            }, 400)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("MayaAccessibility", "Maya Automation Accessibility Service Connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: ""

        // WhatsApp auto-click send
        if (shouldAutoClickWhatsApp && packageName.contains("whatsapp")) {
            val root = rootInActiveWindow ?: return
            val clicked = searchAndClickSendButton(root)
            if (clicked) {
                Log.d("MayaAccessibility", "WhatsApp Send button clicked from event!")
                shouldAutoClickWhatsApp = false
            }
        }

        // WhatsApp search & send flow
        if (shouldSearchAndSendWhatsApp && packageName.contains("whatsapp")) {
            handleWhatsAppSearchFlow()
        }

        // Instagram auto-click send
        if (shouldAutoClickInstagram && packageName.contains("instagram")) {
            val root = rootInActiveWindow ?: return
            val clicked = searchAndClickInstagramSend(root)
            if (clicked) {
                Log.d("MayaAccessibility", "Instagram send clicked successfully!")
                shouldAutoClickInstagram = false
            }
        }

        // YouTube auto-click first video
        if (shouldAutoClickYouTube && packageName.contains("youtube")) {
            val root = rootInActiveWindow ?: return
            val clicked = searchAndClickYouTubeFirstVideo(root)
            if (clicked) {
                Log.d("MayaAccessibility", "YouTube video clicked from event!")
                shouldAutoClickYouTube = false
            }
        }
    }

    // --- WhatsApp Automation ---

    fun searchAndClickSendButton(node: AccessibilityNodeInfo): Boolean {
        val idsToTry = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/send_button_container",
            "com.whatsapp:id/entry_send_button",
            "com.whatsapp:id/btn_send"
        )
        for (id in idsToTry) {
            val sendButtons = node.findAccessibilityNodeInfosByViewId(id)
            for (button in sendButtons) {
                if (performSafeClick(button)) {
                    return true
                }
            }
        }
        return recursiveSearchAndClickSend(node)
    }

    private fun recursiveSearchAndClickSend(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        if (desc == "send" || desc == "send message" || desc == "भेजें" ||
            desc.contains("bheje") || desc == "envio" || text == "send" || text == "भेजें"
        ) {
            if (performSafeClick(node)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (recursiveSearchAndClickSend(child)) return true
            }
        }
        return false
    }

    private fun handleWhatsAppSearchFlow() {
        val contact = targetWhatsAppContact ?: return
        val message = targetWhatsAppMessage ?: ""
        val root = rootInActiveWindow ?: return

        val searchButtons = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/menuitem_search")
            .ifEmpty { root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/search_holder") }
        if (searchButtons.isNotEmpty()) {
            performSafeClick(searchButtons[0])
        }

        val searchInputs = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/search_input")
        if (searchInputs.isNotEmpty()) {
            val inputNode = searchInputs[0]
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, contact)
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            mainHandler.postDelayed({
                val activeRoot = rootInActiveWindow ?: return@postDelayed
                val contactNodes = activeRoot.findAccessibilityNodeInfosByText(contact)
                if (contactNodes.isNotEmpty()) {
                    performSafeClick(contactNodes[0])
                    shouldSearchAndSendWhatsApp = false
                    shouldAutoClickWhatsApp = true
                }
            }, 800)
        }
    }

    // --- Instagram Automation ---

    fun searchAndClickInstagramSend(root: AccessibilityNodeInfo): Boolean {
        val sendIds = listOf(
            "com.instagram.android:id/row_thread_composer_button_send",
            "com.instagram.android:id/action_bar_button_action",
            "com.instagram.android:id/button_send"
        )
        for (id in sendIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (performSafeClick(node)) return true
            }
        }

        // Recursive search for Send description / text in Instagram
        val descNodes = root.findAccessibilityNodeInfosByText("Send")
        for (node in descNodes) {
            if (performSafeClick(node)) return true
        }
        return false
    }

    // --- YouTube Automation ---

    fun searchAndClickYouTubeFirstVideo(root: AccessibilityNodeInfo): Boolean {
        val videoIds = listOf(
            "com.google.android.youtube:id/thumbnail",
            "com.google.android.youtube:id/video_info_view",
            "com.google.android.youtube:id/title",
            "com.google.android.youtube:id/compact_renderer"
        )
        for (id in videoIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && bounds.centerY() > 220) {
                    if (performSafeClick(node)) return true
                }
            }
        }
        return recursiveSearchYouTubeVideo(root)
    }

    private fun recursiveSearchYouTubeVideo(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.centerY() > 240 && bounds.height() > 80 && bounds.width() > 200) {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val isVideo = desc.contains("views") || desc.contains("video") ||
                    desc.contains("minute") || desc.contains("ago") || desc.contains("official")

            if (isVideo || node.isClickable) {
                if (performSafeClick(node)) return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (recursiveSearchYouTubeVideo(child)) return true
            }
        }
        return false
    }

    private fun performSafeClick(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && bounds.centerX() > 0 && bounds.centerY() > 0) {
            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat()
            if (dispatchGestureClick(x, y)) return true
        }

        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    override fun onInterrupt() {
        Log.d("MayaAccessibility", "Maya Automation Accessibility Service Interrupted")
    }
}
