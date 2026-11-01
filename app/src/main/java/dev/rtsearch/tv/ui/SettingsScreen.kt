package dev.rtsearch.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.rtsearch.tv.AppController
import dev.rtsearch.tv.MIRRORS
import dev.rtsearch.tv.UpdateState
import dev.rtsearch.tv.data.summary

@Composable
fun SettingsScreen(c: AppController) {
    val keyboard = LocalSoftwareKeyboardController.current
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // Signed-out users came here to sign in; others most likely want the settings below.
        runCatching { if (c.isLoggedIn) categoryFocus.requestFocus() else userFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Tracker account")
                Text(
                    if (c.isLoggedIn) {
                        "You are signed in. You can switch accounts or sign out."
                    } else {
                        "Search and downloads are available to registered users only."
                    },
                    color = Muted,
                )
                TvTextField(
                    value = user,
                    onValueChange = { user = it },
                    placeholder = "Username",
                    imeAction = ImeAction.Next,
                    onAction = { passFocus.requestFocus() },
                    modifier = Modifier.fillMaxWidth().focusRequester(userFocus),
                )
                TvTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    placeholder = "Password",
                    password = true,
                    imeAction = ImeAction.Go,
                    onAction = {
                        keyboard?.hide()
                        c.login(user, pass)
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(passFocus),
                )
                c.loginError?.let { Text(it, color = LeechColor) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        keyboard?.hide()
                        c.login(user, pass)
                    }) { Text(if (c.loginBusy) "Signing in..." else "Sign in") }
                    OutlinedButton(onClick = { c.loginInBrowser() }) { Text("Sign in on the website") }
                    if (c.isLoggedIn) OutlinedButton(onClick = { c.logout() }) { Text("Sign out") }
                }

                SectionTitle("Site address", Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MIRRORS.forEach { mirror ->
                        if (mirror == c.host) {
                            Button(onClick = {}) { Text(mirror) }
                        } else {
                            OutlinedButton(onClick = { c.changeHost(mirror) }) { Text(mirror) }
                        }
                    }
                }
                Text(
                    "Sign-in cookies belong to an address: sign in again after switching.",
                    color = Muted,
                    fontSize = 14.sp,
                )
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Default categories")
                Text("The search starts with these categories every time the app opens.", color = Muted)
                OutlinedButton(
                    onClick = { c.openCategories(pickDefault = true) },
                    modifier = Modifier.focusRequester(categoryFocus),
                ) {
                    Text(
                        c.defaultFilter.summary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 420.dp),
                    )
                }

                SectionTitle("Updates", Modifier.padding(top = 12.dp))
                Text("Installed version: ${c.currentVersion}", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { c.changeAutoUpdate(!c.autoUpdate) }) {
                        Text("Check automatically: " + if (c.autoUpdate) "on" else "off")
                    }
                    OutlinedButton(onClick = { c.checkForUpdates() }) { Text("Check now") }
                }
                UpdateStatus(c)
            }
        }
    }
}

@Composable
private fun UpdateStatus(c: AppController) {
    when (val u = c.update) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> LoadingRow("Checking for updates...")
        UpdateState.UpToDate -> Text("You have the latest version.", color = SeedColor)
        is UpdateState.Available -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Version ${u.release.version} is available.", modifier = Modifier.padding(top = 12.dp))
            Button(onClick = { c.installUpdate() }) { Text("Install") }
        }
        is UpdateState.Downloading -> LoadingRow("Downloading version ${u.release.version}: ${(u.progress * 100).toInt()}%")
        is UpdateState.Failed -> Text("Update check failed: ${u.message}", color = LeechColor)
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}
