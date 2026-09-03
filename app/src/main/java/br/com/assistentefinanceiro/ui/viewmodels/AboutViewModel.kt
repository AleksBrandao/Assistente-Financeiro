package br.com.assistentefinanceiro.ui.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AboutUiState(
    val versionName: String,
    val versionCode: Int,
)

internal class AboutViewModel(context: Context) : ViewModel() {
    private val packageInfo = context.applicationContext.packageManager.getPackageInfo(
        context.applicationContext.packageName,
        0,
    )
    @Suppress("DEPRECATION")
    private val initialState = AboutUiState(
        versionName = packageInfo.versionName ?: "não informada",
        versionCode = packageInfo.versionCode,
    )
    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()
}
