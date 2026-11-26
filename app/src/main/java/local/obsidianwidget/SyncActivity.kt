package local.obsidianwidget

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Background sync. Until it's set up, a guided setup, one step at a time: what it is, access to the
 * vault folder, downloading Obsidian Headless, signing in (then the 2FA code, if the account asks
 * for one, and who's signed in), the vault, and the last things to do. After that, sync's own page:
 * on or off, the account and vault, Sync's settings, and what it last printed.
 */
class SyncActivity : ComponentActivity() {
    /** Bumped when coming back to the screen (from a system settings page, say), so what's granted is read again. */
    private var resumed by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumed++
    }

    private enum class Step { INTRO, FILES, DOWNLOAD, SIGN_IN, CODE, SIGNED_IN, VAULT, FINISH, DONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Dressed in the vault's own theme, as the app's first screen is.
            var colors by remember { mutableStateOf<ThemeColors?>(null) }
            LaunchedEffect(Unit) {
                val tree = Vault.defaultTree(this@SyncActivity) ?: return@LaunchedEffect
                colors = withContext(Dispatchers.IO) { ObsidianTheme.load(this@SyncActivity, tree, ObsidianTheme.THEME_VAULT) }
            }
            val look = colors?.let { WidgetConfig("", "", colors = it) }
            AppTheme(palette = look?.palette(this), font = remember(look?.fontFile) { Fonts.compose(look?.fontFile) }) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = { PageHeader("Background sync", onBack = { finish() }) },
                ) { pad -> Screen(Modifier.padding(pad)) }
            }
        }
    }

    @Composable
    private fun Screen(modifier: Modifier) {
        val ctx = this
        var refresh by remember { mutableIntStateOf(0) }
        val tick = resumed + refresh
        // Where setup stands, read again on resume and after each step.
        val files = remember(tick) { HeadlessSync.canReachFiles() }
        val installed = remember(tick) { HeadlessSync.installed(ctx) }
        val loggedIn = remember(tick) { HeadlessSync.loggedIn(ctx) }
        val synced = remember(tick) { HeadlessSync.syncedPath(ctx) }
        val done = remember(tick) { HeadlessSync.setupDone(ctx) }
        var started by remember { mutableStateOf(installed || loggedIn) }
        // Steps the person is on that setup's state alone can't tell: the 2FA code, and seeing who signed in.
        var signIn by remember { mutableStateOf<Step?>(null) }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }

        val step = when {
            !HeadlessSync.available(ctx) -> null
            done && synced != null && loggedIn && installed -> Step.DONE
            !started -> Step.INTRO
            !files -> Step.FILES
            !installed -> Step.DOWNLOAD
            signIn == Step.CODE -> Step.CODE
            !loggedIn -> Step.SIGN_IN
            signIn == Step.SIGNED_IN -> Step.SIGNED_IN
            synced == null -> Step.VAULT
            else -> Step.FINISH
        }

        if (step == null) {
            Page(modifier, null, "Not available on this phone", R.drawable.ic_refresh, "Background sync runs Obsidian's Sync client on Node.js, which the app only carries for 64-bit ARM phones.") {}
            return
        }
        if (step == Step.DONE) {
            Dashboard(modifier, synced!!, tick) { refresh++ }
            return
        }

        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        fun work(action: suspend () -> String?) {
            busy = true
            error = null
            scope.launch {
                error = runCatching { withContext(Dispatchers.IO) { action() } }.getOrElse { it.message ?: it.toString() }
                busy = false
                refresh++
            }
        }
        val allFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
        val total = 5
        val number = when (step) {
            Step.FILES -> 1; Step.DOWNLOAD -> 2; Step.SIGN_IN, Step.CODE, Step.SIGNED_IN -> 3; Step.VAULT -> 4; Step.FINISH -> 5; else -> 0
        }

        when (step) {
            Step.INTRO -> Page(
                modifier, null, "Sync without opening Obsidian", R.drawable.ic_refresh,
                "Notes you edit on the widget reach your other devices straight away, and changes from them reach the widget, while Obsidian stays closed.\n\n" +
                    "It runs Obsidian Headless, Obsidian's own command-line Sync client, in the background. You'll need your Obsidian account, and Sync turned off in Obsidian on this phone, so only one Sync client runs here.",
            ) {
                Primary("Get started") { started = true }
            }

            Step.FILES -> Page(
                modifier, number to total, "Allow access to your vault", R.drawable.ic_folder_open,
                "Sync works on the vault folder directly, which Android allows only with All files access. On the next screen, turn it on for QuickNote.",
            ) {
                Primary("Allow access") {
                    allFiles.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                }
            }

            Step.DOWNLOAD -> Page(
                modifier, number to total, "Download Obsidian Headless", R.drawable.ic_open,
                "Obsidian's Sync client, from npm (about 1 MB). It's kept in the app's own storage.",
            ) {
                Primary(if (busy) "Downloading" else "Download", busy = busy) { work { HeadlessSync.install(ctx); null } }
                Problem(error)
            }

            Step.SIGN_IN -> Page(
                modifier, number to total, "Sign in to Obsidian", R.drawable.ic_settings,
                "The account your vault syncs with.",
            ) {
                Field(email, { email = it }, "Email", KeyboardType.Email)
                Field(password, { password = it }, "Password", KeyboardType.Password, secret = true)
                Primary(if (busy) "Signing in" else "Sign in", enabled = email.isNotBlank() && password.isNotEmpty(), busy = busy) {
                    work {
                        when (val r = HeadlessSync.signIn(ctx, email.trim(), password, "")) {
                            HeadlessSync.SignIn.Done -> { signIn = Step.SIGNED_IN; password = ""; null }
                            HeadlessSync.SignIn.NeedsCode, HeadlessSync.SignIn.WrongCode -> { signIn = Step.CODE; null }
                            is HeadlessSync.SignIn.Failed -> r.message
                        }
                    }
                }
                Problem(error)
            }

            Step.CODE -> {
                var code by remember { mutableStateOf("") }
                Page(
                    modifier, number to total, "Two-factor code", R.drawable.ic_settings,
                    "Enter the 6-digit code from your authenticator app.",
                ) {
                    Field(code, { if (it.length <= 8) code = it.filter(Char::isDigit) }, "Code", KeyboardType.Number)
                    Primary(if (busy) "Checking" else "Verify", enabled = code.length >= 6, busy = busy) {
                        work {
                            when (val r = HeadlessSync.signIn(ctx, email.trim(), password, code)) {
                                HeadlessSync.SignIn.Done -> { signIn = Step.SIGNED_IN; password = ""; null }
                                HeadlessSync.SignIn.WrongCode, HeadlessSync.SignIn.NeedsCode -> { code = ""; "That code didn't work. Try the current one." }
                                is HeadlessSync.SignIn.Failed -> r.message
                            }
                        }
                    }
                    Secondary("Back") { signIn = null; error = null }
                    Problem(error)
                }
            }

            Step.SIGNED_IN -> Page(modifier, number to total, "You're signed in", R.drawable.ic_check, null) {
                Confirm(HeadlessSync.account(ctx) ?: "Obsidian account")
                Primary("Continue") { signIn = null }
                Secondary("Not you? Sign out") {
                    work { HeadlessSync.logout(ctx); signIn = null; null }
                }
            }

            Step.VAULT -> VaultStep(modifier, number to total) { refresh++ }

            Step.FINISH -> FinishStep(modifier, number to total, tick) { refresh++ }

            else -> Unit
        }
    }

    /** Which remote vault, synced into the app's vault folder; with its encryption password if it has one. */
    @Composable
    private fun VaultStep(modifier: Modifier, progress: Pair<Int, Int>, onDone: () -> Unit) {
        val ctx = this
        val scope = rememberCoroutineScope()
        val folder = remember { Vault.defaultTree(ctx)?.let(HeadlessSync::vaultPath) }
        var vaults by remember { mutableStateOf<List<HeadlessSync.RemoteVault>?>(null) }
        var chosen by remember { mutableStateOf<HeadlessSync.RemoteVault?>(null) }
        var e2e by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            val (list, err) = withContext(Dispatchers.IO) { HeadlessSync.remoteVaults(ctx) }
            vaults = list
            if (list.size == 1) chosen = list[0]
            error = err
        }
        Page(
            modifier, progress, "Choose your vault", R.drawable.ic_folder,
            if (folder != null) "It syncs into $folder, the vault folder the widget uses." else "Choose the vault folder on the app's first screen first.",
        ) {
            val list = vaults
            if (list == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Finding your vaults", color = noteColor())
                }
            }
            list?.forEach { v -> Choice(v.name, chosen?.id == v.id) { chosen = v } }
            if (list != null && chosen != null) {
                Field(e2e, { e2e = it }, "Encryption password (only if the vault has one)", KeyboardType.Password, secret = true)
            }
            Primary(if (busy) "Connecting" else "Connect", enabled = chosen != null && folder != null, busy = busy) {
                val v = chosen ?: return@Primary
                val path = folder ?: return@Primary
                busy = true
                error = null
                scope.launch {
                    error = withContext(Dispatchers.IO) {
                        val r = HeadlessSync.setup(ctx, v.id, path, e2e)
                        if (!r.ok) return@withContext r.output.lines().lastOrNull { it.isNotBlank() } ?: "Couldn't connect"
                        HeadlessSync.setSynced(ctx, path, v.name)
                        // Everything on, as stock Sync with all its switches on; changeable later.
                        val c = HeadlessSync.writeConfig(ctx, path, HeadlessSync.EVERYTHING)
                        if (c.ok) null else "Connected, but couldn't set what syncs: " + c.output.lines().lastOrNull { it.isNotBlank() }
                    }
                    busy = false
                    onDone()
                }
            }
            Problem(error)
        }
    }

    /** The last things before it's turned on: Sync off in Obsidian here, and no battery limits. */
    @Composable
    private fun FinishStep(modifier: Modifier, progress: Pair<Int, Int>, tick: Int, onDone: () -> Unit) {
        val ctx = this
        val power = getSystemService(PowerManager::class.java)
        val unrestricted = remember(tick) { power.isIgnoringBatteryOptimizations(packageName) }
        var obsidianOff by remember { mutableStateOf(false) }
        val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        Page(modifier, progress, "Almost done", R.drawable.ic_check, "Two things, then it starts.") {
            Check(
                "Turn off Sync in Obsidian on this phone",
                "In Obsidian: Settings, Sync, Disconnect. Two Sync clients on one phone clash.",
                obsidianOff,
            ) { obsidianOff = !obsidianOff }
            Check(
                "Let it run in the background",
                if (unrestricted) "Battery use is unrestricted" else "Allow unrestricted battery use, so the phone doesn't stop it",
                unrestricted,
            ) {
                @Suppress("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            Primary("Turn on background sync", enabled = obsidianOff) {
                if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                HeadlessSync.setEnabled(ctx, true)
                HeadlessSync.setSetupDone(ctx, true)
                SyncService.start(ctx)
                onDone()
            }
        }
    }

    /** Once set up: on or off, the account and vault, Sync's settings, the log. */
    @Composable
    private fun Dashboard(modifier: Modifier, path: String, tick: Int, onChange: () -> Unit) {
        val ctx = this
        val scope = rememberCoroutineScope()
        var enabled by remember(tick) { mutableStateOf(HeadlessSync.enabled(ctx)) }
        var log by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) {
            while (true) {
                log = withContext(Dispatchers.IO) { HeadlessSync.log(ctx).takeIf { it.exists() }?.readLines()?.takeLast(40).orEmpty() }
                delay(3000)
            }
        }
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Sync", R.drawable.ic_refresh) {
                SwitchRow(
                    "Sync in the background",
                    if (enabled) log.lastOrNull()?.substringAfter("  ") ?: "Starting" else "Off",
                    enabled,
                ) { on ->
                    enabled = on
                    HeadlessSync.setEnabled(ctx, on)
                    if (on) SyncService.start(ctx) else SyncService.stop(ctx)
                }
            }
            SectionCard("Account", R.drawable.ic_check) {
                NavRow(HeadlessSync.account(ctx) ?: "Obsidian account", "Tap to sign out") {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            SyncService.stop(ctx)
                            HeadlessSync.setEnabled(ctx, false)
                            HeadlessSync.logout(ctx)
                        }
                        onChange()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavRow(HeadlessSync.syncedVault(ctx) ?: "Vault", "$path. Tap to disconnect") {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            SyncService.stop(ctx)
                            HeadlessSync.setEnabled(ctx, false)
                            HeadlessSync.unlink(ctx, path)
                            HeadlessSync.setSynced(ctx, null, null)
                            HeadlessSync.setSetupDone(ctx, false)
                        }
                        onChange()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavRow("Update Obsidian Headless", "Download the latest version") {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { HeadlessSync.install(ctx) } }
                        SyncService.restart(ctx)
                    }
                }
            }
            SettingsCard(path, tick)
            if (log.isNotEmpty()) {
                SectionCard("Log", R.drawable.ic_note) {
                    Text(log.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = noteColor())
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    /**
     * The vault's Sync settings, laid out as Obsidian's: conflicts, attachment types, vault settings,
     * folders left out. Each change is saved to Headless at once, and sync restarted to take it up.
     */
    @Composable
    private fun SettingsCard(path: String, tick: Int) {
        val ctx = this
        val scope = rememberCoroutineScope()
        var config by remember(tick) { mutableStateOf<HeadlessSync.Config?>(null) }
        var problem by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(tick) { config = withContext(Dispatchers.IO) { HeadlessSync.readConfig(ctx, path) } }
        fun save(next: HeadlessSync.Config) {
            config = next
            scope.launch {
                val r = withContext(Dispatchers.IO) { HeadlessSync.writeConfig(ctx, path, next) }
                problem = if (r.ok) null else r.output.lines().lastOrNull { it.isNotBlank() } ?: "Couldn't save"
                if (r.ok) SyncService.restart(ctx)
            }
        }
        SectionCard("Sync settings", R.drawable.ic_settings) {
            val c = config
            if (c == null) {
                Text("Reading...", style = MaterialTheme.typography.bodyMedium, color = noteColor())
                return@SectionCard
            }
            SettingLabel("Conflict resolution", "When a note changed on two devices before they synced")
            PillSegmented(listOf("merge", "conflict"), c.conflict, { if (it == "merge") "Merge" else "Conflict file" }) {
                save(c.copy(conflict = it))
            }
            SettingLabel("File types", "Notes, canvases and bases always sync")
            for ((key, name) in HeadlessSync.FILE_TYPES) {
                SwitchRow(name, null, key in c.fileTypes) { on -> save(c.copy(fileTypes = if (on) c.fileTypes + key else c.fileTypes - key)) }
            }
            SettingLabel("Vault settings", "What syncs from the vault's settings folder")
            for ((key, name) in HeadlessSync.CONFIGS) {
                SwitchRow(name, null, key in c.configs) { on -> save(c.copy(configs = if (on) c.configs + key else c.configs - key)) }
            }
            var folders by remember(c.excluded) { mutableStateOf(c.excluded.joinToString(", ")) }
            SettingLabel("Excluded folders", "Folders left out, separated by commas")
            OutlinedTextField(
                folders, { folders = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("None") },
            )
            val typed = folders.split(',').map { it.trim().trim('/') }.filter { it.isNotEmpty() }
            if (typed != c.excluded) {
                Button(onClick = { save(c.copy(excluded = typed)) }, shape = CircleShape) { Text("Save folders") }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }

    // The setup's building blocks.

    /** A setup step's page: in one of the app's cards, its title, how far along, what it's for, then its controls. */
    @Composable
    private fun Page(modifier: Modifier, progress: Pair<Int, Int>?, title: String, icon: Int, body: String?, content: @Composable () -> Unit) {
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (progress != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress.first / progress.second.toFloat() },
                        modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        drawStopIndicator = {},
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("${progress.first} of ${progress.second}", style = MaterialTheme.typography.labelLarge, color = noteColor())
                }
            }
            SectionCard(title, icon) {
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = noteColor()) }
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    private fun Primary(label: String, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
        val tick = rememberHaptic()
        Button(
            onClick = { tick(); onClick() },
            enabled = enabled && !busy,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(12.dp))
            }
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun Secondary(label: String, onClick: () -> Unit) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }

    @Composable
    private fun Field(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType, secret: Boolean = false) {
        OutlinedTextField(
            value, onChange,
            label = { Text(label) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /** What went wrong; a long one (all a command printed) in small type, as it came. */
    @Composable
    private fun Problem(message: String?) {
        message ?: return
        val long = message.contains('\n')
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (long) FontFamily.Monospace else null,
            fontSize = if (long) 11.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            color = MaterialTheme.colorScheme.error,
        )
    }

    /** Who signed in, with a tick. */
    @Composable
    private fun Confirm(who: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
        ) {
            IconTile(R.drawable.ic_check, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Signed in as", style = MaterialTheme.typography.bodySmall, color = noteColor())
                Text(who, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    /** One of a few choices, picked by tapping it. */
    @Composable
    private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
        val tick = rememberHaptic()
        val primary = MaterialTheme.colorScheme.primary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(2.dp, if (selected) primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(16.dp))
                .clickable { tick(); onClick() }
                .padding(16.dp),
        ) {
            IconTile(R.drawable.ic_folder, tint = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
            if (selected) Icon(painterResource(R.drawable.ic_check), null, tint = primary, modifier = Modifier.size(20.dp))
        }
    }

    /** A thing to do before finishing: ticked when done; tapped, it does it (or marks it done). */
    @Composable
    private fun Check(title: String, subtitle: String, checked: Boolean, onClick: () -> Unit) {
        val tick = rememberHaptic()
        val primary = MaterialTheme.colorScheme.primary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.background)
                .clickable { tick(); onClick() }
                .padding(16.dp),
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (checked) primary else androidx.compose.ui.graphics.Color.Transparent)
                    .border(2.dp, if (checked) primary else MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = noteColor())
            }
        }
    }
}
