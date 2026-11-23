package com.mantone.catalogue

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import com.google.firebase.firestore.MetadataChanges
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.Query
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.ui.text.input.VisualTransformation
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.text.SimpleDateFormat
import java.util.*

typealias M = Map<String, Any?>
val db get() = FirebaseFirestore.getInstance()
fun M.s(k: String) = this[k]?.toString() ?: ""
fun M.n(k: String) = (this[k] as? Number)?.toLong() ?: 0L
fun M.b(k: String) = this[k] == true
fun now() = System.currentTimeMillis()
fun ts(t: Long) = if (t == 0L) "—" else SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(t))
val loading = mutableIntStateOf(0)   // >0 shows the top progress bar
fun <T> Task<T>.track(): Task<T> { loading.intValue++; return addOnCompleteListener { loading.intValue-- } }

var refreshTick by mutableIntStateOf(0)   // bump to re-attach all live listeners
var syncState by mutableStateOf("loading")   // loading | ok | failed  (drives the blocking refresh screen)
var syncId = 0
fun refresh() {
    val id = ++syncId
    syncState = "loading"; refreshTick++
    Updater.checked = false; Updater.check()   // login stays blocked until the update check finishes
    Handler(Looper.getMainLooper()).postDelayed({ if (id == syncId && syncState == "loading") syncState = "failed" }, 10000)
    db.collection("ping").document("ok").get(Source.SERVER)
        .addOnSuccessListener { if (id == syncId) syncState = "ok" }
        .addOnFailureListener { if (id == syncId) syncState = "failed" }
}
lateinit var appCtx: Context

// In-app updates (no store). The release script publishes version.json + the APK to GitHub Releases; the app reads version.json.
object Updater {
    var checked by mutableStateOf(false)      // first check finished (success or failure)
    var checking by mutableStateOf(false)
    var failed by mutableStateOf(false)       // could not reach the update server
    var latestCode by mutableLongStateOf(0L)
    var latestName by mutableStateOf("")
    var apkUrl by mutableStateOf("")
    var notes by mutableStateOf("")
    var downloading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var error by mutableStateOf("")
    fun installedCode(): Long = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).longVersionCode
    val required: Boolean get() = latestCode > installedCode() && apkUrl.isNotEmpty()

    fun check() {
        checking = true
        val h = Handler(Looper.getMainLooper())
        Thread {
            var res: JSONObject? = null
            try {
                val c = URL(BuildConfig.UPDATE_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode == 200) res = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } catch (_: Exception) { }
            val r = res
            h.post {
                if (r != null) { latestCode = r.optLong("versionCode"); latestName = r.optString("versionName"); apkUrl = r.optString("apkUrl"); notes = r.optString("notes"); failed = false }
                else failed = true   // cannot verify -> do not lock people out
                checking = false; checked = true
            }
        }.start()
    }

    fun install(ctx: Context) {
        if (downloading) return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(ctx, "Allow installs from this app, then tap again", Toast.LENGTH_LONG).show()
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        downloading = true; progress = 0; error = ""
        val h = Handler(Looper.getMainLooper())
        Thread {
            try {
                val f = File(ctx.getExternalFilesDir(null), "update.apk"); f.delete()
                val c = URL(apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000
                val total = c.contentLength.toLong()
                c.inputStream.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(16384); var done = 0L; var n = 0
                        while (inp.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n); done += n
                            if (total > 0) { val pct = (done * 100 / total).toInt(); h.post { progress = pct } }
                        }
                    }
                }
                h.post {
                    downloading = false
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            } catch (e: Exception) { h.post { downloading = false; error = "Download failed. Check your internet and try again." } }
        }.start()
    }
}

// Real Firebase accounts: username -> internal e-mail, password checked by Google. No password is ever stored or downloaded by the app.
val auth get() = FirebaseAuth.getInstance()
fun emailFor(name: String) = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), ".").trim('.') + "@mantone.app"
// Phone number = login ID (10-digit mobile, "+91"/spaces ignored). No SMS is sent; the number is simply the username.
fun normPhone(s: String): String { val d = s.filter { it.isDigit() }; return if (d.length == 12 && d.startsWith("91")) d.drop(2) else d }
fun phoneFilter(s: String) = s.filter { it.isDigit() || it == '+' || it == ' ' }.take(15)

