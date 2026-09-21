package com.surendramaran.Jeepqs.activities

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

import com.surendramaran.Jeepqs.MainActivity
import com.surendramaran.Jeepqs.R
import com.surendramaran.Jeepqs.settings.DeviceConfig


class SetupActivity : AppCompatActivity() {

    private lateinit var rgDevice: RadioGroup
    private lateinit var rbDevice1: RadioButton
    private lateinit var rbDevice2: RadioButton

    private lateinit var rgRole: RadioGroup
    private lateinit var rbPrimary: RadioButton
    private lateinit var rbSecondary: RadioButton

    private lateinit var rgDoor: RadioGroup
    private lateinit var rbFront: RadioButton
    private lateinit var rbRear: RadioButton

    private lateinit var etJeepId: EditText

    private lateinit var tvDeviceDescription: TextView
    private lateinit var tvRoleDescription: TextView
    private lateinit var tvDoorDescription: TextView

    private lateinit var btnSave: Button
    private lateinit var btnReset: Button


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ============================================================
        // SYSTEM STATUS BAR
        // ============================================================

        window.statusBarColor =
            ContextCompat.getColor(
                this,
                R.color.ocean_primary_dark
            )

        // ============================================================
        // DEVICE CONFIG
        // ============================================================

        DeviceConfig.init(this)

        /*
         * If the device has already been configured,
         * do not show SetupActivity again.
         *
         * This happens when:
         * - The user closes the app
         * - Android removes the app from recent apps
         * - The user opens the app again
         *
         * Setup will only appear again after the configuration
         * has been explicitly reset/cleared.
         */
        if (DeviceConfig.isConfigured()) {

            openMainActivity()

            return
        }

        // ============================================================
        // SETUP UI
        // ============================================================

        setContentView(R.layout.activity_setup)

        initializeViews()

        // ============================================================
        // RADIO BUTTON COLOR
        // ============================================================

        val oceanPrimary =
            ContextCompat.getColor(
                this,
                R.color.ocean_primary
            )

        val textMuted =
            ContextCompat.getColor(
                this,
                R.color.text_muted
            )

        val radioTint =
            ColorStateList(
                arrayOf(
                    intArrayOf(
                        android.R.attr.state_checked
                    ),
                    intArrayOf(
                        -android.R.attr.state_checked
                    )
                ),
                intArrayOf(
                    oceanPrimary,
                    textMuted
                )
            )

        rbDevice1.buttonTintList = radioTint
        rbDevice2.buttonTintList = radioTint

        rbPrimary.buttonTintList = radioTint
        rbSecondary.buttonTintList = radioTint

        rbFront.buttonTintList = radioTint
        rbRear.buttonTintList = radioTint

        // ============================================================
        // LOAD / LISTENERS
        // ============================================================

        loadExistingConfiguration()

