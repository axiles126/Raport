package com.example.report.sync

import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import java.util.Collections
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/**
 * LAN sync engine. One device runs a hub ([startHub]); others [connect] to it.
 * Wire protocol: see [Proto] and [SecureChannel].
 *
 * All callbacks are invoked through [onMain] (ReportViewModel posts them onto
 * `viewModelScope` with `Dispatchers.Main.immediate`).
 */
class SyncManager(private val onMain: (() -> Unit) -> Unit) {

    /** An authenticated WebSocket peer; sends are serialized so counters stay in order. */
    class Peer(val session: WebSocketSession, val chan: SecureChannel) {
        private val lock = Mutex()

        suspend fun send(text: String) {
            lock.withLock { session.send(Frame.Text(chan.seal(text))) }
        }
    }

    // ---- callbacks wired by ReportViewModel ----------------------------------------------
    var provideState: (() -> SharedState)? = null
    /** Hub: apply a client's [Op]. */
    var applyOp: ((Op) -> Unit)? = null
    /** Client: replace local state with the hub's snapshot. */
    var applySnapshot: ((SharedState) -> Unit)? = null
    /** Hub: merge a client's pushed state. */
    var mergeState: ((SharedState) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    /** (text, cls) */
    var onToast: ((String, String) -> Unit)? = null
    var onPeersChanged: ((Int) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- hub ----------------------------------------------------------------------------
    private var server: EmbeddedServer<*, *>? = null
    private val hubSessions: MutableList<Peer> = Collections.synchronizedList(ArrayList())
    private val hubMutex = Mutex()
    private var pairingCode = ""

    // ---- client -------------------------------------------------------------------------
    private var client: HttpClient? = null
    private var clientJob: Job? = null
    @Volatile private var clientWantConnect = false
    var clientSession: Peer? = null
        private set

    // TODO(decomp): startHub was inlined by R8 into ReportViewModel.startHub(); reconstructed
    //  from that call site (it sets pairingCode, builds the server, starts it, posts status).
    fun startHub(code: String) {
        pairingCode = code
        server = embeddedServer(ServerCIO, port = PORT, host = "0.0.0.0") {
            install(ServerWebSockets)
            routing {
                webSocket("/sync") { handleHubSession() }
            }
        }.start(wait = false)
        onMain { onStatus?.invoke("Хаб активний · код " + NetUtil.prettyCode(pairingCode)) }
    }

    private suspend fun DefaultWebSocketServerSession.handleHubSession() {
        // Liveness: ping every 5 s, drop a client that does not answer within 12 s (Wi-Fi gone).
        pingIntervalMillis = PING_MS
        timeoutMillis = PING_TIMEOUT_MS
        // 1. client nonce (or legacy JSON from an old client)
        val first = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { receiveText() }
        if (first != null && first.startsWith("{")) {
            send(Proto.encode(Msg.Welcome(accepted = false, reason = "оновіть Report на цьому телефоні")))
            close(CloseReason(CloseReason.Codes.NORMAL, "old version"))
            return
        }
        val nonceC = SecureChannel.parseNonce(first)
        if (nonceC == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "bad handshake"))
            return
        }

        // 2. hub nonce
        val nonceH = SecureChannel.newNonce()
        send("N2:" + Base64.getEncoder().encodeToString(nonceH))
        val chan = SecureChannel(pairingCode, nonceC, nonceH, isHub = true)

        // 3. encrypted Hello with the pairing code
        val second = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { receiveText() }
        val msg = second?.let { chan.open(it) }?.let { runCatching { Proto.decode(it) }.getOrNull() }
        val hello = msg as? Msg.Hello
        if (hello == null || NetUtil.normCode(hello.code) != NetUtil.normCode(pairingCode)) {
            send("X2:Невірний код")
            close(CloseReason(CloseReason.Codes.NORMAL, "bad code"))
            return
        }

        // 4. accept + initial snapshot
        val peer = Peer(this, chan)
        peer.send(Proto.encode(Msg.Welcome(accepted = true, reason = "")))
        val state = runCatching { provideState?.invoke() }.getOrNull() ?: SharedState()
        peer.send(Proto.encode(Msg.Snapshot(state)))

        hubSessions.add(peer)
        onMain { onPeersChanged?.invoke(hubSessions.size) }
        try {
            incoming.consumeEach { frame ->
                if (frame !is Frame.Text) return@consumeEach
                val text = chan.open(frame.readText()) ?: return@consumeEach
                when (val m = runCatching { Proto.decode(text) }.getOrNull()) {
                    is Msg.Operation -> onMain {
                        applyOp?.invoke(m.op)
                        broadcastSnapshot()
                    }
                    is Msg.PushState -> onMain {
                        mergeState?.invoke(m.state)
                        broadcastSnapshot()
                    }
                    else -> Unit
                }
            }
        } finally {
            hubSessions.remove(peer)
            onMain { onPeersChanged?.invoke(hubSessions.size) }
        }
    }

