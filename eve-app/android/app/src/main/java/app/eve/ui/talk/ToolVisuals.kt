package app.eve.ui.talk

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Per-tool icon + friendly title + present-tense running phrase — the Android port of the desktop
 * `lib/toolVisuals.ts`. The transformation here is text+icon: the UI shows a friendly phrase
 * ("Checking your email…") and a meaningful icon instead of the raw tool id. Unknown tools fall
 * back to a bot icon + a title-cased name + "Running {Name}…" (matches the TS fallback).
 *
 * Note `jarvis_agent` is the tool that triggers the delegation waterfall — its icon is the brain.
 */
data class ToolVisual(val icon: ImageVector, val title: String, val running: String)

fun toolVisual(tool: String): ToolVisual = when (tool) {
    "check_email" -> ToolVisual(Icons.Outlined.Email, "Email", "Checking your email…")
    "check_inbox" -> ToolVisual(Icons.Outlined.Inbox, "Inbox", "Checking your inbox…")
    "get_calendar" -> ToolVisual(Icons.Outlined.CalendarMonth, "Calendar", "Looking at your calendar…")
    "get_news" -> ToolVisual(Icons.Outlined.Newspaper, "News", "Pulling the headlines…")
    "get_weather" -> ToolVisual(Icons.Outlined.WbSunny, "Weather", "Checking the weather…")
    "open_on_pc" -> ToolVisual(Icons.Outlined.Computer, "Open on PC", "Opening that on your PC…")
    "search_knowledge" -> ToolVisual(Icons.Outlined.Search, "Knowledge", "Searching your knowledge…")
    "search_notes" -> ToolVisual(Icons.Outlined.Search, "Notes", "Searching your notes…")
    "send_to_channel" -> ToolVisual(Icons.AutoMirrored.Outlined.Send, "Message", "Sending your message…")
    "set_reminder" -> ToolVisual(Icons.Outlined.Alarm, "Reminder", "Setting your reminder…")
    "set_voice" -> ToolVisual(Icons.AutoMirrored.Outlined.VolumeUp, "Voice", "Switching my voice…")
    "start_challenger_mode" -> ToolVisual(Icons.Outlined.Bolt, "Challenger", "Entering challenger mode…")
    "system_report" -> ToolVisual(Icons.Outlined.Insights, "System report", "Auditing my own tools…")
    "remember" -> ToolVisual(Icons.Outlined.Bookmark, "Remember", "Saving that to memory…")
    "recall" -> ToolVisual(Icons.AutoMirrored.Outlined.MenuBook, "Recall", "Recalling what I know…")
    "prepare_text" -> ToolVisual(Icons.Outlined.Edit, "Draft text", "Drafting the text…")
    "confirm_send_text" -> ToolVisual(Icons.AutoMirrored.Outlined.Send, "Send text", "Sending the text…")
    "jarvis_agent" -> ToolVisual(Icons.Outlined.Psychology, "Agent", "Handing this to the agent…")
    "create_invoice" -> ToolVisual(Icons.AutoMirrored.Outlined.ReceiptLong, "Invoice", "Drafting the invoice…")
    "confirm_action" -> ToolVisual(Icons.Outlined.CheckCircle, "Confirm", "Confirming…")
    else -> {
        val name = titleCaseTool(tool)
        ToolVisual(Icons.Outlined.SmartToy, name, "Running $name…")
    }
}

/** "open_on_pc" → "Open On Pc". Mirrors the TS fallback's title-casing of an unknown tool id. */
private fun titleCaseTool(tool: String): String =
    tool.split('_', ' ', '-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        .ifBlank { "Tool" }