        setupListeners()
    }


    // ============================================================
    // OPEN MAIN ACTIVITY
    // ============================================================

    private fun openMainActivity() {

        val intent =
            Intent(
                this,
                MainActivity::class.java
            )

        /*
         * Remove SetupActivity from the task.
         *
         * This prevents the user from pressing Back and
         * returning to the setup screen.
         */
        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK

        startActivity(intent)

        finish()
    }


    // ============================================================
    // INITIALIZE VIEWS
    // ============================================================

    private fun initializeViews() {

        rgDevice =
            findViewById(R.id.rgDevice)

        rbDevice1 =
            findViewById(R.id.rbDevice1)

        rbDevice2 =
            findViewById(R.id.rbDevice2)


        rgRole =
            findViewById(R.id.rgRole)

        rbPrimary =
            findViewById(R.id.rbPrimary)

        rbSecondary =
            findViewById(R.id.rbSecondary)


        rgDoor =
            findViewById(R.id.rgDoor)

        rbFront =
            findViewById(R.id.rbFront)

        rbRear =
            findViewById(R.id.rbRear)


        etJeepId =
            findViewById(R.id.etJeepId)


        tvDeviceDescription =
            findViewById(R.id.tvDeviceDescription)

        tvRoleDescription =
            findViewById(R.id.tvRoleDescription)

        tvDoorDescription =
            findViewById(R.id.tvDoorDescription)


        btnSave =
            findViewById(R.id.btnSave)

        btnReset =
            findViewById(R.id.btnReset)
    }


    // ============================================================
    // LOAD EXISTING CONFIGURATION
    // ============================================================

    private fun loadExistingConfiguration() {

        // --------------------------------------------------------
        // DEVICE
        // --------------------------------------------------------

        when (DeviceConfig.getDeviceNumber()) {

            2 -> {

                rbDevice2.isChecked = true

                updateDeviceDescription(2)
            }

            else -> {

                rbDevice1.isChecked = true

                updateDeviceDescription(1)
            }
        }


        // --------------------------------------------------------
        // ROLE
        // --------------------------------------------------------

        when (DeviceConfig.getRole()) {

            "PRIMARY" -> {

                rbPrimary.isChecked = true
            }

            "SECONDARY" -> {

                rbSecondary.isChecked = true
            }

            else -> {

                rbPrimary.isChecked = true
            }
        }


        // --------------------------------------------------------
        // DOOR
        // --------------------------------------------------------

        when (DeviceConfig.getDoor()) {

            "REAR" -> {

                rbRear.isChecked = true
            }

            else -> {

                rbFront.isChecked = true
            }
        }


        // --------------------------------------------------------
        // JEEP ID
        // --------------------------------------------------------

        DeviceConfig.getJeepId()?.let { jeepId ->

            etJeepId.setText(jeepId)
        }


        updateRoleDescription()

        updateDoorDescription()
    }


    // ============================================================
    // LISTENERS
    // ============================================================

    private fun setupListeners() {

        // --------------------------------------------------------
        // DEVICE
        // --------------------------------------------------------

        rgDevice.setOnCheckedChangeListener { _, checkedId ->

            when (checkedId) {

                R.id.rbDevice1 -> {

                    updateDeviceDescription(1)
                }

                R.id.rbDevice2 -> {

                    updateDeviceDescription(2)
                }
            }
        }


        // --------------------------------------------------------
        // ROLE
        // --------------------------------------------------------

        rgRole.setOnCheckedChangeListener { _, checkedId ->

            when (checkedId) {

                R.id.rbPrimary -> {

                    updateRoleDescription()
                }

                R.id.rbSecondary -> {

                    updateRoleDescription()
                }
            }
        }


        // --------------------------------------------------------
        // DOOR
        // --------------------------------------------------------

        rgDoor.setOnCheckedChangeListener { _, checkedId ->

            when (checkedId) {

                R.id.rbFront -> {

                    updateDoorDescription()
                }

                R.id.rbRear -> {

                    updateDoorDescription()
                }
            }
        }


        // --------------------------------------------------------
        // SAVE
        // --------------------------------------------------------

        btnSave.setOnClickListener {

            saveConfiguration()
        }


        // --------------------------------------------------------
        // RESET
        // --------------------------------------------------------

        btnReset.setOnClickListener {

            resetConfiguration()
        }
    }


    // ============================================================
    // DEVICE DESCRIPTION
    // ============================================================

    private fun updateDeviceDescription(
        deviceNumber: Int
    ) {

        val sender =
            DeviceConfig.getHttpSmsSenderNumber(
                deviceNumber
            )

        tvDeviceDescription.text =
            if (deviceNumber == 1) {

                "Device 1 • SMS Sender: $sender"

            } else {

                "Device 2 • SMS Sender: $sender"
            }
    }


    // ============================================================
    // ROLE DESCRIPTION
    // ============================================================

    private fun updateRoleDescription() {

        tvRoleDescription.text =
            if (rbPrimary.isChecked) {

                "PRIMARY: Main jeepney monitoring and dispatch device"

            } else {

                "SECONDARY: Passenger counting / supporting device"
            }
    }


    // ============================================================
    // DOOR DESCRIPTION
    // ============================================================

    private fun updateDoorDescription() {

        tvDoorDescription.text =
            if (rbFront.isChecked) {

                "FRONT: Main entrance / passenger boarding door"

            } else {

                "REAR: Back entrance / passenger boarding door"
            }
    }


    // ============================================================
    // SAVE CONFIGURATION
    // ============================================================

    private fun saveConfiguration() {

        // --------------------------------------------------------
        // DEVICE
        // --------------------------------------------------------

        val deviceNumber =
            if (rbDevice2.isChecked) {
                2
            } else {
                1
            }


        // --------------------------------------------------------
        // ROLE
        // --------------------------------------------------------

        val role =
            if (rbPrimary.isChecked) {
                "PRIMARY"
            } else {
                "SECONDARY"
            }


        // --------------------------------------------------------
        // DOOR
        // --------------------------------------------------------

        val door =
            if (rbFront.isChecked) {
                "FRONT"
            } else {
                "REAR"
            }


        // --------------------------------------------------------
        // JEEP ID
        // --------------------------------------------------------

        val jeepId =
            etJeepId.text
                .toString()
                .trim()


        // --------------------------------------------------------
        // VALIDATE JEEP ID
        // --------------------------------------------------------

        if (jeepId.isEmpty()) {

            etJeepId.error =
                "Jeepney ID is required"

            etJeepId.requestFocus()

            Toast.makeText(
                this,
                "Please enter the Jeepney ID.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }


        // --------------------------------------------------------
        // UUID VALIDATION
        // --------------------------------------------------------

        if (!DeviceConfig.isValidUUID(jeepId)) {

            etJeepId.error =
                "Enter a valid UUID"

            etJeepId.requestFocus()

            Toast.makeText(
                this,
                "The Jeepney ID must be a valid UUID.",
                Toast.LENGTH_LONG
            ).show()

            return
        }


        // --------------------------------------------------------
        // GET AUTOMATIC SMS SENDER
        // --------------------------------------------------------

        val smsSender =
            DeviceConfig.getHttpSmsSenderNumber(
                deviceNumber
            )


        // --------------------------------------------------------
        // SAVE
        // --------------------------------------------------------

        DeviceConfig.saveConfig(
            role = role,
            door = door,
            jeepId = jeepId,
            deviceNumber = deviceNumber
        )


        // --------------------------------------------------------
        // VERIFY
        // --------------------------------------------------------

        val savedDevice =
            DeviceConfig.getDeviceNumber()

        val savedRole =
            DeviceConfig.getRole()

        val savedDoor =
            DeviceConfig.getDoor()

        val savedJeepId =
            DeviceConfig.getJeepId()


        if (
            savedDevice != deviceNumber ||
            savedRole != role ||
            savedDoor != door ||
            savedJeepId != jeepId
        ) {

            Toast.makeText(
                this,
                "Failed to verify configuration.",
                Toast.LENGTH_LONG
            ).show()

            return
        }


        // --------------------------------------------------------
        // SUCCESS
        // --------------------------------------------------------

        Toast.makeText(
            this,
            "Device $deviceNumber configured\nSMS: $smsSender",
            Toast.LENGTH_LONG
        ).show()


        android.os.Handler(
            android.os.Looper.getMainLooper()
        ).postDelayed({

            openMainActivity()

        }, 500)
    }


    // ============================================================
    // RESET
    // ============================================================

    private fun resetConfiguration() {

        DeviceConfig.clearConfig()

        rbDevice1.isChecked = true

        rbPrimary.isChecked = true

        rbFront.isChecked = true

        etJeepId.text.clear()

        updateDeviceDescription(1)

        updateRoleDescription()

        updateDoorDescription()

        Toast.makeText(
            this,
            "Configuration reset.",
            Toast.LENGTH_SHORT
        ).show()
    }
}