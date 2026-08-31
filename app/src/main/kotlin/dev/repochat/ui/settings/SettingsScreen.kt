package dev.repochat.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
                modelList = state.modelLists[editing.id] ?: ModelListState(),
                useCustomModel = editing.id in state.customModelIds,
                freeOnly = state.freeOnlyByConnection[editing.id]
                    ?: SettingsViewModel.defaultFreeOnlyForConnection(editing),
                onChange = viewModel::updateConnection,
                onApiKeyChange = { viewModel.onApiKeyChanged(editing.id, it) },
                onSelectPreset = { viewModel.selectOpenAiPreset(editing.id, it) },
                onSelectModel = { model ->
                    viewModel.setUseCustomModel(editing.id, false)
                    viewModel.updateConnection(editing.copy(modelName = model))
                },
                onCustomModel = { viewModel.setUseCustomModel(editing.id, true) },
                onFreeOnlyChange = { viewModel.setFreeOnly(editing.id, it) },
                onLoadModels = { viewModel.loadModels(editing.id) },
                onTest = { viewModel.testConnection(editing.id) },
                onTriggerDeployment = viewModel::triggerVercelDeployment,
                onSave = viewModel::saveConnectionEdit,
                onDelete = { viewModel.deleteConnection(editing.id) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
            )
        } else {
            SettingsHome(
                state = state,
                onStartEdit = viewModel::startEditConnection,
                onStartAdd = viewModel::startAddConnection,
                onMoveProvider = viewModel::moveProvider,
                onSetActiveProvider = viewModel::setActiveProvider,
                onTestConnection = viewModel::testConnection,
                onSave = viewModel::save,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun SettingsHome(
    state: SettingsUiState,
    onStartEdit: (String) -> Unit,
    onStartAdd: (ConnectionType) -> Unit,
    onMoveProvider: (String, Boolean) -> Unit,
    onSetActiveProvider: (String) -> Unit,
    onTestConnection: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
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

        val llmConnections = state.connections.filter { it.type == ConnectionType.OLLAMA || it.type == ConnectionType.OPENAI_COMPATIBLE }
        val ordered = state.providerOrder.mapNotNull { id ->
            llmConnections.firstOrNull { it.id == id }
        } + llmConnections.filter { it.id !in state.providerOrder }

        ordered.forEach { conn ->
            ProviderRow(
                connection = conn,
                isActive = state.activeProviderId == conn.id ||
                    (state.activeProviderId == null && ordered.firstOrNull()?.id == conn.id),
                onEdit = { onStartEdit(conn.id) },
                onMoveUp = { onMoveProvider(conn.id, up = true) },
                onMoveDown = { onMoveProvider(conn.id, up = false) },
                onSetActive = { onSetActiveProvider(conn.id) },
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onStartAdd(ConnectionType.OLLAMA) }) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_add_ollama))
            }
            OutlinedButton(
                onClick = { onStartAdd(ConnectionType.OPENAI_COMPATIBLE) },
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_add_openai))
            }
        }

        Spacer(Modifier.height(28.dp))
        androidx.compose.material3.HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_services_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_services_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        val services = state.connections.filter {
            it.type != ConnectionType.OLLAMA && it.type != ConnectionType.OPENAI_COMPATIBLE
        }
        services.forEach { conn ->
            ServiceRow(
                connection = conn,
                testState = state.connectionTests[conn.id] ?: TestState(),
                onEdit = { onStartEdit(conn.id) },
                onTest = { onTestConnection(conn.id) },
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onStartAdd(ConnectionType.GITHUB) }) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_add_github))
            }
            OutlinedButton(onClick = { onStartAdd(ConnectionType.CLOUDFLARE) }) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_add_cloudflare))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onStartAdd(ConnectionType.VERCEL) }) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_add_vercel))
            }
            OutlinedButton(onClick = { onStartAdd(ConnectionType.FIREBASE) }) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_add_firebase))
            }
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

@Composable
private fun ServiceRow(
    connection: ServiceConnection,
    testState: TestState,
    onEdit: () -> Unit,
    onTest: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
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
                    text = serviceDetail(connection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            TextButton(onClick = onTest) {
                Text(stringResource(R.string.settings_test))
            }
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.settings_edit))
            }
        }
        TestStatusLabel(testState)
    }
}

