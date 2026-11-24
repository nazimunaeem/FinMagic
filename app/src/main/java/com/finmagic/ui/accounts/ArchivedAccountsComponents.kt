package com.finmagic.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.repository.ArchivedAccountWithSms
import com.finmagic.ui.theme.StatusSafe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ArchivedAccountRow(
    item: ArchivedAccountWithSms,
    onRestore: () -> Unit,
    onManageSms: () -> Unit,
    onDeletePermanently: () -> Unit,
    modifier: Modifier = Modifier,
    isSmsLoading: Boolean = false,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val account = item.account
    val smsCount = item.matchingSms.size
    val canDeletePermanently = !isSmsLoading && smsCount == 0
    val haptic = LocalHapticFeedback.current

    val subtitleParts = buildList {
        if (account.accountMask.isNotBlank() &&
            !account.displayName.contains(account.accountMask, ignoreCase = true)
        ) {
            add("•• ${account.accountMask}")
        }
        add(
            when {
                isSmsLoading && smsCount == 0 -> "Checking stored SMS..."
                smsCount == 0 -> "No SMS stored"
                else -> "$smsCount stored SMS"
            }
        )
    }

    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) {
                        onToggleSelect?.invoke()
                    } else if (smsCount > 0) {
                        onManageSms()
                    }
                },
                onLongClick = {
                    if (onLongClick != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                }
            ),
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect?.invoke() },
                        modifier = Modifier.testTag("archived_select_checkbox_${account.id}")
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = accountIcon(account.kind),
                            contentDescription = account.kind,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitleParts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (canDeletePermanently) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!selectionMode) {
                    TextButton(
                        onClick = onRestore,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("archived_restore_button_${account.id}")
                    ) {
                        Text("Restore")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (smsCount > 0) {
                    FilledTonalButton(
                        onClick = onManageSms,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("archived_manage_sms_button_${account.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Delete SMS ($smsCount)",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = onDeletePermanently,
                        enabled = false,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("archived_delete_locked_button_${account.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteForever,
                            contentDescription = "Delete permanently disabled until SMS are deleted",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete")
                    }
                } else if (isSmsLoading) {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("archived_checking_sms_button_${account.id}")
                    ) {
                        Text("Checking stored SMS...")
                    }
                } else {
                    Button(
                        onClick = onDeletePermanently,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("archived_delete_permanent_button_${account.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete Account Permanently")
                    }
                }
            }
        }
    }
}

@Composable
internal fun ArchivedAccountSmsDialog(
    item: ArchivedAccountWithSms,
    onDismiss: () -> Unit,
    onDeleteSms: (Long) -> Unit,
    onDeleteAllSms: (List<Long>) -> Unit,
    onRequestPermanentDelete: () -> Unit
) {
    var confirmDeleteAll by remember(item.account.id) { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "${item.account.displayName} SMS",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = if (item.matchingSms.isEmpty()) {
                        "No stored SMS messages"
                    } else {
                        "${item.matchingSms.size} stored message${if (item.matchingSms.size == 1) "" else "s"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Deleting removes SMS from FinMagic's database. Android prevents apps from deleting messages from your phone's Messages app.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (item.matchingSms.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StatusSafe.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = StatusSafe,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "All stored SMS for this account are cleared. You can now permanently delete the account.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(item.matchingSms, key = { "archived_sms_${it.id}" }) { record ->
                            ArchivedSmsItemCard(
                                record = record,
                                formattedTime = dateFormat.format(Date(record.timestamp)),
                                onDelete = {
                                    confirmDeleteAll = false
                                    onDeleteSms(record.id)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (item.matchingSms.isEmpty()) {
                Button(
                    onClick = onRequestPermanentDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("archived_sms_dialog_delete_account_button")
                ) {
                    Text("Delete Permanently")
                }
            } else if (confirmDeleteAll) {
                Button(
                    onClick = {
                        confirmDeleteAll = false
                        onDeleteAllSms(item.matchingSms.map { it.id })
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_all_archived_sms_button")
                ) {
                    Text("Confirm Delete All (${item.matchingSms.size})")
                }
            } else {
                Button(
                    onClick = { confirmDeleteAll = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.testTag("delete_all_archived_sms_button")
                ) {
                    Text("Delete All (${item.matchingSms.size})")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("archived_sms_dialog_close_button")
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun ArchivedSmsItemCard(
    record: SmsRecord,
    formattedTime: String,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 10.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.address?.ifBlank { "Unknown Sender" } ?: "Unknown Sender",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("delete_archived_sms_button_${record.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete SMS",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                text = record.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}

@Composable
internal fun PermanentDeleteAccountDialog(
    account: Account,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Delete Account Permanently?") },
        text = {
            Text(
                "\"${account.displayName}\" has no stored SMS left and will be permanently removed from FinMagic. This cannot be undone."
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.testTag("confirm_permanent_delete_button")
            ) {
                Text("Delete Permanently")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_permanent_delete_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
