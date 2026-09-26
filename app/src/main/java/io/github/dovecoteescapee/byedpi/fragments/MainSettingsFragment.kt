package io.github.dovecoteescapee.byedpi.fragments

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.*
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.utility.*

class MainSettingsFragment : PreferenceFragmentCompat() {
    companion object {
        private val TAG: String = MainSettingsFragment::class.java.simpleName

        fun setTheme(name: String) =
            themeByName(name)?.let {
                AppCompatDelegate.setDefaultNightMode(it)
            } ?: throw IllegalStateException("Invalid value for app_theme: $name")

        private fun themeByName(name: String): Int? = when (name) {
            "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> {
                Log.w(TAG, "Invalid value for app_theme: $name")
                null
            }
        }
    }

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updatePreferences()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.main_settings, rootKey)

        setEditTextPreferenceListener("dns_ip") {
            it.isBlank() || checkNotLocalIp(it)
        }

        findPreferenceNotNull<DropDownPreference>("app_theme")
            .setOnPreferenceChangeListener { _, newValue ->
                setTheme(newValue as String)
                true
            }

        val switchCommandLineSettings = findPreferenceNotNull<SwitchPreference>(
            "byedpi_enable_cmd_settings"
        )
        val uiSettings = findPreferenceNotNull<Preference>("byedpi_ui_settings")
        val cmdSettings = findPreferenceNotNull<Preference>("byedpi_cmd_settings")

        val setByeDpiSettingsMode = { enable: Boolean ->
            uiSettings.isEnabled = !enable
            cmdSettings.isEnabled = enable
        }

        setByeDpiSettingsMode(switchCommandLineSettings.isChecked)

        switchCommandLineSettings.setOnPreferenceChangeListener { _, newValue ->
            setByeDpiSettingsMode(newValue as Boolean)
            true
        }

        findPreferenceNotNull<Preference>("version").summary = BuildConfig.VERSION_NAME

        findPreferenceNotNull<Preference>("credits").setOnPreferenceClickListener {
            showCreditsDialog()
            true
        }
        findPreferenceNotNull<Preference>("source_code").setOnPreferenceClickListener {
            showLicensesDialog()
            true
        }

        updatePreferences()
    }

    private fun showCreditsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("О Ласточке")
            .setMessage(
                """
                Ласточка помогает открывать заблокированные сайты и сервисы в России: YouTube, Telegram и другие популярные ресурсы — без чужих серверов и без подписки.

                Всё работает на вашем телефоне: трафик не уходит на чужой VPN, банки и привычные российские приложения можно оставить вне туннеля.

                Одна кнопка на главном экране — включили и пользуетесь.
                """.trimIndent(),
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLicensesDialog() {
        val items = arrayOf(
            "ByeDPIAndroid — оболочка",
            "ByeDPI — движок обхода",
            "hev-socks5-tunnel — туннель",
            "Документация ByeDPI",
        )
        val urls = arrayOf(
            "https://github.com/dovecoteescapee/ByeDPIAndroid",
            "https://github.com/hufrea/byedpi",
            "https://github.com/heiher/hev-socks5-tunnel",
            "https://github.com/hufrea/byedpi/blob/v0.13/README.md",
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Открытые компоненты")
            .setMessage(
                "Ласточка собрана на открытом ПО. Здесь можно посмотреть исходники компонентов:",
            )
            .setItems(items) { _, which ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urls[which])))
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    private fun updatePreferences() {
        val mode = findPreferenceNotNull<ListPreference>("byedpi_mode")
            .value.let { Mode.fromString(it) }
        val dns = findPreferenceNotNull<EditTextPreference>("dns_ip")
        val ipv6 = findPreferenceNotNull<SwitchPreference>("ipv6_enable")

        when (mode) {
            Mode.VPN -> {
                dns.isVisible = true
                ipv6.isVisible = true
            }

            Mode.Proxy -> {
                dns.isVisible = false
                ipv6.isVisible = false
            }
        }
    }
}