// Password rules for new / changed passwords
fun pwError(p: String): String? = when {
    p.length < 8 -> "Password must be at least 8 characters"
    !p.any { it.isLetter() } || !p.any { it.isDigit() } -> "Password must contain letters and numbers"
    else -> null
}
fun changePassword(cur: String, new: String, cb: (String?) -> Unit) {
    val u = auth.currentUser; val email = u?.email
    if (u == null || email == null) { cb("Not signed in"); return }
    u.reauthenticate(EmailAuthProvider.getCredential(email, cur))
        .addOnSuccessListener { u.updatePassword(new).addOnSuccessListener { cb(null) }.addOnFailureListener { cb("Could not change password: ${it.message}") } }
        .addOnFailureListener { cb("Current password is incorrect") }
}
@Composable fun blueBtn() = ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.White, disabledContainerColor = Grey, disabledContentColor = Color.White)
@Composable fun orangeBtn() = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White, disabledContainerColor = Grey, disabledContentColor = Color.White)
@Composable fun DonePopup(text: String?, onClose: () -> Unit) {
    if (text != null) AlertDialog(onDismissRequest = onClose, title = { Text("Done") }, text = { Text(text) }, confirmButton = { Button(onClose, colors = blueBtn()) { Text("OK") } })
}
@Composable fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(value, { onChange(it.take(64)) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show", fontSize = 12.sp) } })
}
// Admin creates another person's account through a second, separate Firebase session so the admin stays logged in.
fun createAccount(name: String, phone: String, password: String, roles: List<String>, cb: (String?) -> Unit) {
    val app = try { FirebaseApp.getInstance("creator") } catch (e: IllegalStateException) { FirebaseApp.initializeApp(appCtx, FirebaseApp.getInstance().options, "creator") }
    val a2 = FirebaseAuth.getInstance(app)
    a2.createUserWithEmailAndPassword(emailFor(phone), password)
        .addOnSuccessListener { r ->
            val id = r.user?.uid ?: ""
            db.collection("users").document(id).set(mapOf("name" to name, "phone" to phone, "roles" to roles, "auth" to true))
                .addOnSuccessListener { a2.signOut(); cb(null) }
                .addOnFailureListener { r.user?.delete(); a2.signOut(); cb("Could not save the user: ${it.message}") }
        }.addOnFailureListener { e ->
            cb(if (e is FirebaseAuthUserCollisionException) "That phone number already has an account (or had one before). If it was deleted earlier, also remove it in Firebase console > Authentication." else "Failed: ${e.message}")
        }
}

var notifWarn by mutableStateOf(false)   // shown once per fresh launch when notifications are blocked
var darkMode by mutableStateOf(false)
var jobsReady by mutableStateOf(false)   // true after the first jobs snapshot (avoids notification spam on login)

fun notifyUser(ctx: Context, id: Int, title: String, text: String) {
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(android.app.NotificationChannel("jobs", "Job alerts", NotificationManager.IMPORTANCE_HIGH))
    val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
    nm.notify(id, Notification.Builder(ctx, "jobs").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
        .setContentIntent(pi).setAutoCancel(true).build())
}

val WORKER_ROLES = listOf("Printing", "Cutting", "Delivery", "Lamination", "Foil", "Spot UV", "Binding", "Packing")
val ROLES = listOf("Admin", "Supervisor", "Printing Manager") + WORKER_ROLES
val Navy = Color(0xFF1F3A5F); val Orange = Color(0xFFE67E22); val Green = Color(0xFF388E3C); val Grey = Color(0xFF757575); val Blue = Color(0xFF1E88E5); val Yellow = Color(0xFFFBC02D)
fun isAdmin(r: List<String>) = "Admin" in r
// A user can hold several roles. Old single-role users still work.
fun rolesOf(u: Map<String, Any?>): List<String> {
    val l = (u["roles"] as? List<*>)?.map { it.toString() } ?: listOf(u["role"]?.toString() ?: "")
    val legacy = u["roles"] == null   // the very first admin accounts were saved with the single role "Supervisor"
    return l.flatMap { when { it == "Lamination+Foil" -> listOf("Lamination", "Foil"); legacy && it == "Supervisor" -> listOf("Admin"); else -> listOf(it) } }.filter { it.isNotEmpty() }
}
fun dur(m: Long) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

@Suppress("UNCHECKED_CAST") fun stepsOf(j: M): List<M> = (j["steps"] as? List<M>) ?: listOf()
fun curProc(j: M) = stepsOf(j).getOrNull(j.n("cur").toInt())?.s("proc") ?: ""
// A job is visible to a role ONLY while it is that role's turn. Delivery role also does Coating.
// "working": the job's current step has been assigned (not a job still waiting on hold at creation) and belongs to one of the roles
fun working(roles: List<String>, j: M): Boolean {
    val st = stepsOf(j).getOrNull(j.n("cur").toInt()); val p = st?.s("proc") ?: ""
    return j.s("status") != "DELIVERED" && (st?.n("at") ?: 0L) > 0 && (p in roles || (p == "Coating" && "Delivery" in roles))
}
fun mine(roles: List<String>, j: M) = working(roles, j) && !j.b("cancelled")
// Activity log. Doc ids sort newest-first, so pages of 10 can be fetched lazily (with or without a user/job filter) without extra indexes.
fun logEvent(action: String, who: String, job: String = "", target: String = "", detail: String = "") {
    val tm = now()
    val id = "%019d".format(Long.MAX_VALUE - tm) + "-" + (1000..9999).random()
    db.collection("logs").document(id).set(mapOf("t" to tm, "by" to who, "action" to action, "job" to job, "target" to target, "detail" to detail))
}
fun editJob(j: M, name: String, client: String, qty: Long, photos: Long, who: String) {
    val up = mutableMapOf<String, Any>(); val ch = mutableListOf<String>(); val qc = mutableListOf<String>()
    if (name != j.s("jobName")) { up["jobName"] = name; ch += "Job Name: ${j.s("jobName")} → $name" }
    if (client != j.s("client")) { up["client"] = client; ch += "Client: ${j.s("client")} → $client" }
    if (qty != j.n("qty")) { up["qty"] = qty; qc += "Catalogue Qty: ${j.n("qty")} → $qty" }
    if (photos != j.n("photos")) { up["photos"] = photos; qc += "Photos Qty: ${j.n("photos")} → $photos" }
    if (up.isEmpty()) return
    if (qc.isNotEmpty()) { up["qtyChange"] = qc.joinToString(" • "); up["qtyChangeAt"] = now(); up["qtyChangeBy"] = who }   // workers on this job are notified of qty changes
    db.collection("jobs").document(j.s("_id")).update(up).track()
    logEvent("Edited job", who, j.s("id"), detail = (ch + qc).joinToString(" • "))
}
// Deleting moves the whole job document (progress, steps, hold, priority...) to "deletedJobs"; only admins can restore or purge it.
fun deleteJob(id: String, who: String) {
    val ref = db.collection("jobs").document(id); val tr = db.collection("deletedJobs").document(id)
    db.runTransaction { t ->
        val d = t.get(ref)
        if (d.exists()) { t.set(tr, d.data.orEmpty() + mapOf("deletedAt" to now(), "deletedBy" to who)); t.delete(ref) }
        Unit
    }.track()
    logEvent("Deleted job", who, id, detail = "Moved to Deleted Jobs")
}
fun restoreJob(id: String, who: String) {
    val ref = db.collection("jobs").document(id); val tr = db.collection("deletedJobs").document(id)
    db.runTransaction { t ->
        val d = t.get(tr)
        if (d.exists()) { t.set(ref, d.data.orEmpty() - "deletedAt" - "deletedBy"); t.delete(tr) }
        Unit
    }.track()
    logEvent("Restored job", who, id, detail = "Restored with all progress")
}
fun purgeJob(id: String, who: String) { db.collection("deletedJobs").document(id).delete().track(); logEvent("Deleted permanently", who, id) }
fun urgent(j: M) = j.s("priority") == "Urgent"
fun setPriority(id: String, p: String, who: String) { db.collection("jobs").document(id).update("priority", p).track(); logEvent("Changed priority", who, id, detail = "→ $p") }
fun jstat(j: M) = if (j.b("cancelled")) "CANCELLED" else if (j.b("hold")) "ON HOLD" else j.s("status")

@Composable fun col(name: String): List<M> {
    var l by remember { mutableStateOf(listOf<M>()) }
    DisposableEffect(name, refreshTick) {
        var first = true
        loading.intValue++
        val r = db.collection(name).addSnapshotListener { s, e ->
            if (first) { first = false; loading.intValue--; if (name == "jobs") jobsReady = true }
            if (s != null) l = s.documents.map { (it.data ?: mapOf()) + ("_id" to it.id) }
            else if (e != null) Handler(Looper.getMainLooper()).postDelayed({ refreshTick++ }, 5000)   // reconnect, keep old data
        }
        onDispose { r.remove(); if (first) loading.intValue-- }
    }
    return l
}

// ---------- workflow ----------
fun createJob(client: String, jobName: String, design: Int, qty: Long, photos: Long, lam: Boolean, foil: Boolean, spot: Boolean, coating: String, priority: String, hold: Boolean, who: String, done: (String?) -> Unit) {
    val ref = db.collection("counters").document("jobs")
    db.runTransaction { t -> val n = (t.get(ref).getLong("n") ?: 0L) + 1L; t.set(ref, mapOf("n" to n)); n }.track()
        .addOnFailureListener { done(it.message ?: "Could not reach server") }
        .addOnSuccessListener { n ->
            val id = n.toString()   // Job ID: 1, 2, 3 ...
            val f = mutableListOf("Printing", "Cutting")
            if (coating == "Full UV Coating") f += "Coating"
            if (lam) f += "Lamination"
            if (foil) f += "Foil"
            if (spot) f += "Spot UV"
            f += listOf("Binding", "Packing", "Delivery")
            val t = now()
            val steps = f.mapIndexed { i, p -> mapOf("proc" to p, "at" to (if (i == 0 && !hold) t else 0L), "done" to 0L, "by" to "") }
            db.collection("jobs").document(id).set(mapOf("id" to id, "client" to client, "jobName" to jobName, "design" to design.toLong(), "qty" to qty, "photos" to photos,
                "lam" to lam, "foil" to foil, "spot" to spot, "coating" to coating, "status" to "IN PRODUCTION", "priority" to priority, "hold" to hold, "holdAt" to (if (hold) t else 0L), "holdBy" to (if (hold) who else ""), "cancelled" to false, "cur" to 0L, "steps" to steps, "createdAt" to t))
            logEvent("Created job", who, id, detail = "$jobName • $client • Qty $qty" + if (hold) " • created on hold" else "")
            done(null)
        }
}

// Marks the current step done and hands the job to the next role. Transaction stops double-taps / duplicate completion.
@Suppress("UNCHECKED_CAST")
fun complete(id: String, expect: Long, who: String) {
    val ref = db.collection("jobs").document(id)
    db.runTransaction { t ->
        val d = t.get(ref)
        val cur = (d.getLong("cur") ?: 0L).toInt()
        if (cur.toLong() == expect && d.getBoolean("hold") != true && d.getBoolean("cancelled") != true) {
            val st = (d.get("steps") as List<Map<String, Any?>>).map { it.toMutableMap() }
            val tm = now()
            st[cur]["done"] = tm; st[cur]["by"] = who
            val upd = mutableMapOf<String, Any>("steps" to st)
            if (cur + 1 < st.size) {
                st[cur + 1]["at"] = tm
                upd["cur"] = (cur + 1).toLong()
                upd["status"] = if (st[cur + 1]["proc"] == "Delivery") "READY TO DISPATCH" else "IN PRODUCTION"   // automatic after Packing
            } else { upd["status"] = "DELIVERED"; upd["deliveredAt"] = tm }
            t.update(ref, upd)
        }
        Unit
    }.track()
}

fun holdJob(id: String, who: String) { db.collection("jobs").document(id).update(mapOf("hold" to true, "holdAt" to now(), "holdBy" to who)).track(); logEvent("Put on hold", who, id) }
fun cancelJob(id: String, who: String) { db.collection("jobs").document(id).update(mapOf("cancelled" to true, "cancelledAt" to now())).track(); logEvent("Cancelled job", who, id) }
// Resuming a job that was created on hold assigns it to Printing at that moment.
@Suppress("UNCHECKED_CAST")
fun resumeJob(id: String, who: String) {
    logEvent("Resumed job", who, id)
    val ref = db.collection("jobs").document(id)
    db.runTransaction { t ->
        val d = t.get(ref); val cur = (d.getLong("cur") ?: 0L).toInt()
        val st = (d.get("steps") as List<Map<String, Any?>>).map { it.toMutableMap() }
        if (cur < st.size && ((st[cur]["at"] as? Number)?.toLong() ?: 0L) == 0L) st[cur]["at"] = now()
        t.update(ref, mapOf("hold" to false, "steps" to st))
        Unit
    }.track()
}

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume(); refresh()
        if (!(getSharedPreferences("s", MODE_PRIVATE).getString("u", "") ?: "").isEmpty()) startForegroundService(Intent(this, JobWatchService::class.java))
    }   // fresh live connection every time the app comes to the front
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appCtx = applicationContext
        val sp = getSharedPreferences("s", MODE_PRIVATE)
        val nm = getSystemService(NotificationManager::class.java)
        if (savedInstanceState == null) {   // fresh launch only (not when the app is just brought back from minimized, or rotated)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notifWarn = !granted || !nm.areNotificationsEnabled() }.launch(Manifest.permission.POST_NOTIFICATIONS)
            else notifWarn = !nm.areNotificationsEnabled()
        }
        darkMode = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("dark", false)
        setContent { MaterialTheme(colorScheme = if (darkMode) darkColorScheme(primary = Color(0xFF90CAF9), secondary = Orange, background = Color(0xFF121417), surface = Color(0xFF1E2227))
            else lightColorScheme(primary = Navy, secondary = Orange, background = Color(0xFFF4F6F9), surface = Color.White)) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().then(if (syncState != "ok") Modifier.blur(16.dp) else Modifier)) { App(sp) }; NotifBanner(); SyncOverlay(); UpdateGate() } } } }
    }
}

