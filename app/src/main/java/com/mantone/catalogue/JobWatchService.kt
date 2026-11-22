package com.mantone.catalogue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges

// Foreground service: keeps the live connection open so job notifications arrive even when the app is minimized or closed.
class JobWatchService : Service() {
    private val regs = mutableListOf<ListenerRegistration>()
    private var roles = listOf<String>()
    private var jobs = listOf<M>()

    override fun onBind(i: Intent?): IBinder? = null

    // Ongoing + delete-intent: if the user swipes it away, Android calls us back and we post it again.
    private fun watchNotif(): Notification {
        val del = PendingIntent.getForegroundService(this, 7, Intent(this, JobWatchService::class.java).setAction("RESHOW"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "watch").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Mantone Catalogue")
            .setContentText("Watching for new jobs").setOngoing(true).setOnlyAlertOnce(true).setDeleteIntent(del).build()
    }
    private fun repost() { getSystemService(NotificationManager::class.java).notify(77, watchNotif()) }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("watch", "Background sync", NotificationManager.IMPORTANCE_LOW))
        startForeground(77, watchNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (i?.action != "RESHOW") begin()
        return START_STICKY
    }

    private fun begin() {
        regs.forEach { it.remove() }; regs.clear()
        val uid = getSharedPreferences("s", MODE_PRIVATE).getString("u", "") ?: ""
        if (uid.isEmpty()) { stopSelf(); return }
        regs += db.collection("users").document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { s, _ ->
            if (s == null) return@addSnapshotListener
            if (s.exists()) { roles = rolesOf(s.data ?: mapOf()); check() }
            else if (!s.metadata.isFromCache) { getSharedPreferences("s", MODE_PRIVATE).edit().clear().apply(); stopSelf() }   // account deleted
        }
        regs += db.collection("jobs").addSnapshotListener { s, _ ->
            if (s != null) { jobs = s.documents.map { (it.data ?: mapOf()) + ("_id" to it.id) }; check() }
        }
    }

    // Notify once per event (new job step, job put on hold, job cancelled); remembered on the device so nothing repeats.
    private fun check() {
        repost()   // keep the "Watching" notification visible
        if (roles.isEmpty()) return
        val who = getSharedPreferences("s", MODE_PRIVATE).getString("n", "") ?: ""
        val store = getSharedPreferences("notified", MODE_PRIVATE)
        val k = "nt_$who"
        val done = (store.getStringSet(k, null) ?: setOf()).toMutableSet()
        val key = { j: M -> j.s("id") + ":" + j.n("cur") }
        val fresh = jobs.filter { mine(roles, it) && !it.b("hold") && key(it) !in done }
        if (fresh.size > 3) notifyUser(this, 1, "Jobs waiting", "You have ${fresh.size} new jobs waiting for you")
        else fresh.forEach { notifyUser(this, key(it).hashCode(), (if (urgent(it)) "🔥 Urgent job: " else "New job: ") + curProc(it), "#${it.s("id")} ${it.s("jobName")} — ${it.s("client")}") }
        done.addAll(fresh.map(key))
        // a job I am working on was put on hold / cancelled
        jobs.filter { working(roles, it) && !it.b("cancelled") && it.b("hold") && "H:${it.s("id")}:${it.n("holdAt")}" !in done }.forEach {
            notifyUser(this, ("H" + it.s("id")).hashCode(), "⏸ Job on hold", "#${it.s("id")} ${it.s("jobName")} is ON HOLD")
            done.add("H:${it.s("id")}:${it.n("holdAt")}")
        }
        jobs.filter { working(roles, it) && it.b("cancelled") && "C:${it.s("id")}" !in done }.forEach {
            notifyUser(this, ("C" + it.s("id")).hashCode(), "Job cancelled", "#${it.s("id")} ${it.s("jobName")} was cancelled")
            done.add("C:${it.s("id")}")
        }
        jobs.filter { j -> working(roles, j) && !j.b("cancelled") && j.n("qtyChangeAt") > 0 && j.n("qtyChangeAt") >= (stepsOf(j).getOrNull(j.n("cur").toInt())?.n("at") ?: 0L) && "Q:${j.s("id")}:${j.n("qtyChangeAt")}" !in done }.forEach {
            notifyUser(this, ("Q" + it.s("id")).hashCode(), "✏️ Quantity changed — #${it.s("id")} ${it.s("jobName")}", it.s("qtyChange"))
            done.add("Q:${it.s("id")}:${it.n("qtyChangeAt")}")
        }
        store.edit().putStringSet(k, done).apply()
        repost()   // job alerts must not push it out of the tray
    }

    override fun onDestroy() { regs.forEach { it.remove() }; super.onDestroy() }
}
