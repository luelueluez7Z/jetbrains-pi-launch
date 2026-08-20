package com.ruigu.pichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text

/**
 * 轻量 markdown 渲染：
 * - ``` 围栏代码块 → 深色卡片 + 等宽字体
 * - `行内代码` → 等宽 + 高亮
 * - **加粗** → 加粗
 * - 其余按段落渲染
 */
@Composable
fun MarkdownContent(text: String, modifier: Modifier = Modifier, dark: Boolean = false) {
    val codeBg = if (dark) Color(0x1E1F22) else Color(0xF2F3F5)
    val codeFg = if (dark) Color(0xE8EAF0) else Color(0x24292E)
    val accent = if (dark) Color(0xFF8A9BFF) else Color(0x4B8BF5)

    Column(modifier) {
        var i = 0
        val lines = text.split("\n")
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                // 收集代码块
                val sb = StringBuilder()
                val lang = line.trim().removePrefix("```")
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    sb.append(lines[i]).append('\n')
                    i++
                }
                if (i < lines.size) i++ // 跳过闭合 ``` 
                CodeBlockCard(sb.toString().trimEnd('\n'), codeBg, codeFg, lang, dark)
            } else {
                // 普通段落（累积直到遇到代码块或空行）
                val sb = StringBuilder()
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(lines[i])
                    i++
                }
                if (sb.isNotEmpty()) {
                    InlineMarkdownText(sb.toString(), codeFg, accent, dark)
                    Spacer(Modifier.width(0.dp).padding(2.dp))
                }
            }
        }
    }
}

@Composable
private fun CodeBlockCard(code: String, bg: Color, fg: Color, lang: String, dark: Boolean) {
    val borderColor = if (dark) Color(0x3C3F45) else Color(0xE0E3E8)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
    ) {
        if (lang.isNotEmpty()) {
            Text(
                lang,
                color = if (dark) Color(0x8A93A5) else Color(0x6E7781),
                style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp)
            )
        }
        SelectionContainer {
            Text(
                code,
                color = fg,
                fontFamily = FontFamily.Monospace,
                style = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/** 行内 markdown：`code`、**bold** */
@Composable
private fun InlineMarkdownText(raw: String, fg: Color, accent: Color, dark: Boolean) {
    val annotated = buildInline(raw, fg, accent, dark)
    SelectionContainer {
        Text(
            annotated,
            style = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp)
        )
    }
}

private fun buildInline(raw: String, fg: Color, accent: Color, dark: Boolean): AnnotatedString {
    val codeBg = if (dark) Color(0x33363C) else Color(0xEAECEF)
    return buildAnnotatedString {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                // ``` 单行代码（无闭合围栏时的兜底，实际由外层处理）或 ` 行内代码
                c == '`' -> {
                    val end = raw.indexOf('`', i + 1)
                    if (end > i) {
                        val code = raw.substring(i + 1, end)
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = accent, background = codeBg)) {
                            append(code)
                        }
                        i = end + 1
                    } else {
                        append(c)
                        i++
                    }
                }
                c == '*' && i + 1 < raw.length && raw[i + 1] == '*' -> {
                    val end = raw.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(raw.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(c)
                        i++
                    }
                }
                c == '#' && (i == 0 || raw[i - 1] == '\n') -> {
                    // 标题：跳过 # 与空格
                    var j = i
                    while (j < raw.length && raw[j] == '#') j++
                    if (j < raw.length && raw[j] == ' ') j++
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(raw.substring(j, minOf(j + 80, raw.length)).trimEnd())
                    }
                    i = j
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }
}

/** 用户消息内的纯文本（右对齐气泡） */
@Composable
fun PlainTextContent(text: String, fg: Color) {
    SelectionContainer {
        Text(
            text,
            color = fg,
            style = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp)
        )
    }
}
