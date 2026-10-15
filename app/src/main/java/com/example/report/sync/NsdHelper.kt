package com.example.report.sync

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * mDNS/DNS-SD advertisement (hub) and discovery (client) of `_kapibara._tcp.` services.
 *
 * TODO(decomp): register/unregister/stopDiscovery/stop were inlined by R8 into ReportViewModel
 *  (startHub / stopNet) and the hub-search DisposableEffect; method names and the split into
 *  separate functions are reconstructed from those inlined bodies.
 */
class NsdHelper(app: Application) {

    data class Found(val name: String, val ip: String, val port: Int)

    private val nsd: NsdManager =
        app.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    /** Called by the hub as `register("Report-" + Build.MODEL, 8777)`. */
    fun register(name: String, port: Int) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        regListener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun unregister() {
        regListener?.let { runCatching { nsd.unregisterService(it) } }
        regListener = null
    }

    fun startDiscovery(onFound: (Found) -> Unit) {
        discListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains("kapibara") != true) return
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val ip = info.host?.hostAddress ?: return
                            onFound(Found(info.serviceName ?: "Хаб", ip, info.port))
                        }
                    })
                } catch (_: Throwable) {
                }
            }
        }
        discListener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Throwable) {
        }
    }

    fun stopDiscovery() {
        discListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discListener = null
    }

    /** Used by `stopNet()`: drop both the advertisement and any running discovery. */
    fun stop() {
        unregister()
        stopDiscovery()
    }

    companion object {
        const val SERVICE_TYPE = "_kapibara._tcp."
    }
}
