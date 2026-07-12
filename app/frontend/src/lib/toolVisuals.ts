// Maps an EVE tool name to a friendly, human-readable visual: an icon + a present-tense
// "running" phrase ("Checking your email…") + a short noun title ("Email"). This is what makes
// EVE's talking UI *do something* when it invokes a tool, instead of showing the raw tool id.
//
// Pure + framework-agnostic (returns a lucide icon component reference, no JSX) so it is unit
// tested in isolation. Unknown tools fall back to a wrench + a prettified name.
import {
  Activity,
  AlarmClock,
  BookOpen,
  Bookmark,
  Bot,
  Brain,
  Calendar,
  Camera,
  CheckCircle2,
  CloudSun,
  Inbox,
  Mail,
  MessageSquare,
  Monitor,
  MonitorUp,
  Newspaper,
  Receipt,
  Search,
  Send,
  Swords,
  Volume2,
  type LucideIcon,
} from 'lucide-react';

export interface ToolVisual {
  /** Lucide icon representing the tool. */
  Icon: LucideIcon;
  /** Short noun shown once the tool finishes, e.g. "Email", "Invoice". */
  title: string;
  /** Present-tense status shown while running, e.g. "Checking your email…". */
  running: string;
}

const TABLE: Record<string, ToolVisual> = {
  check_email: { Icon: Mail, title: 'Email', running: 'Checking your email…' },
  check_inbox: { Icon: Inbox, title: 'Inbox', running: 'Checking your inbox…' },
  get_calendar: { Icon: Calendar, title: 'Calendar', running: 'Looking at your calendar…' },
  get_news: { Icon: Newspaper, title: 'News', running: 'Pulling the headlines…' },
  get_weather: { Icon: CloudSun, title: 'Weather', running: 'Checking the weather…' },
  open_on_pc: { Icon: Monitor, title: 'Open on PC', running: 'Opening that on your PC…' },
  search_knowledge: { Icon: Search, title: 'Knowledge', running: 'Searching your knowledge base…' },
  search_notes: { Icon: Search, title: 'Notes', running: 'Searching your notes…' },
  send_to_channel: { Icon: Send, title: 'Message', running: 'Sending your message…' },
  set_reminder: { Icon: AlarmClock, title: 'Reminder', running: 'Setting your reminder…' },
  set_voice: { Icon: Volume2, title: 'Voice', running: 'Switching my voice…' },
  start_challenger_mode: { Icon: Swords, title: 'Challenger', running: 'Entering challenger mode…' },
  system_report: { Icon: Activity, title: 'System report', running: 'Auditing my own tools…' },
  remember: { Icon: Bookmark, title: 'Remember', running: 'Saving that to memory…' },
  recall: { Icon: BookOpen, title: 'Recall', running: 'Recalling what I know…' },
  prepare_text: { Icon: MessageSquare, title: 'Draft text', running: 'Drafting a text…' },
  confirm_send_text: { Icon: Send, title: 'Send text', running: 'Sending the text…' },
  jarvis_agent: { Icon: Brain, title: 'Agent', running: 'Handing this to the agent…' },
  create_invoice: { Icon: Receipt, title: 'Invoice', running: 'Drafting the invoice…' },
  confirm_action: { Icon: CheckCircle2, title: 'Confirm', running: 'Confirming…' },
  look_via_phone: { Icon: Camera, title: 'Looking', running: 'Looking through your phone camera…' },
  surface_visual: { Icon: MonitorUp, title: 'On screen', running: 'Putting that on your screen…' },
};

/** Title-case a raw tool id: "get_weather" → "Get Weather". */
export function prettifyToolName(tool: string): string {
  return tool
    .split(/[_\s]+/)
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

/** Resolve a tool name to its visual, falling back gracefully for unknown tools. */
export function toolVisual(tool: string): ToolVisual {
  const known = TABLE[tool?.toLowerCase?.() ?? ''];
  if (known) return known;
  const pretty = prettifyToolName(tool || 'tool');
  return { Icon: Bot, title: pretty, running: `Running ${pretty}…` };
}
