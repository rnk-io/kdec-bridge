package dev.kdecbridge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import tsbridge.Tsbridge

/** Single-screen UI: settings, controls, status and the event log. */
class MainActivity : Activity() {

    private lateinit var cfg: Config
    private lateinit var tsnetBox: CheckBox
    private lateinit var hostField: EditText
    private lateinit var authKeyField: EditText
    private lateinit var nodeNameField: EditText
    private lateinit var remoteField: EditText
    private lateinit var intervalField: EditText
    private lateinit var payloadOffsetField: EditText
    private lateinit var payloadBox: CheckBox
    private lateinit var toggle: Button
    private lateinit var banner: TextView
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        EventLog.init(this)
        noteUnexpectedDeath()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = "KDEC Bridge"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Connects KDE Connect to your computer through loopback, " +
                   "without using the phone's VPN slot."
            textSize = 13f
            setPadding(0, 8, 0, 28)
        })

        tsnetBox = CheckBox(this).apply {
            text = "Use Tailscale (tsnet)"
            isChecked = cfg.mode == Config.MODE_TSNET
        }
        root.addView(tsnetBox)

        root.addView(label("Computer address (MagicDNS name; LAN address or relay host in direct mode)"))
        hostField = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "computer-name.your-tailnet.ts.net"
            setText(cfg.host)
        }
        root.addView(hostField)

        root.addView(label("Tailscale auth key (first run only; cleared once the node is up)"))
        authKeyField = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "tskey-auth-… or leave blank and use the Authenticate button"
            setText(cfg.authKey)
        }
        root.addView(authKeyField)

        root.addView(label("Tailnet node name for this phone"))
        nodeNameField = EditText(this).apply { setText(cfg.nodeName) }
        root.addView(nodeNameField)

        root.addView(label("kdeconnectd port on the computer (1716 unless relayed)"))
        remoteField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cfg.remotePort.toString())
        }
        root.addView(remoteField)

        root.addView(label("Reconnect interval (seconds, while disconnected)"))
        intervalField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cfg.intervalSec.toString())
        }
        root.addView(intervalField)

        root.addView(label("Payload port offset (0 unless a relay remaps 1739-1764)"))
        payloadOffsetField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(cfg.payloadOffset.toString())
        }
        root.addView(payloadOffsetField)

        payloadBox = CheckBox(this).apply {
            text = "Proxy payload ports 1739-1764 (file transfer)"
            isChecked = cfg.proxyPayload
        }
        root.addView(payloadBox)

        toggle = Button(this).apply {
            text = if (BridgeService.isRunning) "Stop bridge" else "Start bridge"
            setOnClickListener { onToggle() }
        }
        root.addView(toggle)

        root.addView(Button(this).apply {
            text = "Authenticate tailnet (opens browser)"
            setOnClickListener {
                val u = runCatching { Tsbridge.loginURL() }.getOrDefault("")
                if (u.isBlank()) {
                    toast("No login pending - start the bridge in Tailscale mode first")
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                }
            }
        })

        root.addView(Button(this).apply {
            text = "Learn computer identity"
            setOnClickListener { learnIdentity() }
        })

        root.addView(Button(this).apply {
            text = "Forget learned identity"
            setOnClickListener {
                save()
                cfg.forgetIdentity(cfg.host)
                BridgeService.log("identity for ${cfg.host} forgotten - will re-learn on next start")
                toast("Identity forgotten. Restart the bridge to learn it again")
                refresh()
            }
        })

        root.addView(Button(this).apply {
            text = "Inject once (test)"
            setOnClickListener { injectOnce() }
        })

        root.addView(Button(this).apply {
            text = "Exempt from battery optimization"
            setOnClickListener { requestBatteryExemption() }
        })

        banner = TextView(this).apply {
            setPadding(24, 24, 24, 24)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(banner)

        status = TextView(this).apply {
            setPadding(0, 12, 0, 8)
            setTypeface(Typeface.MONOSPACE)
            textSize = 12f
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "Event log (newest last)"
            textSize = 12f
            setPadding(0, 18, 0, 4)
            setTypeface(typeface, Typeface.BOLD)
        })
        logView = TextView(this).apply {
            setTypeface(Typeface.MONOSPACE)
            textSize = 11f
            setTextColor(Color.DKGRAY)
        }
        root.addView(ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600)
        })

        setContentView(ScrollView(this).apply { addView(root) })

        // Ask once. If the user later revokes it to hide the status bar icon,
        // the service keeps running without a visible notification.
        if (Build.VERSION.SDK_INT >= 33 && !cfg.notifPermAsked) {
            cfg.notifPermAsked = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        BridgeService.onLog = { ui.post { refresh() } }
        refresh()

        // Allows starting the bridge without the UI (adb, automation) while
        // keeping the service itself unexported.
        if (intent?.getBooleanExtra("autostart", false) == true && !BridgeService.isRunning) {
            save()
            if (cfg.host.isNotBlank()) BridgeService.start(this)
            ui.postDelayed({ refresh() }, 800)
        }
    }

    /**
     * Records an unexpected stop in the event log once, then restarts the
     * bridge. The banner only shows the current state, so without this entry
     * a kill would go unnoticed once the bridge was running again.
     */
    private fun noteUnexpectedDeath() {
        val beat = cfg.lastHeartbeat
        if (!BridgeService.isRunning && cfg.enabled && !cfg.cleanStop &&
            beat > 0 && beat != cfg.killReported) {
            cfg.killReported = beat
            EventLog.add("!! previous session ended unexpectedly - last alive " +
                EventLog.clockOf(beat) + " (" + EventLog.ago(beat) + ")")
            if (cfg.host.isNotBlank()) {
                EventLog.add("restarting bridge after unexpected death")
                BridgeService.start(this)
            }
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(0, 20, 0, 4)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun save() {
        cfg.mode = if (tsnetBox.isChecked) Config.MODE_TSNET else Config.MODE_DIRECT
        cfg.host = hostField.text.toString()
        cfg.authKey = authKeyField.text.toString()
        cfg.nodeName = nodeNameField.text.toString()
        cfg.remotePort = remoteField.text.toString().toIntOrNull() ?: Config.MIN_PORT
        cfg.intervalSec = intervalField.text.toString().toIntOrNull() ?: 10
        cfg.payloadOffset = payloadOffsetField.text.toString().toIntOrNull() ?: 0
        cfg.proxyPayload = payloadBox.isChecked
    }

    private fun onToggle() {
        if (BridgeService.isRunning) {
            BridgeService.stop(this)
        } else {
            save()
            if (cfg.host.isBlank()) { toast("Set the computer address first"); return }
            BridgeService.start(this)
        }
        ui.postDelayed({ refresh() }, 600)
    }

    /** Asks the computer for its identity. In tsnet mode the bridge must be
     *  running; in direct mode this works on the LAN without it. */
    private fun learnIdentity() {
        save()
        if (cfg.host.isBlank()) { toast("Set the computer address first"); return }
        if (cfg.mode == Config.MODE_TSNET && !BridgeService.isRunning) {
            toast("Start the bridge first - discovery runs over the tailnet"); return
        }
        toast("Asking ${cfg.host}…")
        Thread {
            val r = runCatching { IdentityDiscovery.learnAndStore(cfg) }
            ui.post {
                r.onSuccess {
                    val name = Injector.deviceName(it)
                    BridgeService.log("identity: learned from $name (${Injector.deviceId(it).take(8)}…)")
                    Toast.makeText(this, "Learned identity from $name", Toast.LENGTH_LONG).show()
                }.onFailure {
                    BridgeService.log("identity: discovery failed - ${it.message}")
                    Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }.start()
    }

    private fun injectOnce() {
        save()
        val port = BridgeService.activePort.takeIf { it > 0 } ?: cfg.bridgePort
        Thread {
            val r = runCatching { Injector.inject(cfg.identityJson, port) }
            ui.post {
                val msg = if (r.isSuccess) "identity injected to 127.0.0.1:1716 (tcpPort=$port)"
                          else "inject failed: ${r.exceptionOrNull()?.message}"
                BridgeService.log(msg)
                toast(msg)
                refresh()
            }
        }.start()
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Already exempt"); return }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun refresh() {
        val running = BridgeService.isRunning
        val linked = running && BridgeService.linkUpNow
        // enabled && !cleanStop && not running == nobody asked it to stop
        val killed = !running && cfg.enabled && !cfg.cleanStop

        val (text, colour) = when {
            linked -> "●  CONNECTED" to Color.rgb(0x1B, 0x7F, 0x3B)
            running -> "○  CONNECTING…" to Color.rgb(0xB0, 0x6E, 0x00)
            killed -> "■  KILLED BY SYSTEM" to Color.rgb(0xB3, 0x26, 0x1E)
            else -> "○  STOPPED" to Color.DKGRAY
        }
        banner.text = text
        banner.setTextColor(colour)

        val pm = getSystemService(PowerManager::class.java)
        val exempt = runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
        val ident = cfg.identityJson

        status.text = buildString {
            append("mode      : ").append(cfg.mode)
            append("\ntarget    : ").append(cfg.host.ifBlank { "(not set)" }).append(":").append(cfg.remotePort)
            append("\nlisten    : 127.0.0.1:")
            append(if (running && BridgeService.activePort > 0) BridgeService.activePort.toString()
                   else cfg.bridgePort.toString() + " (preferred)")
            append("\nidentity  : ").append(Injector.deviceName(ident))
                .append(" [").append(cfg.identitySource).append("]")
            if (!cfg.isLearned() && cfg.host.isNotBlank()) {
                append("\n            placeholder; the computer's identity has not been learned yet")
            }
            if (cfg.mode == Config.MODE_TSNET) {
                // Status() is cached in Go and non-blocking - safe on the UI thread.
                append("\ntailnet   : ").append(runCatching { Tsbridge.status() }.getOrDefault("?"))
                val u = runCatching { Tsbridge.loginURL() }.getOrDefault("")
                if (u.isNotBlank()) append("\n            LOGIN NEEDED - tap Authenticate tailnet")
            }
            if (running) {
                append("\nstarted   : ").append(EventLog.clockOf(cfg.startedAt))
                    .append("  (").append(EventLog.ago(cfg.startedAt)).append(")")
            }
            append("\nlast beat : ").append(EventLog.clockOf(cfg.lastHeartbeat))
                .append("  (").append(EventLog.ago(cfg.lastHeartbeat)).append(")")
            if (killed) {
                append("\n\nThe service stopped without being asked to. It was last running at ")
                append(EventLog.clockOf(cfg.lastHeartbeat))
                append(", so Android or a task killer ended it.")
                if (!exempt) append("\nBattery optimization is on, which is the likely cause.")
            }
            append("\nbattery   : ").append(if (exempt) "exempt" else "NOT EXEMPT - Doze may stop the service")
        }

        toggle.text = if (running) "Stop bridge" else "Start bridge"
        logView.text = EventLog.recent().joinToString("\n") { "${it.stamp()}  ${it.msg}" }
    }

    private val ticker = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        BridgeService.onLog = null
        super.onDestroy()
    }
}
