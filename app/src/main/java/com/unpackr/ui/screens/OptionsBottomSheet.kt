package com.unpackr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unpackr.R
import com.unpackr.engine.model.ExtractionOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsBottomSheet(
    currentOptions: ExtractionOptions,
    onDismiss: () -> Unit,
    onSave: (ExtractionOptions) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var verifyHashes by remember { mutableStateOf(currentOptions.verifyHashes) }
    var strictMode by remember { mutableStateOf(currentOptions.strictMode) }
    var sanityCheck by remember { mutableStateOf(currentOptions.sanityCheck) }
    var convertSparseToRaw by remember { mutableStateOf(currentOptions.convertSparseToRaw) }
    var parallelism by remember { mutableFloatStateOf(currentOptions.parallelism.toFloat()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.options_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider()

            OptionToggleItem(
                title = stringResource(R.string.options_verify_hash),
                description = stringResource(R.string.options_verify_hash_desc),
                icon = Icons.Default.VerifiedUser,
                checked = verifyHashes,
                onCheckedChange = { verifyHashes = it }
            )

            OptionToggleItem(
                title = stringResource(R.string.options_strict_mode),
                description = stringResource(R.string.options_strict_mode_desc),
                icon = Icons.Default.Security,
                checked = strictMode,
                onCheckedChange = { strictMode = it }
            )

            OptionToggleItem(
                title = stringResource(R.string.options_sanity_check),
                description = stringResource(R.string.options_sanity_check_desc),
                icon = Icons.Default.Check,
                checked = sanityCheck,
                onCheckedChange = { sanityCheck = it }
            )

            OptionToggleItem(
                title = stringResource(R.string.options_sparse_to_raw),
                description = stringResource(R.string.options_sparse_to_raw_desc),
                icon = Icons.Default.Transform,
                checked = convertSparseToRaw,
                onCheckedChange = { convertSparseToRaw = it }
            )

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.options_parallelism),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${parallelism.toInt()} worker threads (Max: 8)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = parallelism,
                    onValueChange = { parallelism = it },
                    valueRange = 1f..8f,
                    steps = 6
                )
            }

            Button(
                onClick = {
                    onSave(
                        ExtractionOptions(
                            verifyHashes = verifyHashes,
                            strictMode = strictMode,
                            sanityCheck = sanityCheck,
                            convertSparseToRaw = convertSparseToRaw,
                            parallelism = parallelism.toInt()
                        )
                    )
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    text = stringResource(R.string.options_save_button),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun OptionToggleItem(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
