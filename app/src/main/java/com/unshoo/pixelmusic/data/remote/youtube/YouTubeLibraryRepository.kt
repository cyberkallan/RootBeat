package com.unshoo.pixelmusic.data.remote.youtube

import com.unshoo.pixelmusic.data.database.AlbumEntity
import com.unshoo.pixelmusic.data.database.ArtistEntity
import com.unshoo.pixelmusic.data.database.MusicDao
import com.unshoo.pixelmusic.data.database.SongArtistCrossRef
import com.unshoo.pixelmusic.data.database.SongEntity
import com.unshoo.pixelmusic.data.database.SourceType
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

        val songs = ArrayList<SongEntity>(tracks.size)
        val artists = LinkedHashMap<Long, ArtistEntity>()
        val albums = LinkedHashMap<Long, AlbumEntity>()
        val crossRefs = ArrayList<SongArtistCrossRef>(tracks.size)
        val now = System.currentTimeMillis()

        tracks.forEachIndexed { index, track ->
            val songId = songId(track.videoId)
            val artistNames = CloudMusicUtils.parseArtistNames(track.artist)
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
            val albumName = track.album.ifBlank { "YouTube Music" }
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
                    title = track.title,
                    artistName = track.artist.ifBlank { primaryArtist },
                    artistId = primaryArtistId,
                    albumArtist = primaryArtist,
                    albumArtistId = primaryArtistId,
                    albumName = albumName,
                    albumId = albumKey,
                    contentUriString = "youtube://${track.videoId}",
                    albumArtUriString = track.thumbnailUrl,
                    duration = track.durationMs,
                    genre = "YouTube Music",
                    filePath = "youtube://${track.videoId}",
                    parentDirectoryPath = YOUTUBE_FOLDER,
                    dateAdded = CATALOG_DATE_ADDED - index,
                    mimeType = null,
                    sourceType = SourceType.YOUTUBE,
                    mediaStoreDateAdded = 0L,
                    mediaStoreDateModified = 0L
                )
            )
        }

        val currentIds = songs.map { it.id }.toSet()
        val removedIds = musicDao.getReplaceableYoutubeSongIds().filter { it !in currentIds }
        musicDao.incrementalSyncMusicData(
            songs = songs,
            albums = albums.values.toList(),
            artists = artists.values.toList(),
            crossRefs = crossRefs,
            deletedSongIds = removedIds
        )
        userPreferencesRepository.setYoutubeCatalogRevision(CATALOG_REVISION)
        Timber.tag(TAG).i(
            "YouTube Music catalog synced: ${songs.size} songs, removed ${removedIds.size} (at $now)"
        )
    }

    suspend fun resolveAudioUrl(videoId: String): String? = youTubeMusicClient.resolveAudioUrl(videoId)

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
