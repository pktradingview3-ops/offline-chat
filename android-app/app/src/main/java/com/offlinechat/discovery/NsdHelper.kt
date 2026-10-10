package com.offlinechat.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Local network service discovery (NSD/mDNS) — Android-to-Android auto-discovery ke liye.
 * Web browser mDNS discover nahi kar sakta (PRD Section 3.2), isliye web app manual URL leta hai.
 *
 * MULTICAST LOCK: mDNS announcements are multicast (224.0.0.251:5353). Android's Wi-Fi
 * radio filters multicast unless something holds a `MulticastLock`, and while
 * `NsdManager` takes one internally on many versions, that is not guaranteed across
 * OEM builds — on devices that don't, discovery simply never finds anything and the app
 * silently falls back to "become host", which looks like a mysterious failure to split
 * into two islands. Holding our own lock for the discovery window is cheap insurance.
 */
class NsdHelper(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var advertising = false

    @Volatile
    private var discovering = false

    fun advertiseService(port: Int, serviceName: String = "OfflineChatHost") {
        stopAdvertising()

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                advertising = true
                Log.i(TAG, "Service registered: ${info.serviceName} on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                advertising = false
                registrationListener = null
                Log.w(TAG, "Registration failed: errorCode=$errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                advertising = false
                Log.i(TAG, "Service unregistered: ${info.serviceName}")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unregistration failed: errorCode=$errorCode")
            }
        }

        registrationListener = listener
        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { e ->
                // registerService throws IllegalArgumentException if a listener is already
                // registered — that must not take the whole host startup down with it.
                registrationListener = null
                Log.w(TAG, "registerService threw: ${e.message}")
            }
    }

    fun discoverServices(onFound: (NsdServiceInfo) -> Unit) {
        stopDiscovery()
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                discovering = true
                Log.i(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.startsWith(SERVICE_TYPE.trimEnd('.'))) return
                Log.i(TAG, "Found candidate service: ${service.serviceName}")
                runCatching {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "Resolve failed: errorCode=$errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Log.i(TAG, "Resolved ${serviceInfo.serviceName} -> " +
                                "${serviceInfo.host?.hostAddress}:${serviceInfo.port}")
                            onFound(serviceInfo)
                        }
                    })
                }.onFailure { e ->
                    // resolveService also throws if a resolve is already in flight.
                    Log.w(TAG, "resolveService threw: ${e.message}")
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}

            override fun onDiscoveryStopped(serviceType: String) {
                discovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false
                Log.w(TAG, "Start discovery failed: errorCode=$errorCode")
                // The listener was never accepted, so stopping it would throw too.
                discoveryListener = null
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed: errorCode=$errorCode")
            }
        }

        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { e ->
                discoveryListener = null
                releaseMulticastLock()
                Log.w(TAG, "discoverServices threw: ${e.message}")
            }
    }

    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { e -> Log.w(TAG, "stopServiceDiscovery threw: ${e.message}") }
        }
        discoveryListener = null
        discovering = false
        releaseMulticastLock()
    }

    fun stopAdvertising() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
                .onFailure { e -> Log.w(TAG, "unregisterService threw: ${e.message}") }
        }
        registrationListener = null
        advertising = false
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("offlinechat-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Multicast lock acquired")
        }.onFailure { e -> Log.w(TAG, "Could not acquire multicast lock: ${e.message}") }
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.takeIf { it.isHeld }?.release()
        }.onFailure { e -> Log.w(TAG, "Could not release multicast lock: ${e.message}") }
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdHelper"
        const val SERVICE_TYPE = "_offlinechat._tcp."
    }
}
