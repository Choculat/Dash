package sh.margot.dash.services.koodo

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import sh.margot.dash.databinding.ActivityKoodoLoginBinding

class KoodoLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKoodoLoginBinding
    private val viewModel: KoodoLoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKoodoLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.loginButton.setOnClickListener { submit() }
        binding.passwordInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        binding.verifyButton.setOnClickListener {
            hideKeyboard()
            viewModel.submitCode(binding.codeInput.text.toString())
        }
        binding.codeInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                viewModel.submitCode(binding.codeInput.text.toString())
                true
            } else false
        }
        binding.resendButton.setOnClickListener { viewModel.sendOtp() }

        lifecycleScope.launch {
            viewModel.state.collect { state ->
                binding.messageText.text = ""
                when (state) {
                    is KoodoLoginState.Idle           -> viewModel.start()
                    is KoodoLoginState.NeedCredentials -> { showCredentials(); setLoading(false) }
                    is KoodoLoginState.Authenticating  -> setLoading(true)
                    is KoodoLoginState.NeedTwoFactor   -> {
                        binding.twoFactorPrompt.text =
                            "Code sent to ${state.phoneMasked}. Enter it below."
                        showTwoFactor()
                        setLoading(false)
                        if (binding.codeInput.text.isNullOrEmpty()) binding.codeInput.requestFocus()
                    }
                    is KoodoLoginState.VerifyingCode   -> setLoading(true)
                    is KoodoLoginState.Success          -> finish()
                    is KoodoLoginState.Error            -> {
                        setLoading(false)
                        binding.messageText.text = state.message
                    }
                }
            }
        }
    }

    private fun submit() {
        hideKeyboard()
        viewModel.login(binding.emailInput.text.toString(), binding.passwordInput.text.toString())
    }

    private fun showCredentials() {
        binding.credentialsGroup.visibility = View.VISIBLE
        binding.twoFactorGroup.visibility = View.GONE
    }

    private fun showTwoFactor() {
        binding.credentialsGroup.visibility = View.GONE
        binding.twoFactorGroup.visibility = View.VISIBLE
    }

    private fun setLoading(on: Boolean) {
        binding.progress.visibility = if (on) View.VISIBLE else View.GONE
        listOf(binding.loginButton, binding.verifyButton, binding.resendButton,
               binding.emailInput, binding.passwordInput, binding.codeInput)
            .forEach { it.isEnabled = !on }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(currentFocus?.windowToken, 0)
    }
}