@Composable fun Item(title: String, body: String = "", accent: Color? = null, btns: List<Pair<String, () -> Unit>> = listOf(), btnEnabled: Boolean = true, rich: AnnotatedString? = null) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (accent != null) Box(Modifier.width(8.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(12.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (rich != null) Text(rich, color = MaterialTheme.colorScheme.onSurfaceVariant) else if (body.isNotEmpty()) Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                btns.forEach { (l, f) ->
                    Button(f, Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp), shape = RoundedCornerShape(12.dp), enabled = btnEnabled, colors = blueBtn()) { Text(l, fontWeight = FontWeight.Bold, color = Color.White) }
                }
            }
        }
    }
}

@Composable fun RowScope.Stat(l: String, v: Int, c: Color, onClick: () -> Unit) {
    Card(Modifier.weight(1f).padding(4.dp).clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = c)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$v", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(l, color = Color.White)
        }
    }
}

@Composable fun Login(onOk: (String, String, String, String) -> Unit) {
    var n by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }; var err by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Navy).padding(horizontal = 24.dp + sidePad(480.dp), vertical = 24.dp), verticalArrangement = Arrangement.Center) {
        Text("MANTONE", style = MaterialTheme.typography.headlineLarge, color = Color.White)
        Text("Catalogue Flow", style = MaterialTheme.typography.titleLarge, color = Color(0xFFFFB74D))
        Spacer(Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(n, { n = phoneFilter(it) }, label = { Text("Phone number") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                PasswordField(p, "Password") { p = it }
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
                Button({
                    if (n.isBlank()) err = "Enter your phone number"
                    else if (normPhone(n).length != 10) err = "Enter your 10-digit phone number"
                    else if (p.isEmpty()) err = "Enter your password"
                    else {
                        busy = true; err = ""
                        var finished = false
                        val h = Handler(Looper.getMainLooper())
                        h.postDelayed({ if (!finished) { finished = true; busy = false; err = "Connection timed out. Check your internet and try again." } }, 12000)
                        auth.signInWithEmailAndPassword(emailFor(normPhone(n)), p)
                            .addOnSuccessListener { r ->
                                if (finished) return@addOnSuccessListener
                                val id = r.user?.uid ?: ""
                                db.collection("users").document(id).get(Source.SERVER)
                                    .addOnSuccessListener { d ->
                                        if (finished) return@addOnSuccessListener
                                        finished = true; h.removeCallbacksAndMessages(null)
                                        if (!d.exists()) { busy = false; auth.signOut(); err = "This account has been disabled. Contact your admin." }
                                        else {
                                            // ONE session per account: writing a new sessionId signs this user out of every other device
                                            val sid = UUID.randomUUID().toString()
                                            db.collection("users").document(id).update(mapOf("sessionId" to sid, "sessionAt" to now(), "device" to Build.MODEL))
                                                .addOnSuccessListener { busy = false; onOk(d.getString("name") ?: n.trim(), rolesOf(d.data ?: mapOf()).joinToString(","), id, sid) }
                                                .addOnFailureListener { busy = false; auth.signOut(); err = "Could not start your session. Try again." }
                                        }
                                    }.addOnFailureListener {
                                        if (finished) return@addOnFailureListener
                                        finished = true; h.removeCallbacksAndMessages(null); busy = false; auth.signOut()
                                        err = "This account has been disabled. Contact your admin."
                                    }
                            }.addOnFailureListener { e ->
                                if (finished) return@addOnFailureListener
                                finished = true; h.removeCallbacksAndMessages(null); busy = false
                                err = when (e) {
                                    is FirebaseAuthInvalidCredentialsException, is FirebaseAuthInvalidUserException -> "Wrong phone number or password"
                                    is FirebaseTooManyRequestsException -> "Too many attempts. Wait a few minutes and try again."
                                    is FirebaseNetworkException -> "Could not connect to the server. Check your internet."
                                    else -> "Login failed. Check your internet and try again."
                                }
                            }
                    }
                }, Modifier.fillMaxWidth().height(56.dp).padding(top = 12.dp), shape = RoundedCornerShape(12.dp), enabled = !busy,
                    colors = orangeBtn()) {
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp) else Text("LOGIN")
                }
            }
        }
    }
}

