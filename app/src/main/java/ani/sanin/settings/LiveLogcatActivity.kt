package ani.sanin.settings

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import ani.sanin.R
import ani.sanin.databinding.ActivityLiveLogcatBinding
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import java.io.BufferedReader
import java.io.InputStreamReader

class LiveLogcatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLiveLogcatBinding
    private var logcatThread: Thread? = null
    private var running = false
    private var paused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityLiveLogcatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            liveLogcatLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            liveLogcatBack.isFocusable = true
            liveLogcatBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
            FocusEffectUtil.applyFocusListener(liveLogcatBack)

            liveLogcatText.movementMethod = ScrollingMovementMethod()
            liveLogcatText.setOnClickListener {
                if (paused) {
                    paused = false
                    liveLogcatToggle.setImageResource(R.drawable.ic_round_pause_24)
                }
            }

            liveLogcatToggle.isFocusable = true
            liveLogcatToggle.setOnClickListener {
                paused = !paused
                liveLogcatToggle.setImageResource(
                    if (paused) R.drawable.ic_round_play_arrow_24
                    else R.drawable.ic_round_pause_24
                )
            }
            FocusEffectUtil.applyFocusListener(liveLogcatToggle)

            val pid = android.os.Process.myPid()
            running = true
            logcatThread = Thread({
                try {
                    val process = Runtime.getRuntime().exec(
                        arrayOf("logcat", "-v", "time", "--pid=$pid")
                    )
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (paused) continue
                        runOnUiThread {
                            liveLogcatText.append("$line\n")
                            if (liveLogcatText.lineCount > 5000) {
                                val text = liveLogcatText.text
                                val idx = text.indexOf("\n", text.length / 2)
                                if (idx > 0) liveLogcatText.text = text.subSequence(idx + 1, text.length)
                            }
                            val scrollAmount = liveLogcatText.layout.getLineTop(liveLogcatText.lineCount - 1)
                            liveLogcatText.scrollTo(0, scrollAmount.coerceAtLeast(0))
                        }
                    }
                } catch (_: Exception) { }
            }, "LiveLogcat").apply { isDaemon = true; start() }
        }
    }

    override fun onDestroy() {
        running = false
        logcatThread?.interrupt()
        logcatThread = null
        super.onDestroy()
    }
}
