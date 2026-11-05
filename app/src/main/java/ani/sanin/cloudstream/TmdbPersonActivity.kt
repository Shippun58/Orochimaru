package ani.sanin.cloudstream

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbMedia
import ani.sanin.connections.tmdb.TmdbPerson
import ani.sanin.connections.tmdb.TmdbPersonImage
import ani.sanin.databinding.ActivityTmdbPersonBinding
import ani.sanin.databinding.ItemTmdbCardBinding
import ani.sanin.databinding.ItemTmdbPersonPhotoBinding
import ani.sanin.loadImage
import ani.sanin.themes.ThemeManager
import ani.sanin.util.FocusEffectUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class TmdbPersonActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTmdbPersonBinding
    private var personId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityTmdbPersonBinding.inflate(layoutInflater)
        setContentView(binding.root)

        personId = intent.getIntExtra(ARG_PERSON_ID, -1)
        if (personId <= 0) {
            finish()
            return
        }

        FocusEffectUtil.applyFocusListener(binding.tmdbPersonBack)
        binding.tmdbPersonBack.setOnClickListener { finish() }

        load()
    }

    private fun load() {
        binding.tmdbPersonProgress.isVisible = true
        lifecycleScope.launch {

            val person = withContext(Dispatchers.IO) { Tmdb.person(personId) }
            if (person == null) {
                finish()
                return@launch
            }
            val (credits, photos) = withContext(Dispatchers.IO) {
                Tmdb.personCredits(personId) to Tmdb.personProfiles(personId)
            }
            withContext(Dispatchers.Main) {
                binding.tmdbPersonProgress.isVisible = false
                bindPerson(person, credits, photos)
            }
        }
    }

    private fun bindPerson(
        person: TmdbPerson,
        credits: List<TmdbMedia>,
        photos: List<TmdbPersonImage>
    ) {
        binding.tmdbPersonName.text = person.name
        binding.tmdbPersonProfile.loadImage(Tmdb.imageUrl(person.profilePath, 342))

        person.knownForDepartment?.takeIf { it.isNotBlank() }?.let {
            binding.tmdbPersonDepartment.text = it
            binding.tmdbPersonDepartment.isVisible = true
        }
        person.birthday?.takeIf { it.isNotBlank() }?.let {
            binding.tmdbPersonBorn.text =
                getString(R.string.tmdb_person_born, prettyDate(it))
            binding.tmdbPersonBorn.isVisible = true
        }
        person.deathday?.takeIf { it.isNotBlank() }?.let {
            binding.tmdbPersonDied.text = getString(R.string.tmdb_person_died, prettyDate(it))
            binding.tmdbPersonDied.isVisible = true
        }
        person.placeOfBirth?.takeIf { it.isNotBlank() }?.let {
            binding.tmdbPersonBirthplace.text = it
            binding.tmdbPersonBirthplace.isVisible = true
        }
        person.biography?.takeIf { it.isNotBlank() }?.let {
            binding.tmdbPersonBiography.text = it
            binding.tmdbPersonBiography.isVisible = true
        }

        if (photos.isNotEmpty()) {
            binding.tmdbPersonPhotosTitle.isVisible = true
            binding.tmdbPersonPhotosRecycler.isVisible = true
            binding.tmdbPersonPhotosRecycler.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.tmdbPersonPhotosRecycler.adapter = PhotoAdapter(photos)
        }

        if (credits.isNotEmpty()) {
            binding.tmdbPersonKnownForTitle.isVisible = true
            binding.tmdbPersonKnownForRecycler.isVisible = true
            binding.tmdbPersonKnownForRecycler.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.tmdbPersonKnownForRecycler.adapter =
                KnownForAdapter(credits) { media -> openTitle(media) }
        }
    }

    private fun openTitle(media: TmdbMedia) {
        startActivity(
            Intent(this, TmdbDetailsActivity::class.java)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_TYPE, media.type)
                .putExtra(TmdbDetailsActivity.ARG_MEDIA_ID, media.id)
        )
    }

    private fun prettyDate(iso: String): String = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
            ?: return@runCatching iso
        SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(parsed)
    }.getOrDefault(iso)

    private class PhotoAdapter(
        private val items: List<TmdbPersonImage>
    ) : RecyclerView.Adapter<PhotoAdapter.VH>() {
        class VH(val b: ItemTmdbPersonPhotoBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(p: ViewGroup, v: Int): VH =
            VH(ItemTmdbPersonPhotoBinding.inflate(LayoutInflater.from(p.context), p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, i: Int) {
            h.b.tmdbPersonPhoto.loadImage(Tmdb.imageUrl(items[i].filePath, 342))
            FocusEffectUtil.applyFocusListener(h.b.tmdbPersonPhoto)
        }
    }

    private class KnownForAdapter(
        private val items: List<TmdbMedia>,
        private val onClick: (TmdbMedia) -> Unit
    ) : RecyclerView.Adapter<KnownForAdapter.VH>() {
        class VH(val b: ItemTmdbCardBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(p: ViewGroup, v: Int): VH =
            VH(ItemTmdbCardBinding.inflate(LayoutInflater.from(p.context), p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, i: Int) {
            val m = items[i]
            TmdbCards.applyCardStyle(h.b, m)
            h.b.root.contentDescription = m.displayTitle
            h.b.root.setOnClickListener { onClick(m) }
            FocusEffectUtil.applyFocusListener(h.b.root)
        }
    }

    companion object {
        const val ARG_PERSON_ID = "person_id"
    }
}
