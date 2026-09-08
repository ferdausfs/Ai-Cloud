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
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
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
import androidx.compose.material3.Switch
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
import dev.repochat.core.model.ModelPriceClass
import dev.repochat.core.model.ModelPricing
import dev.repochat.core.model.ServiceConnection
import dev.repochat.core.model.matchOpenAiPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    embedded: Boolean = false,
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
            // In the embedded (tab) mode the shell already provides the app bar;
            // the connection editor still needs its own bar with a back action.
            if (!embedded || editing != null) {
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
            }
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
                Text(
                    text = stringResource(R.string.settings_appearance_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                AppearanceCard()

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
private fun AppearanceCard() {
    val themeViewModel: dev.repochat.ui.theme.ThemeViewModel = hiltViewModel()
    val darkOverride by themeViewModel.darkOverride.collectAsStateWithLifecycle()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val darkNow = darkOverride ?: systemDark
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.DarkMode,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_dark_theme),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(R.string.settings_dark_theme_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = darkNow,
                onCheckedChange = { themeViewModel.setDarkTheme(it) },
            )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = connection.label.ifBlank { connection.type.name },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (isActive) {
                    Spacer(Modifier.width(6.dp))
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("ACTIVE", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
            Text(
                text = connection.modelName.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Masked key status — the full key is NEVER displayed on the card.
            Text(
                text = SettingsViewModel.maskKey(connection.apiKey),
                style = MaterialTheme.typography.labelSmall,
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
            onValueChange = onApiKeyChange,
            label = stringResource(R.string.settings_api_key),
        )

        Spacer(Modifier.height(10.dp))
        val curated = when (connection.type) {
            ConnectionType.OLLAMA -> KNOWN_OLLAMA_CLOUD_MODELS
            ConnectionType.OPENAI_COMPATIBLE ->
                SettingsViewModel.suggestedModelsFor(matchOpenAiPreset(connection.baseUrl).label)
            else -> emptyList()
        }
        val providerLabel = when (connection.type) {
            ConnectionType.OPENAI_COMPATIBLE -> matchOpenAiPreset(connection.baseUrl).label
            else -> connection.label
        }
        val allModels = ModelPricing.sortFreeFirst(
            modelList.models.ifEmpty { curated },
            providerLabel,
        )
        val hasFree = allModels.any { ModelPricing.isFreeTier(it, providerLabel) }
        val modelChoices = if (freeOnly && hasFree) {
            allModels.filter { ModelPricing.isFreeTier(it, providerLabel) }
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
            providerLabel = providerLabel,
            useCustom = forceCustom,
            loading = loading,
            failedDetail = modelList.detail,
            offlineCache = modelList.status == ModelListStatus.OfflineCache,
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

/** Which subset of models the picker dropdown shows. */
private enum class ModelFilter { All, Free, Paid }

@Composable
private fun ModelPicker(
    modelName: String,
    models: List<String>,
    allModels: List<String>,
    providerLabel: String,
    useCustom: Boolean,
    loading: Boolean,
    failedDetail: String,
    offlineCache: Boolean,
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
    // Offline indicator: models come from the last successful fetch.
    if (offlineCache) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.CloudOff,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = failedDetail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                        val priceClass = ModelPricing.classify(modelName, providerLabel)
                        val label = when (priceClass) {
                            ModelPriceClass.FREE ->
                                stringResource(R.string.settings_model_free)
                            ModelPriceClass.PROMOTIONAL ->
                                "Promotional — free availability is set by the provider and may change"
                            ModelPriceClass.PAID ->
                                stringResource(R.string.settings_model_paid)
                            ModelPriceClass.UNKNOWN ->
                                "Pricing unavailable"
                        }
                        Text(
                            label,
                            color = when (priceClass) {
                                ModelPriceClass.FREE, ModelPriceClass.PROMOTIONAL ->
                                    MaterialTheme.colorScheme.primary
                                else ->
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
                // Search + price filter inside the dropdown (premium picker).
                var query by remember { mutableStateOf("") }
                var filter by remember { mutableStateOf(ModelFilter.All) }
                val visible = models
                    .filter { it.contains(query.trim(), ignoreCase = true) }
                    .filter { id ->
                        when (filter) {
                            ModelFilter.All -> true
                            ModelFilter.Free -> ModelPricing.isFreeTier(id, providerLabel)
                            ModelFilter.Paid -> ModelPricing.classify(id, providerLabel) ==
                                ModelPriceClass.PAID
                        }
                    }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search models") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ModelFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.name, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                if (visible.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No models match") },
                        onClick = {},
                        enabled = false,
                    )
                }
                visible.forEach { id ->
                    DropdownMenuItem(
                        text = {
                            ModelIdRow(id = id, providerLabel = providerLabel)
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
                        failedDetail.isNotBlank() && !offlineCache -> failedDetail
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
private fun ModelIdRow(id: String, providerLabel: String) {
    val priceClass = ModelPricing.classify(id, providerLabel)
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
                    ModelPricing.badgeLabel(priceClass),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                disabledContainerColor = when (priceClass) {
                    ModelPriceClass.FREE, ModelPriceClass.PROMOTIONAL ->
                        MaterialTheme.colorScheme.primaryContainer
                    ModelPriceClass.PAID ->
                        MaterialTheme.colorScheme.surfaceVariant
                    ModelPriceClass.UNKNOWN ->
                        MaterialTheme.colorScheme.surface
                },
                disabledLabelColor = when (priceClass) {
                    ModelPriceClass.FREE, ModelPriceClass.PROMOTIONAL ->
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else ->
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
