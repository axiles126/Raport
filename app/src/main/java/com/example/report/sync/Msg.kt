package com.example.report.sync

import kotlinx.serialization.Serializable

/**
 * Top-level sync message. Every [Msg] travels encrypted inside an `E2:` frame
 * (see [SecureChannel]) except one legacy case: a plaintext [Welcome] rejection sent to
 * pre-v2 clients whose first frame is raw JSON.
 *
 * Discriminator key is `"k"` (see [Proto]); values are FQCNs such as
 * `"com.example.report.sync.Msg.Snapshot"`.
 */
@Serializable
sealed class Msg {

    /** Client → hub, first encrypted message after the nonce exchange. */
    @Serializable
    data class Hello(val code: String, val deviceName: String) : Msg()

    /** Hub → client reply to [Hello]. */
    @Serializable
    data class Welcome(val accepted: Boolean, val reason: String = "") : Msg()

    /** Client → hub: one mutation to apply on the hub. */
    @Serializable
    data class Operation(val op: Op) : Msg()

    /** Client → hub: client's full local state, merged into the hub's state. */
    @Serializable
    data class PushState(val state: SharedState) : Msg()

    /** Hub → client: authoritative full state. */
    @Serializable
    data class Snapshot(val state: SharedState) : Msg()

    /** Hub → client: transient UI notification; [cls] is a status style ("dim", "ok", …). */
    @Serializable
    data class Toast(val text: String, val cls: String = "dim") : Msg()
}
