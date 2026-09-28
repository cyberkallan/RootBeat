package com.unshoo.pixelmusic.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.jam.JamRole
import com.unshoo.pixelmusic.presentation.viewmodel.JamViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamScreen(
    navController: NavController,
    viewModel: JamViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.jam_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.jam_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.jam_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.error.isNotBlank()) {
                item {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (state.role == JamRole.Idle) {
                item {
                    Button(
                        onClick = viewModel::startHosting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.jam_start))
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.jam_join),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.jam_code)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    )
                }
                item {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.jam_host_ip)) },
                        singleLine = true,
                    )
                }
                item {
                    Button(
                        onClick = { viewModel.join(code, host) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.jam_join))
                    }
                }
                item {
                    OutlinedButton(
                        onClick = viewModel::findNearby,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.jam_find_nearby))
                    }
                }
                items(state.nearby, key = { "${it.host}:${it.port}" }) { nearby ->
                    TextButton(
                        onClick = {
                            val parsed = nearby.name.substringAfter("RB-", nearby.name)
                            viewModel.join(parsed, nearby.host, nearby.port)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${nearby.name} · ${nearby.host}")
                    }
                }
            } else {
                item {
                    Text(
                        text = state.code,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (state.hostAddress.isNotBlank()) {
                        Text(
                            text = "${state.hostAddress}:${state.port}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (state.status.isNotBlank()) {
                        Text(
                            text = state.status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Text(
                        text = if (state.trackTitle.isBlank()) {
                            stringResource(R.string.jam_nothing)
                        } else {
                            state.trackTitle
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (state.trackArtist.isNotBlank()) {
                        Text(text = state.trackArtist, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::previous) {
                            Text(stringResource(R.string.jam_previous))
                        }
                        Button(onClick = viewModel::togglePlayback) {
                            Text(
                                stringResource(
                                    if (state.isPlaying) R.string.jam_pause else R.string.jam_play,
                                ),
                            )
                        }
                        OutlinedButton(onClick = viewModel::next) {
                            Text(stringResource(R.string.jam_next))
                        }
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.jam_members),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.members.forEach { member ->
                        val suffix = if (member.isHost) " · ${stringResource(R.string.jam_you_host)}" else ""
                        Text(text = member.name + suffix, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            viewModel.search(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.jam_search_hint)) },
                        singleLine = true,
                    )
                }
                items(state.searchResults, key = { "search-${it.id}" }) { track ->
                    TextButton(
                        onClick = { viewModel.addSong(track.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(track.title, fontWeight = FontWeight.SemiBold)
                            Text(track.artist, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.upNext.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.jam_up_next),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(state.upNext, key = { "next-${it.id}" }) { track ->
                        Text(
                            text = "${track.title} — ${track.artist}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item {
                    OutlinedButton(
                        onClick = viewModel::leave,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.jam_leave))
                    }
                }
            }
        }
    }
}
