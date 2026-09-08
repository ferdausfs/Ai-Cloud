package dev.repochat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import dev.repochat.R
import dev.repochat.core.model.WorkflowJobInfo
import dev.repochat.core.model.WorkflowRunInfo
import dev.repochat.ui.theme.CodeTextStyle
import dev.repochat.ui.theme.GitHubGreenText
import dev.repochat.ui.theme.GitHubRed
import dev.repochat.ui.theme.GitHubRedLight

/**
 * In-app CI build center: workflow run summary, job list, and the failing
 * job's log tail with error-line highlighting — so build failures never
 * force a detour to the browser. Offers "Fix with AI" on failures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CiBuildSheet(
    state: ChatUiState,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onSelectJob: (WorkflowJobInfo) -> Unit,
    onFixWithAi: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        val run = state.ciStatus
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            // ---------- header ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ci_sheet_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    run?.let {
                        Text(
                            text = it.name.ifBlank { it.chipLabel() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.ci_sheet_refresh),
                        modifier = Modifier.size(20.dp),
                    )
                }
                run?.htmlUrl?.let { url ->
                    IconButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {
                            // No browser — nothing else to do here.
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = stringResource(R.string.ci_sheet_open_browser),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.chat_cancel))
                }
            }

            Spacer(Modifier.height(6.dp))

            if (run == null) {
                Text(
                    text = stringResource(R.string.ci_sheet_no_runs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, container, content) = ciChipColors(run.conclusion, run.status)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = container,
                    contentColor = content,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
                        Text(run.chipLabel(), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = run.summarize(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))

            // ---------- jobs ----------
            when {
                state.ciJobsLoading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.ci_sheet_loading_jobs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.ciJobsError != null -> Text(
                    text = state.ciJobsError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp),
                )

                state.ciJobs.isNotEmpty() -> Column {
                    state.ciJobs.forEachIndexed { index, job ->
                        JobRow(
                            job = job,
                            selected = state.selectedJob?.id == job.id,
                            onClick = { onSelectJob(job) },
                        )
                        if (index != state.ciJobs.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }

                else -> Text(
                    text = stringResource(R.string.ci_sheet_no_jobs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            // ---------- log ----------
            LogPanel(
                job = state.selectedJob,
                log = state.jobLog,
                loading = state.jobLogLoading,
                error = state.jobLogError,
                onCopy = { clipboard.setText(AnnotatedString(state.jobLog.orEmpty())) },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, state.jobLog.orEmpty())
                        putExtra(
                            Intent.EXTRA_TITLE,
                            "${state.selectedJob?.name ?: "build"}.log",
                        )
                    }
                    try {
                        context.startActivity(Intent.createChooser(send, null))
                    } catch (_: Exception) {
                    }
                },
            )

            val failed = run.conclusion == "failure"
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (failed) {
                    Button(
                        onClick = onFixWithAi,
                        enabled = !state.typing && !state.approvalPending && !state.approving,
                    ) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ci_sheet_fix_with_ai))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.ci_sheet_close))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun JobRow(
    job: WorkflowJobInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when {
            job.conclusion == "success" -> Icons.Rounded.CheckCircle
            job.conclusion == "failure" -> Icons.Rounded.ErrorOutline
            job.conclusion == "skipped" || job.conclusion == "cancelled" -> Icons.Rounded.SkipNext
            job.status == "in_progress" || job.status == "queued" -> Icons.Rounded.PlayCircle
            else -> Icons.Rounded.HourglassEmpty
        }
        val tint = when {
            job.conclusion == "success" -> GitHubGreenText
            job.conclusion == "failure" -> GitHubRed
            job.status == "in_progress" || job.status == "queued" -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = job.name.ifBlank { "#${job.id}" },
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val failedStep = job.steps.firstOrNull { it.conclusion == "failure" }
        if (failedStep != null) {
            Text(
                text = stringResource(R.string.ci_sheet_failed_step, failedStep.name),
                style = MaterialTheme.typography.labelSmall,
                color = GitHubRed,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LogPanel(
    job: WorkflowJobInfo?,
    log: String?,
    loading: Boolean,
    error: String?,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    if (job == null) return
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.background,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ci_sheet_log_title, job.name.ifBlank { "#${job.id}" }),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (log != null) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.chat_code_copy),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    IconButton(onClick = onShare, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Rounded.Share,
                            contentDescription = stringResource(R.string.ci_sheet_share),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
            when {
                loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )

                log != null -> LogContent(log = log)

                else -> Text(
                    text = stringResource(R.string.ci_sheet_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Marker-based error-line detection (GitHub Actions + common build tools). */
private val ERROR_MARKERS = listOf(
    "##[error]",
    "##[warning]",
    "error:",
    "error ",
    "failed",
    "failure",
    "exception",
    "fatal:",
    "compilation error",
    "e: ", // Kotlin compile errors
)

private fun isErrorLine(line: String): Boolean {
    val lower = line.lowercase()
    return ERROR_MARKERS.any { lower.contains(it) }
}

private const val MAX_LOG_LINES = 400

@Composable
private fun LogContent(log: String) {
    val errorColor = MaterialTheme.colorScheme.isLight().let { if (it) GitHubRedLight else GitHubRed }
    val listState = rememberLazyListState()
    val lines = remember(log) {
        val all = log.split('\n')
        if (all.size > MAX_LOG_LINES) {
            all.takeLast(MAX_LOG_LINES)
        } else {
            all
        }
    }
    // Jump to the first error line (or the end) when the log arrives.
    LaunchedEffect(log) {
        val firstError = lines.indexOfFirst { isErrorLine(it) }
        val target = if (firstError >= 0) firstError else lines.lastIndex
        if (target > 0) listState.scrollToItem(target.coerceAtLeast(0))
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 10.dp, vertical = 8.dp,
        ),
    ) {
        if (lines.size >= MAX_LOG_LINES) {
            item(key = "truncated") {
                Text(
                    text = stringResource(R.string.ci_sheet_log_truncated),
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(lines) { line ->
            Text(
                text = line,
                style = CodeTextStyle.copy(fontSize = CodeTextStyle.fontSize * 0.85f),
                color = if (isErrorLine(line)) errorColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun androidx.compose.material3.ColorScheme.isLight(): Boolean =
    background.luminance() > 0.5f
