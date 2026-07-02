package sh.margot.dash.services.koodo

sealed class KoodoLoginState {
    object Idle : KoodoLoginState()
    object NeedCredentials : KoodoLoginState()
    object Authenticating : KoodoLoginState()
    data class NeedTwoFactor(val phoneMasked: String) : KoodoLoginState()
    object VerifyingCode : KoodoLoginState()
    object Success : KoodoLoginState()
    data class Error(val message: String) : KoodoLoginState()
}
