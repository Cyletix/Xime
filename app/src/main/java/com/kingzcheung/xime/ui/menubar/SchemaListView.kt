package com.kingzcheung.xime.ui.menubar

import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.twotone.Gesture
import androidx.compose.material.icons.twotone.KeyboardAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.kingzcheung.xime.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import com.kingzcheung.xime.ui.keyboard.KeyboardPanelGrid
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.settings.SchemaInfo

@Composable
fun SchemaListView(
    schemas: List<SchemaInfo>,
    currentSchemaId: String,
    backgroundColor: Color,
    accentColor: Color,
    keyTextColor: Color,
    keyBgColor: Color,
    onSelectSchema: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    onReorderSchemas: ((List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var editingOrder by remember(com.kingzcheung.xime.settings.InputModes.languageOf(currentSchemaId, schemas)) { mutableStateOf(false) }
    // 功能 item 背景：与键盘按键背景一致（keyBgColor，浅色纯白、深色跟随 keyboard.colors）
    val itemBgColor = keyBgColor
    val textColor = keyTextColor
    val subTextColor = keyTextColor.copy(alpha = 0.65f)
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        run {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (editingOrder) "长按拖动调整顺序" else
                    "${com.kingzcheung.xime.settings.InputModes.languageOf(currentSchemaId, schemas).displayName}输入模式", color = textColor, fontSize = 13.sp)
                if (onReorderSchemas != null && schemas.size > 1) TextButton(onClick = { editingOrder = !editingOrder }) {
                    Text(if (editingOrder) "完成" else "调整顺序", color = accentColor)
                }
            }
        }
        if (editingOrder && onReorderSchemas != null) {
            InputModeOrderEditor(schemas, onReorderSchemas, keyBgColor, textColor, accentColor,
                Modifier.fillMaxWidth().weight(1f))
            return@Column
        }

        if (schemas.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("没有可用的输入方案", color = subTextColor, fontSize = 13.sp)
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                KeyboardPanelGrid(schemas, isLandscape, textColor, "schema-pages",
                    Modifier.fillMaxWidth().fillMaxHeight(), compactCards = true) { schema, cellModifier ->
                    SchemaGridItem(schema, schema.schemaId == currentSchemaId, itemBgColor, textColor,
                        accentColor = accentColor, onSelect = { onSelectSchema(schema.schemaId) },
                        modifier = cellModifier.testTag("schema-tile:${schema.schemaId}"), isLandscape = isLandscape)
                }
            }
        }
    }
}

@Composable
private fun SchemaGridItem(
    schema: SchemaInfo,
    isSelected: Boolean,
    bgColor: Color,
    textColor: Color,
    subTextColor: Color = textColor,
    accentColor: Color,
    layoutHint: String? = null,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) androidx.compose.ui.graphics.lerp(bgColor, accentColor, 0.18f) else bgColor)
            .semantics { selected = isSelected }
            .clickable { onSelect() }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when {
            isHandwritingSchema(schema.schemaId) ->
                Icon(
                    imageVector = Icons.TwoTone.Gesture,
                    contentDescription = schema.name,
                    tint = if (isSelected) accentColor else textColor,
                    modifier = Modifier.size(24.dp)
                )
            isT9Schema(schema.schemaId) ->
                Icon(
                    painter = painterResource(R.drawable.keyboard_t9),
                    contentDescription = schema.name,
                    tint = if (isSelected) accentColor else textColor,
                    modifier = Modifier.size(24.dp)
                )
            else ->
                Icon(
                    imageVector = Icons.TwoTone.KeyboardAlt,
                    contentDescription = schema.name,
                    tint = if (isSelected) accentColor else textColor,
                    modifier = Modifier.size(24.dp)
                )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = schema.name,
            color = if (isSelected) accentColor else textColor,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (layoutHint != null) {
            Text(
                text = layoutHint,
                color = if (isSelected) accentColor.copy(alpha = 0.7f) else subTextColor,
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