    /** Hub: push the current state to every connected client; drop peers whose send fails. */
    fun broadcastSnapshot() {
        val state = runCatching { provideState?.invoke() }.getOrNull() ?: return
        val text = Proto.encode(Msg.Snapshot(state))
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hubMutex.withLock {
                val dead = ArrayList<Peer>()
                for (p in hubSessions.toList()) {
                    runCatching { p.send(text) }.onFailure { dead.add(p) }
                }
                hubSessions.removeAll(dead)
            }
        }
    }

    // TODO(decomp): connect was inlined by R8 into ReportViewModel.connectToHub(); R8 also
    //  propagated the constant `Build.MODEL` argument into Msg.Hello's constructor, so the
    //  `deviceName` parameter is reconstructed (call site passes Build.MODEL, ip trimmed,
    //  code already normCode'd).
    /** Wakes the reconnect loop immediately (network came back). */
    private val kick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Called on main after every few failed attempts in a row — VM looks the hub up via NSD. */
    var onConnectFailing: ((Int) -> Unit)? = null

    @Volatile var connectedHost: String = ""
        private set

    fun connect(host: String, code: String, deviceName: String = Build.MODEL) {
        stopClient()
        clientWantConnect = true
        connectedHost = host
        val c = HttpClient(ClientCIO) { install(ClientWebSockets) }
        client = c
        clientJob = scope.launch {
            var firstTry = true
            var fails = 0
            while (clientWantConnect) {
                var ok = false
                try {
                    c.webSocket(method = HttpMethod.Get, host = host, port = PORT, path = "/sync") {
                        pingIntervalMillis = PING_MS
                        timeoutMillis = PING_TIMEOUT_MS
                        runClientSession(host, code, deviceName) { firstTry = true; ok = true; fails = 0 }
                    }
                } catch (e: Exception) {
                    // connection refused / timed out / dropped
                } finally {
                    clientSession = null
                }
                if (!clientWantConnect) break
                if (!ok) {
                    fails++
                    if (fails % 3 == 0) onMain { onConnectFailing?.invoke(fails) }
                }
                if (firstTry) onMain { onStatus?.invoke("Перепідключення до хаба…") }
                firstTry = false
                withTimeoutOrNull(RECONNECT_DELAY_MS) { kick.receive() }
            }
        }
    }

    /** Network is back: drop a possibly stale session and retry right now. */
    fun reconnectNow() {
        if (!clientWantConnect) return
        clientSession?.let { p -> scope.launch { runCatching { p.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "net change")) } } }
        kick.trySend(Unit)
    }

    /**
     * Client side of one WebSocket connection: handshake, then receive loop until the socket
     * closes. [onAccepted] fires after the hub accepted us and our state was pushed, right
     * before the receive loop (original: `firstTry.element = true` on a captured Ref).
     */
    private suspend fun DefaultClientWebSocketSession.runClientSession(
        host: String,
        code: String,
        deviceName: String,
        onAccepted: () -> Unit,
    ) {
        // 1. our nonce
        val nonceC = SecureChannel.newNonce()
        send("N2:" + Base64.getEncoder().encodeToString(nonceC))

        // 2. hub nonce
        val reply = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { receiveText() }
        val nonceH = SecureChannel.parseNonce(reply)
        if (nonceH == null) {
            if (reply != null && reply.startsWith("{")) {
                onMain { onStatus?.invoke("Відмова: на хабі стара версія Report") }
                clientWantConnect = false
            }
            return // no answer: transient, the loop retries
        }

        // 3. Hello
        val peer = Peer(this, SecureChannel(code, nonceC, nonceH, isHub = false))
        clientSession = peer
        peer.send(Proto.encode(Msg.Hello(NetUtil.normCode(code), deviceName)))

        // 4. Welcome
        val ans = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { receiveText() }
        val welcome = ans?.let { peer.chan.open(it) }
            ?.let { runCatching { Proto.decode(it) }.getOrNull() } as? Msg.Welcome
        if (welcome == null || !welcome.accepted) {
            val reason = welcome?.reason ?: if (ans != null && ans.startsWith("X2:")) ans.removePrefix("X2:") else null
            if (reason != null) { // real refusal (bad code / old version) — stop
                onMain { onStatus?.invoke("Відмова: $reason") }
                clientWantConnect = false
            }
            return // no answer: transient, the loop retries
        }
        onMain { onStatus?.invoke("Підключено до $host") }

        // 5. push our local state so the hub can merge it
        provideState?.let { peer.send(Proto.encode(Msg.PushState(it()))) }
        onAccepted()

        // 6. receive loop
        incoming.consumeEach { frame ->
            if (frame !is Frame.Text) return@consumeEach
            val text = peer.chan.open(frame.readText()) ?: return@consumeEach
            when (val m = runCatching { Proto.decode(text) }.getOrNull()) {
                is Msg.Snapshot -> onMain { applySnapshot?.invoke(m.state) }
                is Msg.Toast -> onMain { onToast?.invoke(m.text, m.cls) }
                else -> Unit
            }
        }
    }

    /** Client: send one op to the hub (fire-and-forget; errors are swallowed). */
    fun sendOp(op: Op) {
        val peer = clientSession ?: return
        val text = Proto.encode(Msg.Operation(op))
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { peer.send(text) }
        }
    }

    // TODO(decomp): this block is inlined both into shutdown() and into the head of
    //  connectToHub(); extracted as a private helper.
    private fun stopClient() {
        clientWantConnect = false
        clientJob?.cancel()
        clientJob = null
        runCatching { client?.close() }
        client = null
        clientSession = null
    }

    fun shutdown() {
        runCatching { server?.stop(200L, 500L) }
        server = null
        hubSessions.clear()
        onMain {
            onStatus?.invoke("")
            onPeersChanged?.invoke(0)
        }
        stopClient()
    }

    private suspend fun DefaultWebSocketSession.receiveText(): String? =
        (incoming.receive() as? Frame.Text)?.readText()

    companion object {
        const val PORT = 8777
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val PING_MS = 5_000L
        private const val PING_TIMEOUT_MS = 12_000L
    }
}
