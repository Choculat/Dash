package sh.margot.dash.services.koodo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.margot.dash.security.CredentialStore

class KoodoLoginViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<KoodoLoginState>(KoodoLoginState.Idle)
    val state: StateFlow<KoodoLoginState> = _state.asStateFlow()

    private var profile: KoodoAuthService.Profile? = null
    private var otpSecret: String? = null

    /** If credentials are already stored for this service, resume silently instead of asking again. */
    fun start() {
        val creds = CredentialStore.get(getApplication(), KoodoApiClient.SERVICE_ID)
        if (creds != null) login(creds.email, creds.password)
        else _state.value = KoodoLoginState.NeedCredentials
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _state.value = KoodoLoginState.Error("Enter your email and password")
            return
        }
        viewModelScope.launch {
            _state.value = KoodoLoginState.Authenticating
            KoodoAuthService.login(email.trim(), password)
                .onSuccess { p ->
                    profile = p
                    // Password is confirmed valid at this point, independent of any 2FA step that follows.
                    CredentialStore.save(getApplication(), KoodoApiClient.SERVICE_ID, email.trim(), password)
                    Koodo2fa.set(getApplication(), p.twoFactorFlag)
                    if (p.twoFactorFlag && !p.phoneNumber.isNullOrBlank()) sendOtp()
                    else _state.value = KoodoLoginState.Success
                }
                .onFailure { _state.value = KoodoLoginState.Error(it.message ?: "Login failed") }
        }
    }

    fun sendOtp() {
        viewModelScope.launch {
            _state.value = KoodoLoginState.Authenticating
            KoodoAuthService.generateOtp(profile ?: return@launch)
                .onSuccess { secret ->
                    otpSecret = secret
                    val masked = profile?.phoneNumber?.filter { it.isDigit() }
                        ?.takeLast(4)?.let { "•••-•••-$it" } ?: ""
                    _state.value = KoodoLoginState.NeedTwoFactor(masked)
                }
                .onFailure { _state.value = KoodoLoginState.Error(it.message ?: "Couldn't send code") }
        }
    }

    fun submitCode(code: String) {
        val secret = otpSecret ?: run {
            _state.value = KoodoLoginState.Error("No active code — tap Resend")
            return
        }
        if (code.trim().length < 4) {
            _state.value = KoodoLoginState.Error("Enter the full SMS code")
            return
        }
        viewModelScope.launch {
            _state.value = KoodoLoginState.VerifyingCode
            KoodoAuthService.validateOtp(secret, code)
                .onSuccess { valid ->
                    _state.value = if (valid) KoodoLoginState.Success
                    else KoodoLoginState.Error("Invalid or expired code")
                }
                .onFailure { _state.value = KoodoLoginState.Error(it.message ?: "Verification failed") }
        }
    }
}
