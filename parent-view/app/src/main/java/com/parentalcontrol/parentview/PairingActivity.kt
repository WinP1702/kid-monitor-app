package com.parentalcontrol.parentview

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * First-launch screen that asks the parent to enter the 8-character pairing key
 * from the Kid Monitor device. Once saved, MainActivity takes over.
 *
 * The key is stored in SharedPreferences. Parent can change it via
 * MainActivity's menu → "Change Pairing Key".
 */
class PairingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        supportActionBar?.hide()

        val etKey    = findViewById<EditText>(R.id.etPairingKey)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val tvError  = findViewById<TextView>(R.id.tvError)

        // Force uppercase, max 8 chars
        etKey.filters = arrayOf(
            InputFilter.AllCaps(),
            InputFilter.LengthFilter(8)
        )
        etKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS

        btnConnect.setOnClickListener {
            val key = etKey.text.toString().trim().uppercase()
            when {
                key.length != 8 -> {
                    tvError.text = "Key must be exactly 8 characters"
                    tvError.visibility = View.VISIBLE
                }
                !key.all { it.isLetterOrDigit() } -> {
                    tvError.text = "Key must contain only letters and numbers"
                    tvError.visibility = View.VISIBLE
                }
                else -> {
                    tvError.visibility = View.GONE
                    savePairingKey(key)
                }
            }
        }
    }

    private fun savePairingKey(key: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_PAIRING_KEY, key)
            .apply()

        Toast.makeText(this, "✅ Paired! Loading devices...", Toast.LENGTH_SHORT).show()

        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    companion object {
        const val PREFS_NAME    = "parent_prefs"
        const val KEY_PAIRING_KEY = "pairing_key"
    }
}