@Composable fun App(sp: SharedPreferences) {
    var name by remember { mutableStateOf(sp.getString("n", "") ?: "") }
    var role by remember { mutableStateOf(sp.getString("r", "") ?: "") }
    var uid by remember { mutableStateOf(sp.getString("u", "") ?: "") }
    var page by remember { mutableStateOf("home") }
    val ctx = LocalContext.current
    fun logout(msg: String? = null) {
        sp.edit().clear().apply(); role = ""; name = ""; uid = ""; page = "home"; auth.signOut()
        ctx.stopService(Intent(ctx, JobWatchService::class.java)); ctx.getSystemService(NotificationManager::class.java).cancel(77)
        if (msg != null) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(uid) {
        if (uid.isEmpty()) { if (role.isNotEmpty()) logout() }   // old session without an account id: sign in again
        else if (auth.currentUser == null) logout()
        else ctx.startForegroundService(Intent(ctx, JobWatchService::class.java))
    }
    DisposableEffect(uid) {
        if (uid.isEmpty()) return@DisposableEffect onDispose { }
        val r = db.collection("users").document(uid).addSnapshotListener(MetadataChanges.INCLUDE) { s, e ->
            if (e != null && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) { logout("Your account was removed. You have been logged out."); return@addSnapshotListener }
            if (s == null) return@addSnapshotListener
            if (s.exists()) {
                val ds = s.getString("sessionId"); val mine = sp.getString("sid", "") ?: ""
                if (ds != null && mine.isNotEmpty() && ds != mine) { logout("You were signed out because this account signed in on another device."); return@addSnapshotListener }
                val nr = rolesOf(s.data ?: mapOf()).joinToString(",")
                if (nr.isNotEmpty() && nr != role) { role = nr; sp.edit().putString("r", nr).apply() }   // new roles apply instantly
            } else if (!s.metadata.isFromCache) logout("Your account was removed. You have been logged out.")
        }
        onDispose { r.remove() }
    }
    val roles = role.split(",").flatMap { if (it == "Lamination+Foil") listOf("Lamination", "Foil") else listOf(it) }
    if (role.isEmpty()) Login { n, r, u, sid -> sp.edit().putString("n", n).putString("r", r).putString("u", u).putString("sid", sid).apply(); name = n; role = r; uid = u }
    else {
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val manager = roles.any { it in listOf("Admin", "Supervisor", "Printing Manager") }
        val worker = roles.any { it in WORKER_ROLES }
        val adm = isAdmin(roles)
        val pg = if ((!worker && page == "history") || (!adm && (page == "logs" || page == "trash"))) "home" else page
        BackHandler(pg != "home") { page = "home" }
        val jobs = col("jobs")
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            Surface(Modifier.fillMaxHeight().width((LocalConfiguration.current.screenWidthDp * 0.45f).coerceIn(170f, 340f).dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding()) {
                    Text("Mantone Catalogue", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                    if (worker) NavigationDrawerItem(label = { Text("📜  Job History") }, selected = pg == "history", modifier = Modifier.padding(horizontal = 12.dp),
                        onClick = { page = "history"; scope.launch { drawer.close() } })
                    if (adm) {
                        NavigationDrawerItem(label = { Text("🧾  Activity Logs") }, selected = pg == "logs", modifier = Modifier.padding(horizontal = 12.dp), onClick = { page = "logs"; scope.launch { drawer.close() } })
                        NavigationDrawerItem(label = { Text("🗑️  Deleted Jobs") }, selected = pg == "trash", modifier = Modifier.padding(horizontal = 12.dp), onClick = { page = "trash"; scope.launch { drawer.close() } })
                    }
                    NavigationDrawerItem(label = { Text("⚙️  Settings") }, selected = pg == "settings", modifier = Modifier.padding(horizontal = 12.dp),
                        onClick = { page = "settings"; scope.launch { drawer.close() } })
                    NavigationDrawerItem(label = { Text("ℹ️  About") }, selected = pg == "about", modifier = Modifier.padding(horizontal = 12.dp), onClick = { page = "about"; scope.launch { drawer.close() } })
                    Spacer(Modifier.weight(1f))
                    NavigationDrawerItem(label = { Text("🚪  Logout") }, selected = false, modifier = Modifier.padding(12.dp),
                        onClick = { scope.launch { drawer.close() }; logout() })
                }
            }
        }) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().background(Navy).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ if (pg == "home") { scope.launch { drawer.open() } } else { page = "home" } }) { Text(if (pg == "home") "☰" else "←", color = Color.White, style = MaterialTheme.typography.titleLarge) }
                    Text("${roles.joinToString(" + ")} • $name", color = Color.White, modifier = Modifier.weight(1f))
                    TextButton({ refresh() }, Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("⟳", fontSize = 30.sp, color = Color.White) }
                    Spacer(Modifier.width(4.dp))
                }
                if (loading.intValue > 0) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Orange)
                Box(Modifier.weight(1f).padding(horizontal = 12.dp + sidePad(640.dp))) {
                    when (pg) {
                        "settings" -> SettingsScreen()
                        "about" -> AboutScreen()
                        "history" -> History(jobs, name)
                        "logs" -> Logs()
                        "trash" -> Trash(name)
                        else -> if (manager) Manage(jobs, roles, name) else Worker(roles, jobs, name)
                    }
                }
            }
        }
    }
}

@Composable fun SettingsScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val notifOn = remember(refreshTick, tick) { ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    val notifSettings = { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) notifWarn = false else notifSettings(); tick++ }
    var cur by remember { mutableStateOf("") }; var np by remember { mutableStateOf("") }; var cp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf("") }; var ok by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("SETTINGS", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🌙  Dark mode", Modifier.weight(1f))
                Switch(darkMode, { darkMode = it; ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("dark", it).apply() })
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🔔  Job notifications")
                    Text(if (notifOn) "Enabled" else "Disabled — you won't get job alerts", color = if (notifOn) Green else MaterialTheme.colorScheme.error)
                }
                if (!notifOn) Button({ if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) else notifSettings() }, colors = orangeBtn()) { Text("ENABLE") }
            }
        }
        Text("CHANGE PASSWORD", Modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
        PasswordField(cur, "Current password") { cur = it }
        Spacer(Modifier.height(8.dp)); PasswordField(np, "New password (min 8, letters + numbers)") { np = it }
        Spacer(Modifier.height(8.dp)); PasswordField(cp, "Confirm new password") { cp = it }
        if (msg.isNotEmpty()) Text(msg, Modifier.padding(top = 8.dp), color = if (ok) Green else MaterialTheme.colorScheme.error)
        Button({
            val e = pwError(np)
            when {
                cur.isEmpty() -> { ok = false; msg = "Enter your current password" }
                e != null -> { ok = false; msg = e }
                np != cp -> { ok = false; msg = "Passwords don't match" }
                np == cur -> { ok = false; msg = "The new password must be different" }
                else -> { busy = true; msg = ""; changePassword(cur, np) { r -> busy = false; if (r == null) { ok = true; msg = "Password changed ✅"; cur = ""; np = ""; cp = "" } else { ok = false; msg = r } } }
            }
        }, Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp), enabled = !busy, shape = RoundedCornerShape(12.dp), colors = blueBtn()) {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp) else Text("CHANGE PASSWORD")
        }
    }
}

