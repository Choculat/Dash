package sh.margot.dash.services

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

/** One connectable service rendered as a single card on the Dash home screen. */
interface ServiceCard {
    val id: String
    fun createView(inflater: LayoutInflater, parent: ViewGroup): View
    suspend fun refresh(view: View)
}
