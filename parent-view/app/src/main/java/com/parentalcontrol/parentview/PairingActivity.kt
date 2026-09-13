package com.parentalcontrol.parentview

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

/**
 * Shown when the parent has NO paired devices yet (fresh install or all removed).
 * Adds the entered key to the set of paired keys and goes to MainActivity.
 *
 * Keys are stored as a JSON array in SharedPreferences so multiple kid devices
 * (each with their own unique key) can be monitored simultaneously.
 */
class PairingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If we already have at least one key, skip straight to MainActivity
        if (getSavedKeys().isNotEmpty()) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_pairing)
        supportActionBar?.hide()

        val etKey      = findViewById<EditText>(R.id.etPairingKey)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val tvError    = findViewById<TextView>(R.id.tvError)

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
                    addKey(key)
                }
            }
        }
    }

    private fun addKey(key: String) {
        val keys = getSavedKeys().toMutableSet()
        keys.add(key)
        saveKeys(keys)
        Toast.makeText(this, "✅ Device added! Loading...", Toast.LENGTH_SHORT).show()
        goToMain()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    companion object {
        const val PREFS_NAME       = "parent_prefs"
        const val KEY_PAIRING_KEYS = "pairing_keys_json"   // JSON array of keys

        /** Returns the set of all saved pairing keys */
        fun getSavedKeys(prefs: android.content.SharedPreferences): Set<String> {
            val json = prefs.getString(KEY_PAIRING_KEYS, null) ?: return emptySet()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
        }

        /** Persists the set of pairing keys */
        fun saveKeys(prefs: android.content.SharedPreferences, keys: Set<String>) {
            val arr = JSONArray(keys.toList())
            prefs.edit().putString(KEY_PAIRING_KEYS, arr.toString()).apply()
        }
    }

    // Instance helpers that use this activity's own prefs
    private fun getSavedKeys(): Set<String> =
        getSavedKeys(getSharedPreferences(PREFS_NAME, MODE_PRIVATE))

    private fun saveKeys(keys: Set<String>) =
        saveKeys(getSharedPreferences(PREFS_NAME, MODE_PRIVATE), keys)
}
