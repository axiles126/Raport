package com.example.report.sync

import kotlinx.serialization.json.Json

/**
 * JSON codec for [Msg] and the LAN sync wire protocol ("REPORTSYNC2").
 *
 * ## Transport
 * - Hub: Ktor CIO server on `0.0.0.0:8777`, WebSocket route `GET /sync`.
 * - Client: Ktor CIO client, `ws://<hubIp>:8777/sync`.
 * - Discovery: NSD/mDNS service type `_kapibara._tcp.`, name `Report-<Build.MODEL>`, port 8777
 *   (see [NsdHelper]).
 * - All frames are WebSocket **text** frames.
 *
 * ## Frame prefixes
 * | prefix | direction | meaning |
 * |--------|-----------|---------|
 * | `N2:`  | both      | handshake nonce: `"N2:" + base64(16 random bytes)` (plaintext) |
 * | `E2:`  | both      | encrypted [Msg] JSON (see [SecureChannel]) |
 * | `X2:`  | hub → cl  | plaintext error, currently only `"X2:Невірний код"` |
 * | `{`    | legacy    | raw JSON from a pre-v2 peer — treated as "old version" |
 *
 * ## Handshake (each receive has a 10 s timeout; timeout ⇒ `null`)
 * 1. C → H  `N2:<nonceC>`
 *    - H: first frame starts with `{` ⇒ send plaintext `Welcome(accepted=false,
 *      reason="оновіть Report на цьому телефоні")` and close `1000 "old version"`.
 *    - H: not a valid `N2:` (16 bytes) ⇒ close `1003 "bad handshake"`.
 * 2. H → C  `N2:<nonceH>`
 *    - C: reply not a valid nonce ⇒ status "Відмова: на хабі стара версія Report" if it starts with `{`,
 *      else "Відмова: немає звʼязку"; client stops reconnecting.
 * 3. Both build `SecureChannel(code, nonceC, nonceH, isHub)`.
 * 4. C → H  `E2:{Hello(code = normCode(code), deviceName = Build.MODEL)}`
 *    - H: undecryptable / not Hello / `normCode(hello.code) != normCode(pairingCode)` ⇒
 *      send plaintext `X2:Невірний код`, close `1000 "bad code"`.
 * 5. H → C  `E2:{Welcome(accepted=true)}` then `E2:{Snapshot(hubState)}`.
 *    - C: no accepted Welcome ⇒ status "Відмова: <reason>" where reason is Welcome.reason,
 *      else the `X2:` text, else "немає звʼязку"; client stops reconnecting.
 *    - C: accepted ⇒ status "Підключено до <host>", then C → H `E2:{PushState(localState)}`.
 *
 * ## Steady state
 * - C → H: [Msg.Operation], [Msg.PushState]. Hub applies on main thread (applyOp / mergeState)
 *   and re-broadcasts a [Msg.Snapshot] to every connected client.
 * - H → C: [Msg.Snapshot] (applySnapshot), [Msg.Toast] (onToast).
 * - Client reconnects every 3 s while it still wants a connection.
 *
 * ## JSON
 * `ignoreUnknownKeys = true`, `encodeDefaults = true`, `classDiscriminator = "k"`;
 * discriminator values are the classes' FQCNs (no `@SerialName`).
 */
object Proto {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "k"
    }

    fun encode(msg: Msg): String = json.encodeToString(Msg.serializer(), msg)

    fun decode(text: String): Msg = json.decodeFromString(Msg.serializer(), text)
}