// Every step this user completed: job, client, when they received it, when they finished it
@Composable fun History(jobs: List<M>, who: String) {
    val rows = jobs.flatMap { j -> stepsOf(j).filter { it.n("done") > 0 && it.s("by") == who }.map { j to it } }.sortedByDescending { it.second.n("done") }
    if (rows.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No completed jobs yet", style = MaterialTheme.typography.titleMedium, color = Color.Gray, textAlign = TextAlign.Center)
    } else LazyColumn {
        item { Text("Completed by you: ${rows.size}", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }
        items(rows) { (j, s) ->
            Item("#${j.s("id")} ${j.s("jobName")}", "Client: ${j.s("client")}\nStep: ${s.s("proc")}\nReceived: ${ts(s.n("at"))}\nCompleted: ${ts(s.n("done"))}\nTime taken: ${dur((s.n("done") - s.n("at")) / 60000)}", Green)
        }
    }
}

@Composable fun sidePad(max: Dp): Dp = ((LocalConfiguration.current.screenWidthDp.dp - max) / 2).coerceAtLeast(0.dp)

fun statusColor(s: String) = when (s) { "READY TO DISPATCH" -> Green; "DELIVERED" -> Grey; "ON HOLD" -> Yellow; "CANCELLED" -> Color(0xFFD32F2F); else -> Blue }

// Admin card: only Job ID + Client. Tap = completed steps. Tap a step = its details.
@Composable fun AdminJob(j: M, roles: List<String>, who: String) {
    var open by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<Int?>(null) }
    var confirmDel by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var prioDlg by remember { mutableStateOf(false) }
    var editDlg by remember { mutableStateOf(false) }
    if (editDlg) EditJobDialog(j, "Admin" in roles, who) { editDlg = false }
    val canHC = ("Admin" in roles || "Printing Manager" in roles) && j.s("status") != "DELIVERED" && !j.b("cancelled")
    val canDel = "Admin" in roles
    if (confirmCancel) AlertDialog(onDismissRequest = { confirmCancel = false }, title = { Text("Cancel job #${j.s("id")}?") },
        text = { Text("Workers will no longer see this job. This cannot be undone.") },
        confirmButton = { Button({ confirmCancel = false; cancelJob(j.s("_id"), who) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("CANCEL JOB") } },
        dismissButton = { TextButton({ confirmCancel = false }) { Text("Keep job") } })
    if (prioDlg) PickDialog("Job Priority", listOf("Urgent", "Normal"), 1, { setPriority(j.s("_id"), it, who); prioDlg = false }) { prioDlg = false }
    if (confirmDel) AlertDialog(onDismissRequest = { confirmDel = false }, title = { Text("Delete job #${j.s("id")}?") },
        text = { Text("The job disappears for everyone, including workers who are on it. It moves to Deleted Jobs, where you can restore it with all its progress or delete it permanently.") },
        confirmButton = { Button({ confirmDel = false; deleteJob(j.s("_id"), who) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("DELETE") } },
        dismissButton = { TextButton({ confirmDel = false }) { Text("Cancel") } })
    val opts = listOfNotNull(if (j.b("lam")) "Lamination" else null, if (j.b("foil")) "Foil" else null, if (j.b("spot")) "Spot UV" else null,
        if (j.s("coating") != "None" && j.s("coating").isNotEmpty()) j.s("coating") else null).ifEmpty { listOf("No finishing") }.joinToString(" • ")
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { open = !open; sel = null }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(8.dp).fillMaxHeight().background(statusColor(jstat(j))))
            Column(Modifier.padding(12.dp).weight(1f)) {
                Text((if (urgent(j)) "🔥 " else "") + "#${j.s("id")}  •  ${j.s("client")}", style = MaterialTheme.typography.titleMedium)
                Text(j.s("jobName"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (open) {
                    Text("Design ${j.s("design")} | Qty ${j.n("qty")} | Photos ${j.n("photos")}\n$opts", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(jstat(j) + if (jstat(j) !in listOf("DELIVERED", "CANCELLED")) " — now at ${curProc(j)}" else "", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall)
                    if (j.b("hold") && !j.b("cancelled")) Text("On hold by ${j.s("holdBy").ifEmpty { "—" }} • ${ts(j.n("holdAt"))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Priority: ${j.s("priority").ifEmpty { "Normal" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Created: ${ts(j.n("createdAt"))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Completed steps (tap for details):", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
                    val done = stepsOf(j).withIndex().filter { it.value.n("done") > 0 }
                    if (done.isEmpty()) Text("None yet", color = Color.Gray)
                    done.forEach { (i, s) ->
                        Text("✅ ${s.s("proc")}", Modifier.fillMaxWidth().clickable { sel = if (sel == i) null else i }.padding(vertical = 10.dp))
                        if (sel == i) Text("Assigned: ${ts(s.n("at"))}\nCompleted: ${ts(s.n("done"))}\nCompleted by: ${s.s("by")}\nTime taken: ${dur((s.n("done") - s.n("at")) / 60000)}",
                            Modifier.padding(start = 24.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Pending steps:", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
                    val pend = stepsOf(j).withIndex().filter { it.value.n("done") == 0L }
                    if (pend.isEmpty()) Text("None — all steps done", color = Color.Gray)
                    pend.forEach { (i, s) ->
                        val now = i == j.n("cur").toInt()
                        Text((if (now) "🟡 " else "⬜ ") + s.s("proc") + (if (now) (if (s.n("at") == 0L) " — not started (on hold)" else " — waiting since ${ts(s.n("at"))}") else ""), Modifier.padding(vertical = 4.dp))
                    }
                    if (canHC) {
                        OutlinedButton({ editDlg = true }, Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(12.dp)) { Text("✏️ EDIT JOB") }
                        OutlinedButton({ prioDlg = true }, Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(12.dp)) { Text("PRIORITY: ${j.s("priority").ifEmpty { "Normal" }}  (tap to change)") }
                        Button({ if (j.b("hold")) resumeJob(j.s("_id"), who) else holdJob(j.s("_id"), who) }, Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Yellow, contentColor = Color.Black)) { Text(if (j.b("hold")) "▶ RESUME JOB" else "⏸ PUT ON HOLD") }
                        OutlinedButton({ confirmCancel = true }, Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(12.dp)) { Text("CANCEL JOB") }
                    }
                    if (canDel) Button({ confirmDel = true }, Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("DELETE JOB") }
                }
            }
        }
    }
}

@Composable fun Manage(jobs: List<M>, roles: List<String>, who: String) {
    val isAdm = "Admin" in roles
    val canCreate = isAdm || "Printing Manager" in roles
    val worker = roles.any { it in WORKER_ROLES }
    val tabs = buildList { add("📊" to "Home"); add("📋" to "Jobs"); if (worker) add("🛠" to "My Tasks"); if (canCreate) add("➕" to "New"); if (isAdm) add("👥" to "Users") }
    var sel by remember { mutableStateOf("Home") }
    val tab = if (tabs.any { it.second == sel }) sel else "Home"
    var filter by remember { mutableStateOf<String?>(null) }
    val sorted = jobs.sortedByDescending { it.n("createdAt") }
    val by = { s: String -> sorted.filter { jstat(it) == s } }
    val empty = @Composable { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No Jobs Available", style = MaterialTheme.typography.titleMedium, color = Color.Gray, textAlign = TextAlign.Center) } }
    BackHandler(filter != null) { filter = null }
    Column {
        Box(Modifier.weight(1f)) {
            when (tab) {
                "Home" -> if (filter == null) LazyColumn {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Row { Stat("All jobs", sorted.size, Navy) { filter = "All jobs" }; Stat("In production", by("IN PRODUCTION").size, Blue) { filter = "IN PRODUCTION" } }
                        Row { Stat("Ready to dispatch", by("READY TO DISPATCH").size, Green) { filter = "READY TO DISPATCH" }; Stat("Delivered", by("DELIVERED").size, Grey) { filter = "DELIVERED" } }
                        Row { Stat("On hold", by("ON HOLD").size, Yellow) { filter = "ON HOLD" }; Stat("Cancelled", by("CANCELLED").size, Color(0xFFD32F2F)) { filter = "CANCELLED" } }
                        Text("Tap a tile to see its jobs", Modifier.fillMaxWidth().padding(top = 12.dp), color = Color.Gray, textAlign = TextAlign.Center)
                    }
                } else {
                    val f = filter ?: ""
                    val list = if (f == "All jobs") sorted else by(f)
                    LazyColumn {
                        item { Text("${f.lowercase().replaceFirstChar { it.uppercase() }} • ${list.size}", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium) }
                        if (list.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                            Text("No Jobs Available", style = MaterialTheme.typography.titleMedium, color = Color.Gray, textAlign = TextAlign.Center) } }
                        items(list) { AdminJob(it, roles, who) }
                    }
                }
                "Jobs" -> if (sorted.isEmpty()) empty() else LazyColumn { items(sorted) { AdminJob(it, roles, who) } }
                "My Tasks" -> Worker(roles, jobs, who)
                "New" -> NewJob(who)
                else -> Users(who)
            }
        }
        NavigationBar { tabs.forEach { (e, l) -> NavigationBarItem(tab == l, { sel = l; filter = null }, { Text(e) }, label = { Text(l) }) } }
    }
}

// Worker: sees ONLY jobs whose current step belongs to their role
@Composable fun WorkCard(j: M, who: String) {
    val hold = j.b("hold")
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Confirm ${curProc(j)} completed?") },
        text = { Text("Job #${j.s("id")} ${j.s("jobName")} will move to the next step. This can't be undone.") },
        confirmButton = { Button({ confirm = false; complete(j.s("id"), j.n("cur"), who) }, colors = blueBtn()) { Text("YES, COMPLETED", color = Color.White, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
    val opts = listOfNotNull(if (j.b("lam")) "Lamination" else null, if (j.b("foil")) "Foil" else null, if (j.b("spot")) "Spot UV" else null,
        if (j.s("coating") != "None" && j.s("coating").isNotEmpty()) j.s("coating") else null).joinToString(" • ")
    val st = stepsOf(j)[j.n("cur").toInt()]
    val rich = buildAnnotatedString {
        if (hold) append("ON HOLD by ${j.s("holdBy").ifEmpty { "—" }} • ${ts(j.n("holdAt"))}\n")
        append("Your Job: "); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(curProc(j)) }
        append("\n${j.s("client")}\nDesign ${j.s("design")} | Qty ${j.n("qty")} | Photos ${j.n("photos")}")
        if (opts.isNotEmpty()) append("\n$opts")
        append("\nReceived: ${ts(st.n("at"))}")
    }
    Item((if (hold) "⏸ " else "") + (if (urgent(j)) "🔥 " else "") + "#${j.s("id")} ${j.s("jobName")}", "",
        if (urgent(j) && !hold) Orange else statusColor(jstat(j)),
        listOf((if (hold) "JOB ON HOLD" else "${curProc(j)} Completed") to { confirm = true }), !hold, rich)
}

// Worker: sees ONLY jobs whose current step belongs to their role. Sections appear only when they have jobs.
@Composable fun Worker(roles: List<String>, jobs: List<M>, who: String) {
    val list = jobs.filter { mine(roles, it) }.sortedBy { stepsOf(it).getOrNull(it.n("cur").toInt())?.n("at") ?: 0L }
    val urg = list.filter { !it.b("hold") && urgent(it) }; val active = list.filter { !it.b("hold") && !urgent(it) }; val held = list.filter { it.b("hold") }
    if (list.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No Jobs Available For You", style = MaterialTheme.typography.titleMedium, color = Color.Gray, textAlign = TextAlign.Center)
    } else LazyColumn {
        if (urg.isNotEmpty()) { item { Text("🔥 Urgent Jobs • ${urg.size}", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }; items(urg) { WorkCard(it, who) } }
        if (active.isNotEmpty()) { item { Text("Active Jobs • ${active.size}", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }; items(active) { WorkCard(it, who) } }
        if (held.isNotEmpty()) { item { Text("On Hold • ${held.size}", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }; items(held) { WorkCard(it, who) } }
    }
}

@Composable fun NewJob(who: String) {
    var client by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var qty by remember { mutableStateOf("") }; var photos by remember { mutableStateOf("") }
    var design by remember { mutableStateOf<Int?>(null) }; var dMenu by remember { mutableStateOf(false) }
    var coating by remember { mutableStateOf("None") }; var cMenu by remember { mutableStateOf(false) }
    var lam by remember { mutableStateOf(false) }; var foil by remember { mutableStateOf(false) }; var spot by remember { mutableStateOf(false) }; var onHold by remember { mutableStateOf(false) }
    var priority by remember { mutableStateOf("Normal") }; var pMenu by remember { mutableStateOf(false) }
    var popup by remember { mutableStateOf<String?>(null) }
    DonePopup(popup) { popup = null }
    var busy by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf("") }; var ok by remember { mutableStateOf(false) }
    LazyColumn {
        item {
            Text("NEW JOB", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
            Text("Job ID is assigned automatically (1, 2, 3…)", Modifier.padding(bottom = 8.dp), color = Color.Gray)
            LimitedField(client, "Client Name") { client = it }
            LimitedField(name, "Job Name (e.g. Books Vol 24)") { name = it }
            OutlinedTextField(qty, { qty = it.filter { c -> c.isDigit() }.take(9) }, label = { Text("Catalogue Qty") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(photos, { photos = it.filter { c -> c.isDigit() }.take(9) }, label = { Text("Photos Qty") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Box(Modifier.padding(top = 8.dp)) {
                OutlinedButton({ dMenu = true }, Modifier.fillMaxWidth().height(56.dp)) { Text(if (design == null) "Select Design (1–24)" else "Design: $design") }
                if (dMenu) PickDialog("Select Design", (1..24).map { "$it" }, if (LocalConfiguration.current.screenWidthDp >= 600) 6 else 4, { design = it.toInt(); dMenu = false }) { dMenu = false }
            }
            Box(Modifier.padding(vertical = 8.dp)) {
                OutlinedButton({ cMenu = true }, Modifier.fillMaxWidth().height(56.dp)) { Text("Coating: $coating") }
                if (cMenu) PickDialog("Select Coating", listOf("None", "Base Coating", "Full UV Coating"), 1, { coating = it; cMenu = false }) { cMenu = false }
            }
            Box(Modifier.padding(bottom = 8.dp)) {
                OutlinedButton({ pMenu = true }, Modifier.fillMaxWidth().height(56.dp)) { Text("Priority: $priority") }
                if (pMenu) PickDialog("Select Priority", listOf("Urgent", "Normal"), 1, { priority = it; pMenu = false }) { pMenu = false }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(lam, { lam = it }); Text("  Lamination") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(foil, { foil = it }); Text("  Foil") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(spot, { spot = it }); Text("  SPOT UV") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(onHold, { onHold = it }); Text("  Create on hold (won't reach Printing)") }
            if (msg.isNotEmpty()) Text(msg, color = if (ok) Green else MaterialTheme.colorScheme.error)
            Button({
                val q = qty.toLongOrNull() ?: 0L
                val d = design
                when {
                    client.isBlank() -> { ok = false; msg = "Enter the client name" }
                    name.isBlank() -> { ok = false; msg = "Enter the job name" }
                    q <= 0 -> { ok = false; msg = "Enter a quantity greater than 0" }
                    (photos.toLongOrNull() ?: 0L) <= 0 -> { ok = false; msg = "Photos quantity must be greater than 0" }
                    d == null -> { ok = false; msg = "Select a design" }
                    else -> {
                        busy = true; msg = ""
                        createJob(client.trim(), name.trim(), d, q, photos.toLongOrNull() ?: 0L, lam, foil, spot, coating, priority, onHold, who) { err ->
                            busy = false
                            if (err == null) { msg = ""; popup = if (onHold) "Job created ON HOLD ✅" else "Job created ✅ — sent to Printing"; client = ""; name = ""; qty = ""; photos = ""; design = null; coating = "None"; lam = false; foil = false; spot = false; onHold = false; priority = "Normal" }
                            else { ok = false; msg = "Failed: $err" }
                        }
                    }
                }
            }, Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp), enabled = !busy, shape = RoundedCornerShape(12.dp), colors = orangeBtn()) {
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp) else Text("CREATE JOB")
            }
        }
    }
}

@Composable fun LimitedField(value: String, label: String, max: Int = 50, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value, { onChange(it.take(max)) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { Spacer(Modifier.width(44.dp)) })
        Text("${value.length}/$max", Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 5.dp), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun PickDialog(title: String, options: List<String>, columns: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                options.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { o -> OutlinedButton({ onPick(o) }, Modifier.weight(1f).padding(3.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 10.dp)) { Text(o, maxLines = 1, softWrap = false) } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        })
}

@Composable fun RoleChecklist(sel: Set<String>, onChange: (Set<String>) -> Unit) {
    ROLES.forEach { r ->
        val flip = { onChange(if (r in sel) sel - r else sel + r) }
        Row(Modifier.fillMaxWidth().clickable { flip() }, verticalAlignment = Alignment.CenterVertically) { Checkbox(r in sel, { flip() }); Text(r) }
    }
}

@Composable fun UserCard(u: M, who: String, onEdit: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete ${u.s("name")}?") },
        text = { Text("They are logged out immediately and can no longer sign in. To reuse this phone number later, also delete the account in Firebase console > Authentication.") },
        confirmButton = { Button({ confirm = false; db.collection("users").document(u.s("_id")).delete(); logEvent("Deleted user", who, target = u.s("name")) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("DELETE") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { open = !open }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("${u.s("name")} — ${rolesOf(u).joinToString(" + ")}", style = MaterialTheme.typography.titleMedium)
            if (u.s("phone").isNotEmpty()) Text("📞 ${u.s("phone")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (open) {
                if (u.s("name") == "admin") Text("Main admin account (locked)", color = Color.Gray)
                else {
                    Button(onEdit, Modifier.fillMaxWidth().height(52.dp).padding(top = 8.dp), shape = RoundedCornerShape(12.dp), colors = orangeBtn()) { Text("EDIT ROLES") }
                    OutlinedButton({ confirm = true }, Modifier.fillMaxWidth().height(52.dp).padding(top = 8.dp), shape = RoundedCornerShape(12.dp)) { Text("DELETE USER") }
                }
            }
        }
    }
}

@Composable fun Users(who: String) {
    val users = col("users")
    var editing by remember { mutableStateOf<M?>(null) }
    var editSel by remember { mutableStateOf(setOf<String>()) }
    val uctx = LocalContext.current
    editing?.let { e ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Roles for ${e.s("name")}") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { RoleChecklist(editSel) { editSel = it } } },
            confirmButton = { Button({
                if (editSel.isEmpty()) Toast.makeText(uctx, "Select at least one role", Toast.LENGTH_SHORT).show()
                else db.collection("users").document(e.s("_id")).update("roles", editSel.toList()).track()
                    .addOnSuccessListener { logEvent("Changed user roles", who, target = e.s("name"), detail = rolesOf(e).joinToString(", ") + " → " + ROLES.filter { it in editSel }.joinToString(", ")); editing = null }
                    .addOnFailureListener { Toast.makeText(uctx, "Failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }) { Text("SAVE") } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancel") } })
    }
    var n by remember { mutableStateOf("") }; var ph by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }; var c by remember { mutableStateOf("") }; var sel by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf("") }; var ok by remember { mutableStateOf(false) }
    var confirmAdd by remember { mutableStateOf(false) }
    var popup by remember { mutableStateOf<String?>(null) }
    var sub by remember { mutableStateOf(0) }
    DonePopup(popup) { popup = null }
    var rolesDlg by remember { mutableStateOf(false) }
    if (rolesDlg) AlertDialog(onDismissRequest = { rolesDlg = false }, title = { Text("Select Roles") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { RoleChecklist(sel) { sel = it } } },
        confirmButton = { Button({ rolesDlg = false }) { Text("DONE") } })
    if (confirmAdd) AlertDialog(onDismissRequest = { confirmAdd = false }, title = { Text("Confirm new user") },
        text = { Column { Text("Name: ${n.trim()}"); Text("Phone: ${normPhone(ph)}"); Text("Roles being given:", Modifier.padding(top = 8.dp)); ROLES.filter { it in sel }.forEach { Text("• $it") } } },
        confirmButton = { Button({
            confirmAdd = false; busy = true; msg = ""
            var fin = false
            val h = Handler(Looper.getMainLooper())
            h.postDelayed({ if (!fin) { fin = true; busy = false; ok = false; msg = "No response from the server. The user is NOT saved to the cloud until you are online." } }, 12000)
            createAccount(n.trim(), normPhone(ph), p, ROLES.filter { it in sel }) { e ->
                fin = true; h.removeCallbacksAndMessages(null); busy = false
                if (e == null) { logEvent("Created user", who, target = n.trim(), detail = "Roles: " + ROLES.filter { it in sel }.joinToString(", ")); msg = ""; popup = "User created ✅\n\nShare the password with them now. It can't be viewed later."; n = ""; ph = ""; p = ""; c = ""; sel = setOf() }
                else { ok = false; msg = e }
            }
        }) { Text("CREATE USER") } },
        dismissButton = { TextButton({ confirmAdd = false }) { Text("Cancel") } })
    LazyColumn {
        item {
            TabRow(selectedTabIndex = sub) {
                Tab(sub == 0, { sub = 0 }, text = { Text("Add New User") })
                Tab(sub == 1, { sub = 1 }, text = { Text("Existing Users (${users.size})") })
            }
            Spacer(Modifier.height(8.dp))
        }
        if (sub == 0) item {
            Text("ADD USER", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(n, { n = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ph, { ph = phoneFilter(it) }, label = { Text("Phone number (login ID)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            PasswordField(p, "Password (min 8, letters + numbers)") { p = it }
            PasswordField(c, "Confirm password") { c = it }
            OutlinedButton({ rolesDlg = true }, Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp)) {
                Text(if (sel.isEmpty()) "Select Roles" else "Roles: " + ROLES.filter { it in sel }.joinToString(", "), maxLines = 1)
            }
            if (msg.isNotEmpty()) Text(msg, color = if (ok) Green else MaterialTheme.colorScheme.error)
            Button({
                when {
                    n.isBlank() -> { ok = false; msg = "Enter the person's name" }
                    normPhone(ph).length != 10 -> { ok = false; msg = "Enter a valid 10-digit phone number" }
                    pwError(p) != null -> { ok = false; msg = pwError(p) ?: "" }
                    p != c -> { ok = false; msg = "Passwords don't match" }
                    sel.isEmpty() -> { ok = false; msg = "Select at least one role" }
                    users.any { it.s("name").equals(n.trim(), ignoreCase = true) } -> { ok = false; msg = "A user with that name already exists" }
                    users.any { it.s("phone") == normPhone(ph) } -> { ok = false; msg = "That phone number already has an account" }
                    else -> { msg = ""; confirmAdd = true }
                }
            }, Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp), enabled = !busy, shape = RoundedCornerShape(12.dp), colors = orangeBtn()) {
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp) else Text("ADD USER")
            }
        }
        if (sub == 1) {
            if (users.isEmpty()) item { Text("No users yet", Modifier.fillMaxWidth().padding(top = 40.dp), textAlign = TextAlign.Center, color = Color.Gray) }
            items(users.sortedBy { it.s("name") }) { u -> UserCard(u, who) { editing = u; editSel = rolesOf(u).toSet() } }
        }
    }
}

// Blocks the whole app (blurred) while refreshing; on failure offers Refresh Again / Exit
@Composable fun SyncOverlay() {
    if (syncState == "ok" && Updater.checked) return
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            if (syncState != "failed") {
                CircularProgressIndicator(Modifier.size(56.dp), color = MaterialTheme.colorScheme.primary)
                Text("Refreshing…", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                Text("Could not refresh", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("Check your internet connection.", Modifier.padding(top = 4.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ refresh() }, Modifier.width(220.dp).height(52.dp), shape = RoundedCornerShape(12.dp), colors = orangeBtn()) { Text("REFRESH AGAIN") }
                OutlinedButton({ (ctx as? Activity)?.finishAffinity() }, Modifier.width(220.dp).height(52.dp).padding(top = 8.dp), shape = RoundedCornerShape(12.dp)) { Text("EXIT") }
            }
        }
    }
}

@Composable fun EditJobDialog(j: M, isAdm: Boolean, who: String, onClose: () -> Unit) {
    var name by remember { mutableStateOf(j.s("jobName")) }; var client by remember { mutableStateOf(j.s("client")) }
    var qty by remember { mutableStateOf(j.n("qty").toString()) }; var photos by remember { mutableStateOf(j.n("photos").toString()) }
    var err by remember { mutableStateOf("") }
    val kb = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(onDismissRequest = onClose, title = { Text("Edit job #${j.s("id")}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (isAdm) { LimitedField(client, "Client Name") { client = it }; Spacer(Modifier.height(8.dp)); LimitedField(name, "Job Name") { name = it }; Spacer(Modifier.height(8.dp)) }
                OutlinedTextField(qty, { qty = it.filter { c -> c.isDigit() }.take(9) }, label = { Text("Catalogue Qty") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(photos, { photos = it.filter { c -> c.isDigit() }.take(9) }, label = { Text("Photos Qty") }, singleLine = true, keyboardOptions = kb, modifier = Modifier.fillMaxWidth())
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button({
            val q = qty.toLongOrNull() ?: 0L; val p = photos.toLongOrNull() ?: 0L
            if (isAdm && (client.isBlank() || name.isBlank())) err = "Client and Job Name can't be empty"
            else if (q <= 0) err = "Catalogue Qty must be greater than 0"
            else if (p <= 0) err = "Photos Qty must be greater than 0"
            else { editJob(j, if (isAdm) name.trim() else j.s("jobName"), if (isAdm) client.trim() else j.s("client"), q, p, who); onClose() }
        }) { Text("SAVE") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
}

// Deleted jobs: admin can restore (keeps all progress) or delete permanently
@Composable fun Trash(who: String) {
    val del = col("deletedJobs").sortedByDescending { it.n("deletedAt") }
    var purge by remember { mutableStateOf<M?>(null) }
    purge?.let { j ->
        AlertDialog(onDismissRequest = { purge = null }, title = { Text("Delete job #${j.s("id")} permanently?") }, text = { Text("This cannot be undone.") },
            confirmButton = { Button({ purgeJob(j.s("_id"), who); purge = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("DELETE FOREVER") } },
            dismissButton = { TextButton({ purge = null }) { Text("Cancel") } })
    }
    if (del.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No deleted jobs", style = MaterialTheme.typography.titleMedium, color = Color.Gray) }
    else LazyColumn {
        item { Text("Deleted jobs • ${del.size}", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium) }
        items(del) { j ->
            Item("#${j.s("id")}  •  ${j.s("client")}", "${j.s("jobName")}\nWas: ${jstat(j)} — at ${curProc(j)}\nDeleted by ${j.s("deletedBy")} • ${ts(j.n("deletedAt"))}", Grey,
                listOf("RESTORE JOB" to { restoreJob(j.s("_id"), who) }, "DELETE PERMANENTLY" to { purge = j }))
        }
    }
}

// Activity logs: 10 at a time, next page fetched from the server as you scroll; filter by user or by job
@Composable fun Logs() {
    val users = col("users")
    var mode by remember { mutableStateOf("All") }
    var value by remember { mutableStateOf("") }; var jobInput by remember { mutableStateOf("") }
    var uDlg by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(listOf<M>()) }
    var last by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var more by remember { mutableStateOf(true) }; var busy by remember { mutableStateOf(false) }; var err by remember { mutableStateOf("") }
    val gen = remember { IntArray(1) }
    fun load(reset: Boolean) {
        if (!reset && (busy || !more)) return
        val my = if (reset) ++gen[0] else gen[0]
        if (reset) { rows = listOf(); last = null; more = true }
        busy = true; err = ""
        var q: Query = db.collection("logs")
        if (mode == "User" && value.isNotEmpty()) q = q.whereEqualTo("by", value)
        if (mode == "Job" && value.isNotEmpty()) q = q.whereEqualTo("job", value)
        q = q.orderBy(FieldPath.documentId()).limit(10)
        val l = last
        if (!reset && l != null) q = q.startAfter(l)
        q.get().addOnSuccessListener { s ->
            if (my != gen[0]) return@addOnSuccessListener
            busy = false
            rows = rows + s.documents.map { (it.data ?: mapOf()) + ("_id" to it.id) }
            last = s.documents.lastOrNull() ?: last; more = s.size() == 10
        }.addOnFailureListener { if (my == gen[0]) { busy = false; err = "Could not load logs: ${it.message}" } }
    }
    LaunchedEffect(mode, value, refreshTick) { load(true) }
    val ls = rememberLazyListState()
    LaunchedEffect(ls, rows.size) { snapshotFlow { ls.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { if (it >= rows.size - 1) load(false) } }
    if (uDlg) PickDialog("Select user", users.map { it.s("name") }.sorted(), 1, { value = it; uDlg = false }) { uDlg = false }
    LazyColumn(state = ls) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp)) {
                listOf("All", "User", "Job").forEach { m -> FilterChip(mode == m, { mode = m; value = ""; jobInput = "" }, { Text(if (m == "All") "All logs" else "By $m") }, Modifier.padding(end = 6.dp)) }
            }
            if (mode == "User") OutlinedButton({ uDlg = true }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (value.isEmpty()) "Select user" else "User: $value") }
            if (mode == "Job") Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(jobInput, { jobInput = it.filter { c -> c.isDigit() }.take(9) }, label = { Text("Job ID") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button({ value = jobInput }, Modifier.height(56.dp)) { Text("SHOW") }
            }
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        if (rows.isEmpty() && !busy && err.isEmpty()) item { Text("No logs found", Modifier.fillMaxWidth().padding(top = 40.dp), textAlign = TextAlign.Center, color = Color.Gray) }
        items(rows) { r ->
            Item(r.s("action"), "By ${r.s("by")} • ${ts(r.n("t"))}" + (if (r.s("job").isNotEmpty()) "\nJob #${r.s("job")}" else "") +
                (if (r.s("target").isNotEmpty()) "\nUser: ${r.s("target")}" else "") + (if (r.s("detail").isNotEmpty()) "\n${r.s("detail")}" else ""), Blue)
        }
        if (busy) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) } }
    }
}

@Composable fun AboutScreen() {
    val ctx = LocalContext.current
    val info = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_logo), "Mantone Catalogue logo", Modifier.size(112.dp))
            Text("Mantone Catalogue", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge)
            Text("Version ${info.versionName} (${info.longVersionCode})", Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ Updater.check() }, Modifier.padding(top = 24.dp).width(240.dp).height(52.dp), enabled = !Updater.checking, shape = RoundedCornerShape(12.dp),
                colors = blueBtn()) {
                if (Updater.checking) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp) else Text("CHECK FOR UPDATES")
            }
            if (Updater.checked && !Updater.checking)
                Text(if (Updater.failed) "Couldn't check for updates.\nCheck your internet connection." else "You're up to date ✅", Modifier.padding(top = 12.dp), textAlign = TextAlign.Center,
                    color = if (Updater.failed) MaterialTheme.colorScheme.error else Green)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Made With ❤️", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("By", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Ballava", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// Full-screen block: nobody can log in or use the app until the latest version is installed
@Composable fun UpdateGate() {
    val ctx = LocalContext.current
    if (!Updater.checked || !Updater.required) return
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(96.dp))
            Text("Update required", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge)
            Text("Version ${Updater.latestName} is available. You must update to continue.", Modifier.padding(top = 8.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (Updater.notes.isNotEmpty()) Text(Updater.notes, Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
            if (Updater.downloading) {
                LinearProgressIndicator(progress = { Updater.progress / 100f }, Modifier.padding(top = 20.dp).fillMaxWidth())
                Text("Downloading… ${Updater.progress}%", Modifier.padding(top = 8.dp))
            } else Button({ Updater.install(ctx) }, Modifier.padding(top = 20.dp).fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), colors = blueBtn()) { Text("DOWNLOAD & INSTALL") }
            if (Updater.error.isNotEmpty()) Text(Updater.error, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable fun NotifBanner() {
    if (!notifWarn) return
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), contentAlignment = Alignment.TopCenter) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F)), elevation = CardDefaults.cardElevation(8.dp)) {
            Row(Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Notification permission denied. You will not receive Job Notifications.", Modifier.weight(1f), color = Color.White)
                TextButton({ notifWarn = false }) { Text("✕", color = Color.White, fontSize = 20.sp) }
            }
        }
    }
}
