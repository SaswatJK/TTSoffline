package com.example.ttsoffline

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.microsoft.cognitiveservices.speech.*
import kotlinx.coroutines.*

class LoginActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var regionInput: EditText
    private lateinit var loginButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if already logged in
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("SPEECH_KEY", null)
        val savedRegion = prefs.getString("SERVICE_REGION", null)

        if (!savedKey.isNullOrEmpty() && !savedRegion.isNullOrEmpty()) {
            // Already logged in, go to main activity
            startMainActivity()
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        regionInput = findViewById(R.id.regionInput)
        loginButton = findViewById(R.id.loginButton)

        loginButton.setOnClickListener {
            val apiKey = apiKeyInput.text.toString().trim()
            val region = regionInput.text.toString().trim()

            if (apiKey.isEmpty() || region.isEmpty()) {
                Toast.makeText(this, "Please enter both API Key and Region", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Disable button and show loading
            loginButton.isEnabled = false
            loginButton.text = "Verifying..."

            // Test the credentials
            testAzureCredentials(apiKey, region, prefs)
        }
    }

    private fun testAzureCredentials(apiKey: String, region: String, prefs: android.content.SharedPreferences) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val speechConfig = SpeechConfig.fromSubscription(apiKey, region)
                speechConfig.setSpeechSynthesisVoiceName("en-US-AndrewNeural")

                // Try a very short synthesis to test credentials
                val synthesizer = SpeechSynthesizer(speechConfig)
                val result = synthesizer.SpeakText("Test")

                synthesizer.close()
                speechConfig.close()

                withContext(Dispatchers.Main) {
                    when (result.reason) {
                        ResultReason.SynthesizingAudioCompleted -> {
                            // Success! Save credentials
                            prefs.edit().apply {
                                putString("SPEECH_KEY", apiKey)
                                putString("SERVICE_REGION", region)
                                apply()
                            }
                            Toast.makeText(this@LoginActivity, "Login successful!", Toast.LENGTH_SHORT).show()
                            startMainActivity()
                        }
                        ResultReason.Canceled -> {
                            val cancellation = SpeechSynthesisCancellationDetails.fromResult(result)
                            loginButton.isEnabled = true
                            loginButton.text = "Login"

                            val errorMsg = when {
                                cancellation.errorDetails.contains("401") ||
                                        cancellation.errorDetails.contains("Unauthorized") -> "Invalid API Key"
                                cancellation.errorDetails.contains("region") -> "Invalid Region"
                                else -> "Invalid credentials: ${cancellation.errorDetails}"
                            }

                            Toast.makeText(this@LoginActivity, errorMsg, Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            loginButton.isEnabled = true
                            loginButton.text = "Login"
                            Toast.makeText(this@LoginActivity, "Login failed. Please try again.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loginButton.isEnabled = true
                    loginButton.text = "Login"
                    Toast.makeText(this@LoginActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}