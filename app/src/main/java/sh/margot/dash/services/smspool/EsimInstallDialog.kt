package sh.margot.dash.services.smspool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import sh.margot.dash.databinding.DialogEsimInstallBinding

/**
 * "Add to this phone" fires exactly what the camera fires when it scans an eSIM QR code:
 * ACTION_VIEW with the raw "LPA:1$smdp$token" activation string as the URI. Android routes that
 * to the device's privileged LPA (e.g. com.google.android.euicc), which performs the install
 * itself — so it works even when another eSIM is already active, without the app needing the
 * WRITE_EMBEDDED_SUBSCRIPTIONS permission (which no third-party app can hold). Verified against a
 * real device: GoogleCamera launches `ACTION_VIEW dat=LPA: cmp=com.google.android.euicc/…
 * QrDownloadActivity`, and the same intent from here resolves to that same activity.
 *
 * If no LPA handler exists (non-standard device), falls back to the QR code + manual SM-DP+/code
 * details already shown in the dialog.
 */
object EsimInstallDialog {

    fun show(activity: AppCompatActivity, profile: SmsPoolApiClient.EsimProfile) {
        val binding = DialogEsimInstallBinding.inflate(activity.layoutInflater)

        val remainingMb = parseMb(profile.remainingData)
        val totalMb = parseMb(profile.totalData)
        binding.remainingText.text = "${profile.remainingData.ifBlank { "—" }} of ${profile.totalData.ifBlank { "—" }} remaining"
        binding.dataProgress.progress = if (totalMb > 0) (remainingMb / totalMb * 100).toInt().coerceIn(0, 100) else 0

        binding.detailsText.text = buildString {
            append("SM-DP+ address:\n${profile.smdp}\n\n")
            append("Activation code:\n${profile.activationCode}\n\n")
            if (profile.pin.isNotBlank()) append("PIN: ${profile.pin}\n")
            if (profile.puk.isNotBlank()) append("PUK: ${profile.puk}\n")
            if (profile.apn.isNotBlank()) append("APN: ${profile.apn}\n")
        }

        // Don't gate on resolveActivity(): Android 11+ package visibility hides the LPA package
        // from us so resolveActivity returns null even though startActivity to it works fine
        // (launching an implicit intent isn't blocked by visibility, only querying is). Just try
        // it and fall back to the QR if this device genuinely has no LPA handler.
        binding.qrButton.setOnClickListener { showQr(activity, profile.ac) }
        binding.installButton.setOnClickListener {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(profile.ac)))
            } catch (e: Exception) {
                Toast.makeText(activity, "No eSIM installer on this phone — scan the QR code instead", Toast.LENGTH_LONG).show()
            }
        }

        AlertDialog.Builder(activity)
            .setView(binding.root)
            .setNegativeButton("Close", null)
            .setNeutralButton("Copy code") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("eSIM activation code", profile.ac))
                Toast.makeText(activity, "Activation code copied", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showQr(activity: AppCompatActivity, data: String) {
        val sizePx = (240 * activity.resources.displayMetrics.density).toInt()
        val bitmap = try {
            renderQr(data, sizePx)
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't generate QR code: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val imageView = ImageView(activity).apply {
            setImageBitmap(bitmap)
            val pad = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(activity)
            .setTitle("Scan to add eSIM")
            .setView(imageView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun renderQr(data: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun parseMb(raw: String): Double {
        val value = raw.trim().takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0.0
        return if (raw.contains("GB", ignoreCase = true)) value * 1024 else value
    }
}