internal fun serviceDetail(connection: ServiceConnection): String = when (connection.type) {
    ConnectionType.GITHUB -> "Personal access token"
    ConnectionType.CLOUDFLARE -> "API token & Account ID"
    ConnectionType.VERCEL -> "API token${connection.projectId.ifBlank { "" }.let { if (it.isNotBlank()) " · project $it" else "" }}"
    ConnectionType.FIREBASE -> "Project ID + Web key / Service Account"
    else -> connection.type.name
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionEditor(
    connection: ServiceConnection,
    testState: TestState,
    modelList: ModelListState,
    useCustomModel: Boolean,
    freeOnly: Boolean,
    onChange: (ServiceConnection) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSelectPreset: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onCustomModel: () -> Unit,
    onFreeOnlyChange: (Boolean) -> Unit,
    onLoadModels: () -> Unit,
    onTest: () -> Unit,
    onTriggerDeployment: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val serviceAccountText = stringResource(R.string.settings_service_account_loaded)
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
            onValueChange = onApiKeyChange,
            label = apiKeyLabel(connection.type),
        )

        when (connection.type) {
            ConnectionType.CLOUDFLARE -> {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = connection.accountId,
                    onValueChange = { onChange(connection.copy(accountId = it)) },
                    label = { Text(stringResource(R.string.settings_cloudflare_account_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(stringResource(R.string.settings_cloudflare_account_hint))
                    },
                )
            }
            ConnectionType.VERCEL -> {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = connection.projectId,
                    onValueChange = { onChange(connection.copy(projectId = it)) },
                    label = { Text(stringResource(R.string.settings_vercel_project)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = connection.teamId,
                    onValueChange = { onChange(connection.copy(teamId = it)) },
                    label = { Text(stringResource(R.string.settings_vercel_team)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(stringResource(R.string.settings_vercel_team_hint))
                    },
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { onTriggerDeployment(connection.id) },
                    enabled = connection.projectId.isNotBlank() &&
                        testState.status != TestStatus.Testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_vercel_trigger))
                }
            }
            ConnectionType.FIREBASE -> {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = connection.projectId,
                    onValueChange = { onChange(connection.copy(projectId = it)) },
                    label = { Text(stringResource(R.string.settings_firebase_project)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_firebase_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val pickJson = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val json = try {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?.toString(Charsets.UTF_8).orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    if (json.isNotBlank()) {
                        onChange(connection.copy(serviceAccountJson = json))
                    }
                }
                if (connection.serviceAccountJson.isBlank()) {
                    OutlinedButton(
                        onClick = { pickJson.launch(arrayOf("application/json")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_firebase_pick_service_account))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = serviceAccountText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            else -> Unit
        }

        if (connection.isLlm) {
            Spacer(Modifier.height(10.dp))
            val curated = when (connection.type) {
                ConnectionType.OLLAMA -> KNOWN_OLLAMA_CLOUD_MODELS
                ConnectionType.OPENAI_COMPATIBLE ->
                    SettingsViewModel.suggestedModelsFor(matchOpenAiPreset(connection.baseUrl).label)
                else -> emptyList()
            }
            val allModels = SettingsViewModel.sortModelsFreeFirst(
                modelList.models.ifEmpty { curated },
            )
            val hasFree = allModels.any { SettingsViewModel.isFreeModelId(it) }
            val modelChoices = if (freeOnly && hasFree) {
                allModels.filter { SettingsViewModel.isFreeModelId(it) }
            } else {
                allModels
            }
            val loading = modelList.status == ModelListStatus.Loading
            val forceCustom = useCustomModel ||
                modelList.status == ModelListStatus.Failed ||
                modelChoices.isEmpty()
            ModelPicker(
                modelName = connection.modelName,
                models = modelChoices,
                allModels = allModels,
                useCustom = forceCustom,
                loading = loading,
                failedDetail = modelList.detail,
                freeOnly = freeOnly,
                showFreeFilter = hasFree,
                onFreeOnlyChange = onFreeOnlyChange,
                onSelectModel = onSelectModel,
                onCustomModel = onCustomModel,
                onModelTextChange = { onChange(connection.copy(modelName = it)) },
                onLoadModels = onLoadModels,
                onBackToList = {
                    val pick = connection.modelName.takeIf { it in modelChoices }
                        ?: modelChoices.firstOrNull().orEmpty()
                    if (pick.isNotEmpty()) onSelectModel(pick)
                },
            )
        }

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

internal fun apiKeyLabel(type: ConnectionType): String = when (type) {
    ConnectionType.GITHUB -> "GitHub personal access token"
    ConnectionType.CLOUDFLARE -> "Cloudflare API token"
    ConnectionType.VERCEL -> "Vercel API token"
    ConnectionType.FIREBASE -> "Web API key (optional with Service Account)"
    else -> "API key"
}

@Composable
private fun ModelPicker(
    modelName: String,
    models: List<String>,
    allModels: List<String>,
    useCustom: Boolean,
    loading: Boolean,
    failedDetail: String,
    freeOnly: Boolean,
    showFreeFilter: Boolean,
    onFreeOnlyChange: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    onCustomModel: () -> Unit,
    onModelTextChange: (String) -> Unit,
    onLoadModels: () -> Unit,
    onBackToList: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.settings_model_name),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = onLoadModels, enabled = !loading) {
                Text(stringResource(R.string.settings_load_models))
            }
        }
    }
    if (showFreeFilter) {
        Spacer(Modifier.height(4.dp))
        FilterChip(
            selected = freeOnly,
            onClick = { onFreeOnlyChange(!freeOnly) },
            label = { Text(stringResource(R.string.settings_model_free_only)) },
        )
    }
    Spacer(Modifier.height(4.dp))
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
                supportingText = {
                    if (modelName.isNotBlank()) {
                        val free = SettingsViewModel.isFreeModelId(modelName)
                        Text(
                            if (free) {
                                stringResource(R.string.settings_model_free)
                            } else {
                                stringResource(R.string.settings_model_paid)
                            },
                            color = if (free) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
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
                        text = {
                            ModelIdRow(id = id)
                        },
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
                    when {
                        failedDetail.isNotBlank() -> failedDetail
                        models.isEmpty() && allModels.isEmpty() ->
                            stringResource(R.string.settings_models_load_failed)
                        models.isEmpty() && freeOnly ->
                            stringResource(R.string.settings_model_free_only)
                        else -> stringResource(R.string.settings_model_custom_hint)
                    },
                )
            },
        )
        if (allModels.isNotEmpty()) {
            TextButton(onClick = onBackToList) {
                Text(stringResource(R.string.settings_model_from_list))
            }
        }
    }
}

@Composable
private fun ModelIdRow(id: String) {
    val free = SettingsViewModel.isFreeModelId(id)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = id,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        AssistChip(
            onClick = {},
            enabled = false,
            label = {
                Text(
                    if (free) {
                        stringResource(R.string.settings_model_free)
                    } else {
                        stringResource(R.string.settings_model_paid)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                disabledContainerColor = if (free) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                disabledLabelColor = if (free) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        )
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
