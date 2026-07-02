package sh.margot.dash

import android.app.Application
import com.google.android.material.color.DynamicColors
import sh.margot.dash.services.koodo.KoodoApiClient

class DashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        KoodoApiClient.init(this)
        // Apply Material You wallpaper-based colors on Android 12+.
        // Falls back to the Dash theme colors on older devices.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
