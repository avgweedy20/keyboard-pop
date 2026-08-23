package com.autofocus.telegram

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.autofocus.telegram.databinding.ActivityMainBinding
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        val TELEGRAM_PACKAGES = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun setupListeners() {
        binding.switchService.setOnCheckedChangeListener { _, isChecked ->
            val serviceEnabled = isAccessibilityServiceEnabled(this, TelegramFocusAccessibilityService::class.java)
            if (isChecked && !serviceEnabled) {
                // Reset switch silently until enabled in settings
                binding.switchService.isChecked = false
                showDisclosureDialog()
            } else if (!isChecked && serviceEnabled) {
                // Deep link to accessibility settings so user can disable it
                openAccessibilitySettings()
            }
        }

        binding.btnOpenSettings.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    private fun updateUiState() {
        val serviceEnabled = isAccessibilityServiceEnabled(this, TelegramFocusAccessibilityService::class.java)

        // Temporarily remove listener to avoid recursion when updating state programmatically
        binding.switchService.setOnCheckedChangeListener(null)
        binding.switchService.isChecked = serviceEnabled
        setupListeners()

        binding.tvServiceStatus.text = if (serviceEnabled) {
            getString(R.string.service_status_enabled)
        } else {
            getString(R.string.service_status_disabled)
        }

        checkTelegramInstalled()
    }

    private fun checkTelegramInstalled() {
        var installedPackageName: String? = null
        for (pkg in TELEGRAM_PACKAGES) {
            if (isPackageInstalled(pkg)) {
                installedPackageName = pkg
                break
            }
        }

        if (installedPackageName != null) {
            binding.tvTelegramStatus.text = getString(R.string.telegram_status_installed)
            binding.tvTelegramStatus.setTextColor(getColor(android.R.color.tab_indicator_text))
        } else {
            binding.tvTelegramStatus.text = getString(R.string.telegram_status_not_installed)
            binding.tvTelegramStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun showDisclosureDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_message)
            .setPositiveButton(R.string.disclosure_accept) { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton(R.string.disclosure_decline, null)
            .show()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedServiceName = "${context.packageName}/${serviceClass.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedServiceName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
