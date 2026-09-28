package com.unshoo.pixelmusic.data.remote.youtube

import com.unshoo.pixelmusic.data.database.AlbumEntity
import com.unshoo.pixelmusic.data.database.ArtistEntity
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.database.SongArtistCrossRef
import com.unshoo.pixelmusic.data.database.SongEntity
import com.unshoo.pixelmusic.data.database.SourceType
import com.unshoo.pixelmusic.data.database.toSong
import com.unshoo.pixelmusic.data.model.Song
import com.unshoo.pixelmusic.data.preferences.UserPreferencesRepository
import com.unshoo.pixelmusic.data.stream.CloudMusicUtils
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

@Singleton
class YouTubeLibraryRepository @Inject constructor(
    private val musicDao: MusicDao,
    private val youTubeMusicClient: YouTubeMusicClient,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    suspend fun syncPublicCatalog() {
        val tracks = youTubeMusicClient.loadCatalog()
        if (tracks.isEmpty()) {
            Timber.tag(TAG).w("YouTube Music catalog was empty; leaving the saved songs in place")
            return
        }

        val batch = buildBatch(tracks, indexDates = true)
        val currentIds = batch.songs.map { it.id }.toSet()
        val removedIds = musicDao.getReplaceableYoutubeSongIds().filter { it !in currentIds }
        upsert(batch, deletedSongIds = removedIds)
        userPreferencesRepository.setYoutubeCatalogRevision(CATALOG_REVISION)
        Timber.tag(TAG).i(
            "YouTube Music catalog synced: ${batch.songs.size} songs, removed ${removedIds.size}"
        )
    }

    /**
     * Looks up songs on YouTube Music and stores them without removing the rest of the catalog.
     * Results stay in the order YouTube returned.
     */
    suspend fun search(query: String): List<Song> {
        val tracks = youTubeMusicClient.search(query)
        if (tracks.isEmpty()) return emptyList()
        val batch = buildBatch(tracks, indexDates = false)
        upsert(batch, deletedSongIds = emptyList())
        val saved = musicDao.getSongsByIdsListSimple(batch.songs.map { it.id }).associateBy { it.id }
        return batch.songs.mapNotNull { entity -> saved[entity.id]?.toSong() }
    }

    suspend fun resolveAudioUrl(videoId: String): String? = youTubeMusicClient.resolveAudioUrl(videoId)

    private suspend fun upsert(batch: LibraryBatch, deletedSongIds: List<Long>) {
        musicDao.incrementalSyncMusicData(
            songs = batch.songs,
            albums = batch.albums.values.toList(),
            artists = batch.artists.values.toList(),
            crossRefs = batch.crossRefs,
            deletedSongIds = deletedSongIds
        )
    }

    private suspend fun buildBatch(tracks: List<YouTubeTrack>, indexDates: Boolean): LibraryBatch {
        val existing = musicDao.getSongsByIdsListSimple(tracks.map { songId(it.videoId) }).associateBy { it.id }
        val songs = ArrayList<SongEntity>(tracks.size)
        val artists = LinkedHashMap<Long, ArtistEntity>()
        val albums = LinkedHashMap<Long, AlbumEntity>()
        val crossRefs = ArrayList<SongArtistCrossRef>(tracks.size)

        tracks.forEachIndexed { index, track ->
            val songId = songId(track.videoId)
            val previous = existing[songId]
            val artistNames = CloudMusicUtils.parseArtistNames(
                if (previous?.artistUserEdited == true) previous.artistName else track.artist
            )
            val primaryArtist = artistNames.first()
            val primaryArtistId = artistId(primaryArtist)
            artistNames.forEachIndexed { artistIndex, name ->
                val id = artistId(name)
                artists.putIfAbsent(
                    id,
                    ArtistEntity(id = id, name = name, trackCount = 0, imageUrl = track.thumbnailUrl)
                )
                crossRefs.add(
                    SongArtistCrossRef(
                        songId = songId,
                        artistId = id,
                        isPrimary = artistIndex == 0
                    )
                )
            }
            val albumName = if (previous?.albumUserEdited == true) {
                previous.albumName
            } else {
                track.album.ifBlank { "YouTube Music" }
            }
            val albumKey = albumId(primaryArtist, albumName)
            albums.putIfAbsent(
                albumKey,
                AlbumEntity(
                    id = albumKey,
                    title = albumName,
                    artistName = primaryArtist,
                    artistId = primaryArtistId,
                    albumArtUriString = track.thumbnailUrl,
                    songCount = 0,
                    dateAdded = CATALOG_DATE_ADDED,
                    year = 0,
                    albumArtist = primaryArtist
                )
            )
            songs.add(
                SongEntity(
                    id = songId,
                    title = if (previous?.titleUserEdited == true) previous.title else track.title,
                    artistName = if (previous?.artistUserEdited == true) {
                        previous.artistName
                    } else {
                        track.artist.ifBlank { primaryArtist }
                    },
                    artistId = primaryArtistId,
                    albumArtist = primaryArtist,
                    albumArtistId = primaryArtistId,
                    albumName = albumName,
                    albumId = albumKey,
                    contentUriString = "youtube://${track.videoId}",
                    albumArtUriString = track.thumbnailUrl ?: previous?.albumArtUriString,
                    duration = track.durationMs.takeIf { it > 0L } ?: previous?.duration ?: 0L,
                    genre = if (previous?.genreUserEdited == true) previous.genre else "YouTube Music",
                    filePath = "youtube://${track.videoId}",
                    parentDirectoryPath = YOUTUBE_FOLDER,
                    isFavorite = previous?.isFavorite ?: false,
                    lyrics = previous?.lyrics,
                    trackNumber = previous?.trackNumber ?: 0,
                    dateAdded = previous?.dateAdded ?: if (indexDates) {
                        CATALOG_DATE_ADDED - index
                    } else {
                        CATALOG_DATE_ADDED
                    },
                    mimeType = previous?.mimeType,
                    sourceType = SourceType.YOUTUBE,
                    artistsJson = previous?.artistsJson,
                    titleUserEdited = previous?.titleUserEdited ?: false,
                    artistUserEdited = previous?.artistUserEdited ?: false,
                    albumUserEdited = previous?.albumUserEdited ?: false,
                    genreUserEdited = previous?.genreUserEdited ?: false
                )
            )
        }
        return LibraryBatch(songs, albums, artists, crossRefs)
    }

    private data class LibraryBatch(
        val songs: List<SongEntity>,
        val albums: Map<Long, AlbumEntity>,
        val artists: Map<Long, ArtistEntity>,
        val crossRefs: List<SongArtistCrossRef>
    )

    private fun songId(videoId: String): Long =
        -(SONG_ID_OFFSET + videoId.hashCode().toLong().absoluteValue)

    private fun albumId(artist: String, album: String): Long =
        -(ALBUM_ID_OFFSET + "$artist\u0000$album".lowercase().hashCode().toLong().absoluteValue)

    private fun artistId(name: String): Long =
        -(ARTIST_ID_OFFSET + name.lowercase().hashCode().toLong().absoluteValue)

    companion object {
        const val CATALOG_REVISION = 1
        private const val TAG = "YouTubeLibrary"
        private const val YOUTUBE_FOLDER = "YouTube Music"
        private const val SONG_ID_OFFSET = 15_000_000_000_000L
        private const val ALBUM_ID_OFFSET = 16_000_000_000_000L
        private const val ARTIST_ID_OFFSET = 17_000_000_000_000L
        private const val CATALOG_DATE_ADDED = 1_577_836_800_000L
    }
}
