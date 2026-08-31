package dev.repochat.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.repochat.R
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.KNOWN_OLLAMA_CLOUD_MODELS
import dev.repochat.core.model.KNOWN_OPENAI_PROVIDERS
import dev.repochat.core.model.ServiceConnection
import dev.repochat.core.model.matchOpenAiPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.settings_saved)

    LaunchedEffect(state.savedFlash) {
        if (state.savedFlash) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(savedMessage)
            viewModel.onSavedFlashShown()
        }
    }

    val editing = state.editingConnectionId?.let { id ->
        state.connections.firstOrNull { it.id == id }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (editing != null) {
                            stringResource(R.string.settings_edit_connection)
                        } else {
                            stringResource(R.string.settings_title)
                        },
                    )
                },
                navigationIcon = {
                    if (onBack != null || editing != null) {
                        IconButton(
                            onClick = {
                                if (editing != null) viewModel.cancelEdit()
                                else onBack?.invoke()
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.chat_back),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (editing != null) {
            ConnectionEditor(
                connection = editing,
                testState = state.connectionTests[editing.id] ?: TestState(),
                useCustomModel = editing.id in state.customModelIds,
                onChange = viewModel::updateConnection,
                onSelectPreset = { viewModel.selectOpenAiPreset(editing.id, it) },
                onSelectModel = { model ->
                    viewModel.setUseCustomModel(editing.id, false)
                    viewModel.updateConnection(editing.copy(modelName = model))
                },
                onCustomModel = { viewModel.setUseCustomModel(editing.id, true) },
                onTest = { viewModel.testConnection(editing.id) },
                onSave = viewModel::saveConnectionEdit,
                onDelete = { viewModel.deleteConnection(editing.id) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.settings_ai_providers),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                Text(
                    text = stringResource(R.string.settings_ai_providers_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                val ordered = state.providerOrder.mapNotNull { id ->
                    state.connections.firstOrNull { it.id == id }
                } + state.connections.filter {
                    it.id !in state.providerOrder &&
                        (it.type == ConnectionType.OLLAMA || it.type == ConnectionType.OPENAI_COMPATIBLE)
                }

                ordered.forEach { conn ->
                    ProviderRow(
                        connection = conn,
                        isActive = state.activeProviderId == conn.id ||
                            (state.activeProviderId == null && ordered.firstOrNull()?.id == conn.id),
                        onEdit = { viewModel.startEditConnection(conn.id) },
                        onMoveUp = { viewModel.moveProvider(conn.id, up = true) },
                        onMoveDown = { viewModel.moveProvider(conn.id, up = false) },
                        onSetActive = { viewModel.setActiveProvider(conn.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.startAddConnection(ConnectionType.OLLAMA) }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.settings_add_ollama))
                    }
                    OutlinedButton(
                        onClick = { viewModel.startAddConnection(ConnectionType.OPENAI_COMPATIBLE) },
                    ) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.settings_add_openai))
                    }
                }

                Spacer(Modifier.height(28.dp))
                Text(
                    text = stringResource(R.string.settings_github_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                SecretField(
                    value = state.githubPat,
                    onValueChange = viewModel::onGithubPatChange,
                    label = stringResource(R.string.settings_github_pat),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = viewModel::testGithubConnection) {
                        Text(stringResource(R.string.settings_test))
                    }
                    Spacer(Modifier.width(12.dp))
                    TestStatusLabel(state.githubTest)
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = viewModel::save,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text(stringResource(R.string.settings_save))
                }

                Spacer(Modifier.height(24.dp))
                val context = LocalContext.current
                Text(
                    text = stringResource(R.string.settings_battery_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        runCatching { context.startActivity(intent) }
                    },
                ) {
                    Text(stringResource(R.string.settings_battery_open))
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ProviderRow(
    connection: ServiceConnection,
    isActive: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSetActive: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connection.label.ifBlank { connection.type.name },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = buildString {
                    append(connection.modelName.ifBlank { "—" })
                    if (isActive) append(" · active")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveUp) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null)
        }
        IconButton(onClick = onMoveDown) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
        }
        TextButton(onClick = onSetActive) {
            Text(if (isActive) "✓" else stringResource(R.string.settings_use))
        }
        TextButton(onClick = onEdit) {
            Text(stringResource(R.string.settings_edit))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionEditor(
    connection: ServiceConnection,
    testState: TestState,
    useCustomModel: Boolean,
    onChange: (ServiceConnection) -> Unit,
    onSelectPreset: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onCustomModel: () -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Spacer(Modifier.height(8.dp))

        if (connection.type == ConnectionType.OPENAI_COMPATIBLE) {
            val matched = matchOpenAiPreset(connection.baseUrl)
            var providerExpanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = matched.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_provider_preset)) },
                    trailingIcon = {
                        IconButton(onClick = { providerExpanded = true }) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false },
                ) {
                    KNOWN_OPENAI_PROVIDERS.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.label) },
                            onClick = {
                                providerExpanded = false
                                onSelectPreset(preset.label)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            val isCustom = matched.baseUrl.isEmpty()
            OutlinedTextField(
                value = connection.baseUrl,
                onValueChange = { if (isCustom) onChange(connection.copy(baseUrl = it)) },
                label = { Text(stringResource(R.string.settings_base_url)) },
                singleLine = true,
                readOnly = !isCustom,
                enabled = isCustom,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    if (!isCustom) {
                        Text(stringResource(R.string.settings_base_url_locked))
                    }
                },
            )

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = connection.label,
                onValueChange = { onChange(connection.copy(label = it)) },
                label = { Text(stringResource(R.string.settings_conn_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = connection.label,
                onValueChange = { onChange(connection.copy(label = it)) },
                label = { Text(stringResource(R.string.settings_conn_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(10.dp))
        SecretField(
            value = connection.apiKey,
            onValueChange = { onChange(connection.copy(apiKey = it)) },
            label = stringResource(R.string.settings_api_key),
        )

        Spacer(Modifier.height(10.dp))
        val modelChoices = when (connection.type) {
            ConnectionType.OLLAMA -> KNOWN_OLLAMA_CLOUD_MODELS
            ConnectionType.OPENAI_COMPATIBLE ->
                SettingsViewModel.suggestedModelsFor(matchOpenAiPreset(connection.baseUrl).label)
            else -> emptyList()
        }
        ModelPicker(
            modelName = connection.modelName,
            models = modelChoices,
            useCustom = useCustomModel || modelChoices.isEmpty(),
            onSelectModel = onSelectModel,
            onCustomModel = onCustomModel,
            onModelTextChange = { onChange(connection.copy(modelName = it)) },
            onBackToList = {
                val pick = connection.modelName.takeIf { it in modelChoices }
                    ?: modelChoices.firstOrNull().orEmpty()
                if (pick.isNotEmpty()) onSelectModel(pick)
            },
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onTest) {
                Text(stringResource(R.string.settings_test))
            }
            Spacer(Modifier.width(12.dp))
            TestStatusLabel(testState)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(stringResource(R.string.settings_save))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_delete_connection))
        }
    }
}

@Composable
private fun ModelPicker(
    modelName: String,
    models: List<String>,
    useCustom: Boolean,
    onSelectModel: (String) -> Unit,
    onCustomModel: () -> Unit,
    onModelTextChange: (String) -> Unit,
    onBackToList: () -> Unit,
) {
    if (!useCustom && models.isNotEmpty()) {
        var expanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = modelName.ifBlank { stringResource(R.string.settings_model_pick) },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.settings_model_name)) },
                trailingIcon = {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                models.forEach { id ->
                    DropdownMenuItem(
                        text = { Text(id) },
                        onClick = {
                            expanded = false
                            onSelectModel(id)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_model_custom)) },
                    onClick = {
                        expanded = false
                        onCustomModel()
                    },
                )
            }
        }
    } else {
        OutlinedTextField(
            value = modelName,
            onValueChange = onModelTextChange,
            label = { Text(stringResource(R.string.settings_model_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    if (models.isEmpty()) {
                        stringResource(R.string.settings_ollama_model_hint)
                    } else {
                        stringResource(R.string.settings_model_custom_hint)
                    },
                )
            },
        )
        if (models.isNotEmpty()) {
            TextButton(onClick = onBackToList) {
                Text(stringResource(R.string.settings_model_from_list))
            }
        }
    }
}

@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = null,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TestStatusLabel(state: TestState) {
    when (state.status) {
        TestStatus.Idle -> Unit
        TestStatus.Testing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        TestStatus.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.CheckCircle,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(state.detail, style = MaterialTheme.typography.bodySmall)
        }
        TestStatus.Failure -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ErrorOutline,
                null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                state.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
