package br.com.assistentefinanceiro.notifications

import android.content.Context

class BankPackagePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("bank_packages", Context.MODE_PRIVATE)

    fun allowedPackages(): Set<String> = preferences.getStringSet("allowed", emptySet())?.toSet().orEmpty()

    fun allow(packageName: String) {
        val updated = allowedPackages().toMutableSet().apply { add(packageName.trim()) }
        preferences.edit().putStringSet("allowed", updated).apply()
    }

    fun remove(packageName: String) {
        val updated = allowedPackages().toMutableSet().apply { remove(packageName) }
        preferences.edit().putStringSet("allowed", updated).apply()
    }
}

