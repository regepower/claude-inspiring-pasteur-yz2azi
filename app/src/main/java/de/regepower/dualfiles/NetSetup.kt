package de.regepower.dualfiles

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.UUID

/** Dialog to add an FTP/FTPS/WebDAV server, with a search for servers in the local network. */
internal object NetSetup {
    private class Found(val type: NetType, val host: String, val port: Int, val name: String) {
        override fun toString() = "$name  ·  ${type.name.lowercase()}  ·  $host:$port"
    }

    fun show(activity: Activity, onAdded: (File) -> Unit) {
        val ctx: Context = activity
        val types = NetType.values()
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
        fun field(hint: Int, inputType: Int = InputType.TYPE_CLASS_TEXT) = EditText(ctx).apply {
            setHint(hint)
            setSingleLine()
            this.inputType = inputType
            col.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val type = Spinner(ctx)
        type.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, types.map { ctx.getString(it.label) })
        col.addView(type)
        val host = field(R.string.net_host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field(R.string.net_port, InputType.TYPE_CLASS_NUMBER)
        val path = field(R.string.net_path, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val user = field(R.string.net_user)
        val pass = field(R.string.net_password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val name = field(R.string.net_name)
        val insecure = CheckBox(ctx).apply { setText(R.string.net_insecure) }
        col.addView(insecure)
        type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                port.hint = ctx.getString(R.string.net_port) + " (" + types[pos].defaultPort + ")"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val scroll = ScrollView(ctx)
        scroll.addView(col)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.net_title)
            .setView(scroll)
            .setPositiveButton(R.string.net_save, null)
            .setNeutralButton(R.string.net_search, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            // Own click handling: the dialog stays open while testing or searching
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                search(activity) { f ->
                    type.setSelection(types.indexOf(f.type))
                    host.setText(f.host)
                    port.setText(f.port.toString())
                    if (name.text.isEmpty()) name.setText(f.name)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = types[type.selectedItemPosition]
                val h = host.text.toString().trim().substringAfter("://").substringBefore('/')
                if (h.isEmpty()) {
                    host.error = ctx.getString(R.string.net_host)
                    return@setOnClickListener
                }
                val cfg = NetConfig(
                    UUID.randomUUID().toString().take(8), t, h,
                    port.text.toString().toIntOrNull() ?: t.defaultPort,
                    "/" + path.text.toString().trim().trim('/'),
                    user.text.toString().trim(), pass.text.toString(), name.text.toString().trim(), insecure.isChecked,
                )
                val button = it
                button.isEnabled = false
                Toast.makeText(ctx, R.string.net_testing, Toast.LENGTH_SHORT).show()
                Thread {
                    val error = Net.test(ctx, cfg)
                    activity.runOnUiThread {
                        button.isEnabled = true
                        if (error != null) {
                            AlertDialog.Builder(ctx).setMessage(ctx.getString(R.string.net_error, error))
                                .setPositiveButton(R.string.help_ok, null).show()
                        } else {
                            dialog.dismiss()
                            onAdded(Net.save(ctx, cfg))
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    /**
     * Looks for FTP and WebDAV servers that announce themselves in the local network (mDNS / Bonjour)
     * for a few seconds, then lets the user pick one.
     */
    private fun search(activity: Activity, onPick: (Found) -> Unit) {
        val nsd = activity.getSystemService(NsdManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val found = ArrayList<Found>()
        val adapter = ArrayAdapter<Found>(activity, android.R.layout.simple_list_item_1, found)
        val status = TextView(activity).apply {
            setText(R.string.net_searching)
            setPadding(activity.dp(24), activity.dp(12), activity.dp(24), activity.dp(4))
        }
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun typeOf(service: String) = when {
            service.startsWith("_ftp.") -> NetType.FTP
            service.startsWith("_webdavs.") -> NetType.DAVS
            else -> NetType.DAV
        }

        // Android resolves one service at a time on older versions
        fun resolveNext() {
            if (resolving) return
            val info = queue.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; resolveNext() }
                }

                override fun onServiceResolved(si: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val addr = si.host?.hostAddress ?: ""
                    main.post {
                        resolving = false
                        if (addr.isNotEmpty() && found.none { it.host == addr && it.port == si.port }) {
                            found.add(Found(typeOf(si.serviceType.trimStart('.')), addr, si.port, si.serviceName))
                            adapter.notifyDataSetChanged()
                        }
                        resolveNext()
                    }
                }
            })
        }

        for (service in listOf("_ftp._tcp", "_webdav._tcp", "_webdavs._tcp")) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(si: NsdServiceInfo) {
                    main.post { queue.addLast(si); resolveNext() }
                }
                override fun onServiceLost(si: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            try {
                nsd.discoverServices(service, NsdManager.PROTOCOL_DNS_SD, l)
                listeners.add(l)
            } catch (e: Exception) {
                // this service type is not searched
            }
        }
        fun stop() {
            for (l in listeners) try { nsd.stopServiceDiscovery(l) } catch (e: Exception) { }
            listeners.clear()
        }
        main.postDelayed({
            status.setText(if (found.isEmpty()) R.string.net_none_found else R.string.net_found)
            stop()
        }, 6000)
        AlertDialog.Builder(activity)
            .setTitle(R.string.net_search)
            .setCustomTitle(status)
            .setAdapter(adapter) { _, which -> onPick(found[which]) }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { stop() }
            .show()
    }
}
