package com.offlinechat.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Local network service discovery (NSD/mDNS) — sirf Android-to-Android auto-discovery ke liye.
 * Web browser mDNS discover nahi kar sakta (PRD Section 3.2), isliye web app manual URL leta hai.
 */
class NsdHelper(context: Context) {

    private val nsdManager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun advertiseService(port: Int, serviceName: String = "OfflineChatHost") {
        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            this.port = port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun discoverServices(onFound: (NsdServiceInfo) -> Unit) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.startsWith(SERVICE_TYPE.trimEnd('.'))) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "Resolve failed: $errorCode")
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            onFound(serviceInfo)
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsdManager.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    fun stopAdvertising() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
    }

    companion object {
        private const val TAG = "NsdHelper"
        const val SERVICE_TYPE = "_offlinechat._tcp."
    }
}
