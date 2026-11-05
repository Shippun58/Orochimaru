package ani.sanin.home.status

import android.os.Bundle
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import ani.sanin.R
import ani.sanin.connections.anilist.api.Activity
import ani.sanin.databinding.ActivityStatusBinding
import ani.sanin.home.status.listener.StoriesCallback
import ani.sanin.initActivity
import ani.sanin.navBarHeight
import ani.sanin.profile.User
import ani.sanin.settings.saving.PrefManager
import ani.sanin.settings.saving.PrefName
import ani.sanin.statusBarHeight
import ani.sanin.themes.ThemeManager
import ani.sanin.util.Logger
import ani.sanin.util.FocusEffectUtil

class StatusActivity : AppCompatActivity(), StoriesCallback {
    private lateinit var activity: ArrayList<User>
    private lateinit var binding: ActivityStatusBinding
    private var position: Int = -1
    private lateinit var slideInLeft: Animation
    private lateinit var slideOutRight: Animation
    private lateinit var slideOutLeft: Animation
    private lateinit var slideInRight: Animation
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FocusEffectUtil.applyFocusListener(binding.root)
        activity = user
        position = intent.getIntExtra("position", -1)
        binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }
        slideInLeft = AnimationUtils.loadAnimation(this, R.anim.slide_in_left)
        slideOutRight = AnimationUtils.loadAnimation(this, R.anim.slide_out_right)
        slideOutLeft = AnimationUtils.loadAnimation(this, R.anim.slide_out_left)
        slideInRight = AnimationUtils.loadAnimation(this, R.anim.slide_in_right)

        val key = "activities"
        val watchedActivity = PrefManager.getCustomVal<Set<Int>>(key, setOf())
        if (activity.getOrNull(position) != null) {
            val startFrom = findFirstNonMatch(watchedActivity, activity[position].activity)
            val startIndex = if (startFrom > 0) startFrom else 0
            binding.stories.setStoriesList(
                activityList = activity[position].activity,
                startIndex = startIndex + 1
            )
        } else {
            Logger.log("index out of bounds for position $position of size ${activity.size}")
            finish()
        }

    }

    private fun findFirstNonMatch(watchedActivity: Set<Int>, activity: List<Activity>): Int {
        for (activityItem in activity) {
            if (activityItem.id !in watchedActivity) {
                return activity.indexOf(activityItem)
            }
        }
        return -1
    }

    override fun onPause() {
        super.onPause()
        binding.stories.pause()
    }

    override fun onResume() {
        super.onResume()
        if (hasWindowFocus())
            binding.stories.resume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            binding.stories.resume()
        } else {
            binding.stories.pause()
        }
    }

    override fun onStoriesEnd() {
        position += 1
        if (position < activity.size) {
            val key = "activities"
            val watchedActivity = PrefManager.getCustomVal<Set<Int>>(key, setOf())
            val startFrom = findFirstNonMatch(watchedActivity, activity[position].activity)
            val startIndex = if (startFrom > 0) startFrom else 0
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.TransitionAnimations)) {
                binding.stories.startAnimation(slideOutLeft)
            }
            binding.stories.setStoriesList(activity[position].activity, startIndex + 1)
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.TransitionAnimations)) {
                binding.stories.startAnimation(slideInRight)
            }
        } else {
            finish()
        }
    }

    override fun onStoriesStart() {
        position -= 1
        if (position >= 0 && activity[position].activity.isNotEmpty()) {
            val key = "activities"
            val watchedActivity = PrefManager.getCustomVal<Set<Int>>(key, setOf())
            val startFrom = findFirstNonMatch(watchedActivity, activity[position].activity)
            val startIndex = if (startFrom > 0) startFrom else 0
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.TransitionAnimations)) {
                binding.stories.startAnimation(slideOutRight)
            }
            binding.stories.setStoriesList(activity[position].activity, startIndex + 1)
            if (PrefManager.getVal<Boolean>(PrefName.AnimationsEnabled) && PrefManager.getVal<Boolean>(PrefName.TransitionAnimations)) {
                binding.stories.startAnimation(slideInLeft)
            }
        } else {
            finish()
        }
    }

    companion object {
        var user: ArrayList<User> = arrayListOf()
    }
}