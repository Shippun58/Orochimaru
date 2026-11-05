package ani.sanin.cloudstream

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity

class CloudStreamSettingsActivity : AppCompatActivity() {

    private var dialogTookFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CommonActivity.setActivityInstance(this)

        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
        val source = sourceId?.let { id ->
            CsRepos.installed(this).firstOrNull { it.id == id }
        }
        if (source == null) {
            Toast.makeText(this, "No settings available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (savedInstanceState == null) {

            supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                        if (fm.fragments.isEmpty()) finish()
                    }
                },
                false,
            )

            supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                        if (f is com.google.android.material.bottomsheet.BottomSheetDialogFragment) {
                            val sheetView = f.dialog?.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
                            val sheet = sheetView?.let { com.google.android.material.bottomsheet.BottomSheetBehavior.from(it) }
                            if (sheet != null) {
                                sheet.isFitToContents = false
                                sheet.maxHeight = resources.displayMetrics.heightPixels
                                sheet.skipCollapsed = true
                                sheet.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                            }
                        }
                    }
                },
                false,
            )

            val opener = runCatching { CsRuntime.openSettingsFor(this, source) }
            if (opener.isFailure) {
                val t = opener.exceptionOrNull()!!
                Log.e(TAG, "Failed to open ${source.name} settings", t)
                Toast.makeText(this, "Couldn't open ${source.name} settings", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val invoke = opener.getOrNull()
            if (invoke == null) {

                val loadError = if (CsRuntime.isLoaded(source.id)) null else CsRuntime.lastError
                val message = if (loadError != null) {
                    "Failed to load ${source.name}: $loadError"
                } else {
                    "${source.name} has no settings"
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val shown = runCatching { invoke(this); true }.getOrElse { t ->
                Log.e(TAG, "Failed to open ${source.name} settings", t)
                Toast.makeText(this, "Couldn't open ${source.name} settings", Toast.LENGTH_SHORT).show()
                false
            }
            if (!shown) finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            dialogTookFocus = true
            return
        }
        if (dialogTookFocus && supportFragmentManager.fragments.isEmpty()) finish()
    }

    companion object {
        const val EXTRA_SOURCE_ID = "sourceId"
        private const val TAG = "CloudStreamSettings"

        fun intent(context: android.content.Context, sourceId: String): Intent =
            Intent(context, CloudStreamSettingsActivity::class.java)
                .putExtra(EXTRA_SOURCE_ID, sourceId)
    }
}
