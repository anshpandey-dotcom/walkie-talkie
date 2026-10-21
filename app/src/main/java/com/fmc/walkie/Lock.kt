package com.fmc.walkie

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest
import java.security.SecureRandom

class Lock(private val activity: Activity) {

    private val prefs = activity.getSharedPreferences("lock", Context.MODE_PRIVATE)
    private var dialog: Dialog? = null
    private var firstPattern: String? = null

    val isSet: Boolean
        get() = prefs.contains("secret")

    fun gate(onUnlocked: () -> Unit) {
        if (dialog?.isShowing == true) return
        if (isSet) prompt(onUnlocked) else setup(onUnlocked)
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun setup(onUnlocked: () -> Unit) {
        firstPattern = null
        val title = TextView(activity).apply {
            text = "Draw a new unlock pattern"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        val hint = TextView(activity).apply { text = "Connect at least 4 dots, then draw again to confirm" }
        val pattern = PatternView(activity)
        pattern.onComplete = { drawn ->
            if (drawn.size < 4) {
                hint.text = "Too short. Use at least 4 dots."
            } else if (firstPattern == null) {
                firstPattern = encode(drawn)
                hint.text = "Draw the same pattern again to confirm"
            } else if (firstPattern == encode(drawn)) {
                save(firstPattern!!)
                dismiss()
                onUnlocked()
            } else {
                firstPattern = null
                hint.text = "Did not match. Draw a new pattern."
            }
        }
        show(title, hint, pattern, null, onUnlocked, allowBiometric = false)
    }

    private fun prompt(onUnlocked: () -> Unit) {
        val title = TextView(activity).apply {
            text = "Draw pattern to unlock"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        val hint = TextView(activity).apply { text = "" }
        val pattern = PatternView(activity)
        pattern.onComplete = { drawn ->
            if (verify(encode(drawn))) {
                dismiss()
                onUnlocked()
            } else {
                hint.text = "Wrong pattern"
            }
        }
        show(title, hint, pattern, null, onUnlocked, allowBiometric = true)
        offerBiometric(hint, onUnlocked)
    }

    private fun show(
        title: TextView,
        hint: TextView,
        pattern: PatternView,
        extra: Button?,
        onUnlocked: () -> Unit,
        allowBiometric: Boolean
    ) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(title)
            addView(hint)
            addView(pattern, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 760))
        }
        if (allowBiometric && biometricReady()) {
            layout.addView(Button(activity).apply {
                text = "Use fingerprint"
                setOnClickListener { offerBiometric(hint, onUnlocked) }
            })
        }
        extra?.let { layout.addView(it) }
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(layout)
            setCancelable(false)
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            show()
        }
    }

    private fun biometricReady(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return activity.packageManager.hasSystemFeature("android.hardware.fingerprint")
    }

    private fun offerBiometric(hint: TextView, onUnlocked: () -> Unit) {
        if (Build.VERSION.SDK_INT < 29 || !biometricReady()) return
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Unlock")
            .setNegativeButton("Use pattern", activity.mainExecutor) { _, _ -> }
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    dismiss()
                    onUnlocked()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence?) {
                    hint.text = ""
                }
            }
        )
    }

    private fun save(secret: String) {
        prefs.edit().putString("secret", secret).apply()
    }

    private fun verify(secret: String): Boolean {
        return prefs.getString("secret", null) == secret
    }

    private fun encode(drawn: List<Int>): String {
        var salt = prefs.getString("salt", null)
        if (salt == null) {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            salt = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("salt", salt).apply()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray())
        digest.update(drawn.joinToString("-").toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("unused")
    private fun pinField(): EditText {
        return EditText(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
    }
}
