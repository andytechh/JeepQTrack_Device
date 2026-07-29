package com.surendramaran.Jeepqs.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.surendramaran.Jeepqs.MainActivity
import com.surendramaran.Jeepqs.databinding.ActivitySetupBinding
import com.surendramaran.Jeepqs.settings.DeviceConfig

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize DeviceConfig
        DeviceConfig.init(this)

        // DEBUG: Log current config
        android.util.Log.d("SetupActivity", "=".repeat(60))
        android.util.Log.d("SetupActivity", "SETUP ACTIVITY STARTED")
        android.util.Log.d("SetupActivity", DeviceConfig.getConfigSummary())
        android.util.Log.d("SetupActivity", "=".repeat(60))

        // Check if already configured
        if (DeviceConfig.isConfigured()) {
            val savedRole = DeviceConfig.getRole()
            android.util.Log.d("SetupActivity", "Already configured with role: $savedRole")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // ─── SET DEFAULT VALUES ──────────────────────────────────────────
        // Default to SECONDARY (for passenger counting devices)
        binding.rbSecondary.isChecked = true
        binding.rbFront.isChecked = true

        binding.etJeepId.hint = "Enter Jeep ID (UUID)"

        // ─── UPDATE UI ON SELECTION CHANGE ──────────────────────────────
        binding.rbPrimary.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                android.util.Log.d("SetupActivity", "Primary selected")
            }
        }

        binding.rbSecondary.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                android.util.Log.d("SetupActivity", "Secondary selected")
            }
        }

        // ─── SAVE BUTTON ──────────────────────────────────────────────────
        binding.btnSave.setOnClickListener {
            saveConfiguration()
        }

        // ─── RESET BUTTON (for debugging) ────────────────────────────────
        binding.btnReset.setOnClickListener {
            DeviceConfig.clearConfig()
            Toast.makeText(this, "Configuration reset", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveConfiguration() {
        val role = if (binding.rbPrimary.isChecked) "PRIMARY" else "SECONDARY"
        val door = if (binding.rbFront.isChecked) "FRONT" else "REAR"
        val jeepId = binding.etJeepId.text.toString().trim()

        android.util.Log.d("SetupActivity", "=".repeat(60))
        android.util.Log.d("SetupActivity", "SAVING CONFIGURATION")
        android.util.Log.d("SetupActivity", "Selected Role: $role")
        android.util.Log.d("SetupActivity", "Selected Door: $door")
        android.util.Log.d("SetupActivity", "Jeep ID: $jeepId")
        android.util.Log.d("SetupActivity", "=".repeat(60))

        // ─── VALIDATE ──────────────────────────────────────────────────────
        if (jeepId.isEmpty()) {
            Toast.makeText(this, "Please enter Jeep ID", Toast.LENGTH_SHORT).show()
            binding.etJeepId.error = "Jeep ID is required"
            return
        }

        // Validate UUID format
        if (jeepId.length > 10 && !DeviceConfig.isValidUUID(jeepId)) {
            Toast.makeText(this, "Invalid Jeep ID format. Please enter a valid UUID.", Toast.LENGTH_LONG).show()
            binding.etJeepId.error = "Invalid UUID format"
            return
        }

        // ─── SAVE ──────────────────────────────────────────────────────────
        DeviceConfig.saveConfig(role, door, jeepId)

        // ─── VERIFY ────────────────────────────────────────────────────────
        val savedRole = DeviceConfig.getRole()
        val savedDoor = DeviceConfig.getDoor()
        val savedJeepId = DeviceConfig.getJeepId()

        android.util.Log.d("SetupActivity", "=".repeat(60))
        android.util.Log.d("SetupActivity", "VERIFICATION")
        android.util.Log.d("SetupActivity", "Saved Role: $savedRole (expected: $role)")
        android.util.Log.d("SetupActivity", "Saved Door: $savedDoor (expected: $door)")
        android.util.Log.d("SetupActivity", "Saved Jeep ID: $savedJeepId (expected: $jeepId)")
        android.util.Log.d("SetupActivity", "=".repeat(60))

        // Check if save was successful
        if (savedRole != role) {
            android.util.Log.e("SetupActivity", " ROLE MISMATCH! Saved: $savedRole, Expected: $role")
            Toast.makeText(this, "Error: Role mismatch! Please try again.", Toast.LENGTH_LONG).show()
            return
        }

        // ─── SUCCESS ──────────────────────────────────────────────────────
        Toast.makeText(
            this,
            " Configuration saved as $role",
            Toast.LENGTH_LONG
        ).show()

        android.util.Log.d("SetupActivity", "Configuration saved successfully!")

        // ─── NAVIGATE ─────────────────────────────────────────────────────
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}