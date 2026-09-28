package com.unshoo.pixelmusic.data.jam

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.di.AppScope
import com.unshoo.pixelmusic.di.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random

@Singleton
class JamSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: JamPlaybackBridge,
    private val dispatchers: DispatcherProvider,
    @AppScope private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(JamUiState())
    val ui: StateFlow<JamUiState> = _ui.asStateFlow()

    private val sockets = ConcurrentHashMap<String, WebSocketSession>()
    private val guestMembers = ConcurrentHashMap<String, JamMember>()
    private var partyCode: String = ""
    private var partyPort: Int = DEFAULT_PORT
    private var revision: Long = 0
    private var publishJob: Job? = null
    private var guestJob: Job? = null
    private var guestSession: WebSocketSession? = null
    private var httpClient: HttpClient? = null
    private var stopServer: (() -> Unit)? = null
    private var lastGuestSeekElapsed = 0L
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdRegistered = false
    private var nsdDiscovering = false
    private val selfId = UUID.randomUUID().toString().take(8)
    private val deviceName: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            nsdRegistered = true
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            nsdRegistered = false
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) {
            nsdRegistered = false
        }

        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            nsdDiscovering = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onDiscoveryStarted(serviceType: String) {
            nsdDiscovering = true
        }

        override fun onDiscoveryStopped(serviceType: String) {
            nsdDiscovering = false
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType?.contains("rootbeat") != true) return
            resolveFoundService(service)
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            _ui.update { state ->
                state.copy(nearby = state.nearby.filterNot { it.name == service.serviceName })
            }
        }
    }

    fun startHosting() {
        if (_ui.value.role == JamRole.Host) return
        leaveInternal(updateUi = false)
        scope.launch {
            partyCode = newCode()
            val address = localIpv4().orEmpty()
            var boundPort = 0
            for (candidate in DEFAULT_PORT until DEFAULT_PORT + 8) {
                if (launchServer(candidate)) {
                    boundPort = candidate
                    break
                }
            }
            if (boundPort == 0) {
                _ui.update { it.copy(role = JamRole.Idle, error = context.getString(R.string.jam_start_failed)) }
                return@launch
            }
            partyPort = boundPort
            acquireMulticast()
            registerService()
            _ui.value = JamUiState(
                role = JamRole.Host,
                code = partyCode,
                hostAddress = address,
                port = boundPort,
                members = listOf(hostMember()),
                status = context.getString(R.string.jam_share_line),
            )
            publishJob = scope.launch {
                while (isActive && _ui.value.role == JamRole.Host) {
                    publish()
                    val playing = _ui.value.isPlaying
                    delay(if (playing) 700 else 1500)
                }
            }
        }
    }

    fun join(code: String, host: String, port: Int = DEFAULT_PORT) {
        val cleanCode = code.trim().uppercase()
        val cleanHost = host.trim()
        if (cleanCode.length < 4 || cleanHost.isBlank()) {
            _ui.update { it.copy(error = context.getString(R.string.jam_status_need_ip)) }
            return
        }
        leaveInternal(updateUi = false)
        _ui.value = JamUiState(
            role = JamRole.Guest,
            code = cleanCode,
            hostAddress = cleanHost,
            port = port,
            status = context.getString(R.string.jam_status_connecting),
        )
        guestJob = scope.launch {
            val client = HttpClient(ClientCIO) { install(ClientWebSockets) }
            httpClient = client
            try {
                client.webSocket("ws://$cleanHost:$port/jam?code=$cleanCode") {
                    guestSession = this
                    send(Frame.Text(JamCodec.encode(JamMessage(type = "hello", memberId = selfId, name = deviceName))))
                    _ui.update { it.copy(status = context.getString(R.string.jam_status_joined), error = "") }
                    for (frame in incoming) {
                        if (frame is Frame.Text) onGuestFrame(frame.readText())
                    }
                }
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "Jam join failed")
                if (_ui.value.role == JamRole.Guest) {
                    _ui.update { it.copy(role = JamRole.Idle, error = context.getString(R.string.jam_join_failed)) }
                }
            }
        }
    }

    fun findNearby() {
        val manager = nsdManager ?: return
        acquireMulticast()
        if (nsdDiscovering) return
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            _ui.update { state -> state.copy(error = context.getString(R.string.jam_nearby_failed)) }
        }
    }

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _ui.update { it.copy(searchResults = emptyList()) }
            return
        }
        scope.launch {
            when (_ui.value.role) {
                JamRole.Host -> {
                    val results = runCatching { bridge.search?.invoke(trimmed).orEmpty() }.getOrDefault(emptyList())
                    _ui.update { it.copy(searchResults = results, error = "") }
                }
                JamRole.Guest -> sendGuest(JamMessage(type = "search", query = trimmed, memberId = selfId))
                JamRole.Idle -> Unit
            }
        }
    }

    fun addSong(songId: String) {
        scope.launch {
            when (_ui.value.role) {
                JamRole.Host -> {
                    withContext(dispatchers.main) { bridge.enqueue?.invoke(songId) }
                    publish()
                }
                JamRole.Guest -> sendGuest(JamMessage(type = "add", songId = songId, memberId = selfId))
                JamRole.Idle -> Unit
            }
        }
    }

    fun togglePlayback() {
        scope.launch {
            val playing = withContext(dispatchers.main) { bridge.snapshot?.invoke()?.isPlaying == true }
            val action = if (playing) "pause" else "play"
            dispatchControl(action, 0L)
        }
    }

    fun next() = scope.launch { dispatchControl("next", 0L) }

    fun previous() = scope.launch { dispatchControl("previous", 0L) }

    fun leave() {
        leaveInternal(updateUi = true)
    }

    private suspend fun dispatchControl(action: String, positionMs: Long) {
        when (_ui.value.role) {
            JamRole.Host -> {
                applyControl(action, positionMs)
                publish()
            }
            JamRole.Guest -> sendGuest(
                JamMessage(type = "control", action = action, positionMs = positionMs, memberId = selfId),
            )
            JamRole.Idle -> Unit
        }
    }

    private fun launchServer(port: Int): Boolean {
        return try {
            val started = embeddedServer(CIO, host = "0.0.0.0", port = port) {
                jamModule()
            }.start(wait = false)
            stopServer = { started.stop(200, 1_000) }
            true
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Could not bind jam port $port")
            false
        }
    }

    private fun Application.jamModule() {
        install(WebSockets)
        routing {
            webSocket("/jam") {
                val code = call.request.queryParameters["code"].orEmpty()
                if (!code.equals(partyCode, ignoreCase = true)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "code"))
                    return@webSocket
                }
                val memberKey = UUID.randomUUID().toString().take(8)
                sockets[memberKey] = this
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) onHostFrame(memberKey, frame.readText(), this)
                    }
                } finally {
                    sockets.remove(memberKey)
                    guestMembers.remove(memberKey)
                    scope.launch { publish() }
                }
            }
            get("/audio/{id}") {
                val token = call.request.queryParameters["token"].orEmpty()
                if (!token.equals(partyCode, ignoreCase = true)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                val id = call.parameters["id"]
                val uri = id?.let { bridge.contentUri?.invoke(it) }
                if (id.isNullOrBlank() || uri.isNullOrBlank()) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val descriptor = runCatching {
                    context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
                }.getOrNull()
                if (descriptor == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                descriptor.use { pfd ->
                    val size = pfd.statSize
                    if (size <= 0) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    val (start, end, partial) = JamAudio.parseRange(call.request.header(HttpHeaders.Range), size)
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    if (partial) {
                        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$size")
                    }
                    call.respondBytesWriter(
                        contentType = ContentType.Application.OctetStream,
                        status = if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                        contentLength = end - start + 1,
                    ) {
                        writeFileRange(pfd, start, end)
                    }
                }
            }
        }
    }

    private suspend fun onHostFrame(memberKey: String, text: String, session: WebSocketSession) {
        val message = runCatching { JamCodec.decode(text) }.getOrNull() ?: return
        when (message.type) {
            "hello" -> {
                val name = message.name.ifBlank { "Guest" }
                guestMembers[memberKey] = JamMember(id = memberKey, name = name, isHost = false)
                publish()
            }
            "control" -> {
                applyControl(message.action, message.positionMs)
                publish()
            }
            "search" -> {
                val results = runCatching { bridge.search?.invoke(message.query).orEmpty() }.getOrDefault(emptyList())
                runCatching {
                    session.send(Frame.Text(JamCodec.encode(JamMessage(type = "searchResults", results = results))))
                }
            }
            "add" -> {
                if (message.songId.isNotBlank()) {
                    withContext(dispatchers.main) { bridge.enqueue?.invoke(message.songId) }
                    publish()
                }
            }
        }
    }

    private suspend fun onGuestFrame(text: String) {
        val message = runCatching { JamCodec.decode(text) }.getOrNull() ?: return
        when (message.type) {
            "state" -> applyRemoteState(message)
            "searchResults" -> _ui.update { it.copy(searchResults = message.results) }
        }
    }

    private suspend fun applyRemoteState(message: JamMessage) {
        _ui.update {
            it.copy(
                members = message.members,
                trackTitle = message.track?.title.orEmpty(),
                trackArtist = message.track?.artist.orEmpty(),
                isPlaying = message.isPlaying,
                upNext = message.upNext,
                error = "",
            )
        }
        val track = message.track
        if (track == null || !track.streamUrl.startsWith("http")) {
            if (track != null) {
                _ui.update { it.copy(error = context.getString(R.string.jam_track_host_only)) }
            }
            return
        }
        withContext(dispatchers.main) {
            val local = bridge.snapshot?.invoke()
            val remoteId = "jam:${track.id}"
            if (local?.songId != remoteId) {
                bridge.playRemote?.invoke(track, message.positionMs)
                return@withContext
            }
            val localPlaying = local.isPlaying
            if (message.isPlaying && !localPlaying) bridge.play?.invoke()
            if (!message.isPlaying && localPlaying) bridge.pause?.invoke()
            val expected = message.positionMs + (System.currentTimeMillis() - message.sentAtEpochMs).coerceAtLeast(0)
            val drift = abs((local.positionMs) - expected)
            val now = SystemClock.elapsedRealtime()
            if (drift > 1_500 && now - lastGuestSeekElapsed > 2_500) {
                lastGuestSeekElapsed = now
                bridge.seek?.invoke(expected)
            }
        }
    }

    private suspend fun applyControl(action: String, positionMs: Long) {
        withContext(dispatchers.main) {
            when (action) {
                "play" -> bridge.play?.invoke()
                "pause" -> bridge.pause?.invoke()
                "next" -> bridge.next?.invoke()
                "previous" -> bridge.previous?.invoke()
                "seek" -> bridge.seek?.invoke(positionMs)
            }
        }
    }

    private suspend fun publish() {
        if (_ui.value.role != JamRole.Host) return
        val message = buildStateMessage()
        _ui.update {
            it.copy(
                members = message.members,
                trackTitle = message.track?.title.orEmpty(),
                trackArtist = message.track?.artist.orEmpty(),
                isPlaying = message.isPlaying,
                upNext = message.upNext,
            )
        }
        val text = JamCodec.encode(message)
        sockets.values.forEach { socket ->
            runCatching { socket.send(Frame.Text(text)) }
        }
    }

    private suspend fun buildStateMessage(): JamMessage {
        val snap = withContext(dispatchers.main) { bridge.snapshot?.invoke() }
        revision += 1
        val track = snap?.let { snapshot ->
            val libraryId = snapshot.songId.removePrefix("jam:")
            val streamUrl = when {
                snapshot.contentUri.startsWith("http://") || snapshot.contentUri.startsWith("https://") ->
                    snapshot.contentUri
                snapshot.songId.startsWith("jam:") -> ""
                _ui.value.hostAddress.isBlank() -> ""
                else -> "http://${_ui.value.hostAddress}:$partyPort/audio/$libraryId?token=$partyCode"
            }
            JamTrack(
                id = libraryId,
                title = snapshot.title,
                artist = snapshot.artist,
                album = snapshot.album,
                durationMs = snapshot.durationMs,
                streamUrl = streamUrl,
            )
        }
        return JamMessage(
            type = "state",
            code = partyCode,
            revision = revision,
            sentAtEpochMs = System.currentTimeMillis(),
            positionMs = snap?.positionMs ?: 0L,
            isPlaying = snap?.isPlaying == true,
            track = track,
            members = listOf(hostMember()) + guestMembers.values,
            upNext = snap?.upNext.orEmpty(),
        )
    }

    private suspend fun sendGuest(message: JamMessage) {
        val session = guestSession ?: return
        runCatching { session.send(Frame.Text(JamCodec.encode(message))) }
    }

    private fun leaveInternal(updateUi: Boolean) {
        publishJob?.cancel()
        publishJob = null
        guestJob?.cancel()
        guestJob = null
        guestSession = null
        runCatching { httpClient?.close() }
        httpClient = null
        sockets.clear()
        guestMembers.clear()
        runCatching { stopServer?.invoke() }
        stopServer = null
        if (nsdRegistered) {
            runCatching { nsdManager?.unregisterService(registrationListener) }
            nsdRegistered = false
        }
        if (nsdDiscovering) {
            runCatching { nsdManager?.stopServiceDiscovery(discoveryListener) }
            nsdDiscovering = false
        }
        runCatching { multicastLock?.release() }
        multicastLock = null
        partyCode = ""
        if (updateUi) _ui.value = JamUiState()
    }

    private fun registerService() {
        val manager = nsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "RB-$partyCode"
            serviceType = SERVICE_TYPE
            port = partyPort
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener) }
    }

    @Suppress("DEPRECATION")
    private fun resolveFoundService(service: NsdServiceInfo) {
        val manager = nsdManager ?: return
        runCatching {
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    val item = NearbyJamHost(
                        name = resolved.serviceName.orEmpty(),
                        host = host,
                        port = resolved.port,
                    )
                    _ui.update { state ->
                        if (state.nearby.any { it.host == item.host && it.port == item.port }) state
                        else state.copy(nearby = state.nearby + item)
                    }
                }
            })
        }
    }

    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("rootbeat-jam").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun hostMember() = JamMember(id = "host", name = deviceName, isHost = true)

    private fun localIpv4(): String? {
        return NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { iface -> iface.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }

    private fun newCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(6) { repeat(6) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }

    private suspend fun ByteWriteChannel.writeFileRange(
        descriptor: ParcelFileDescriptor,
        start: Long,
        endInclusive: Long,
    ) {
        val input = FileInputStream(descriptor.fileDescriptor)
        input.channel.position(start)
        val buffer = ByteArray(64 * 1024)
        var remaining = endInclusive - start + 1
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            writeFully(buffer, 0, read)
            remaining -= read
        }
    }

    private companion object {
        const val TAG = "RootBeatJam"
        const val SERVICE_TYPE = "_rootbeat._tcp."
    }
}
