package app.scriptorium.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.scriptorium.agenda.AgendaController
import app.scriptorium.agenda.AgendaLayout
import app.scriptorium.agenda.AgendaSources
import app.scriptorium.agenda.AgendaText
import app.scriptorium.agenda.AgendaView
import app.scriptorium.agenda.DayGroup
import app.scriptorium.agenda.Entry
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val PAGES = 20_000
private const val MID = PAGES / 2

private sealed class ListItem(val key: String) {
    class Month(val ym: YearMonth) : ListItem("m:$ym")
    class Day(val group: DayGroup) : ListItem("d:${group.date}")
}

/**
 * The agenda list: days with entries grouped under month headers, today always present; it grows by months in
 * both directions as the list is scrolled (months computed off the main thread, one by one, cached).
 */
@Composable
fun AgendaList(c: AgendaController, sources: AgendaSources, today: LocalDate, h24: Boolean) {
    val zone = ZoneId.systemDefault()
    var first by remember { mutableStateOf(YearMonth.from(c.selected).minusMonths(1)) }
    var last by remember { mutableStateOf(YearMonth.from(c.selected).plusMonths(3)) }
    // the previous months stay shown until the new data is ready, so the list keeps its place on every change
    var months by remember { mutableStateOf<Map<YearMonth, List<Entry>>>(emptyMap()) }
    var monthsOf by remember { mutableStateOf<AgendaSources?>(null) }
    var target by remember { mutableStateOf<LocalDate?>(c.selected) }
    val list = rememberLazyListState()

    LaunchedEffect(c.scrollRequest) {
        if (c.scrollRequest == 0) return@LaunchedEffect
        val m = YearMonth.from(c.selected)
        if (m.isBefore(first) || m.isAfter(last)) {
            first = m.minusMonths(1)
            last = m.plusMonths(3)
        }
        target = c.selected
    }
    var monthsZone by remember { mutableStateOf(zone) }
    LaunchedEffect(sources, first, last, zone) {
        val fresh = if (monthsOf === sources && monthsZone == zone) HashMap(months) else HashMap()
        monthsZone = zone
        var m = first
        while (!m.isAfter(last)) {
            if (m !in fresh) fresh[m] = c.entries(sources, m.atDay(1), m.atEndOfMonth().plusDays(1)).entries
            m = m.plusMonths(1)
        }
        months = fresh
        monthsOf = sources
    }
    val loaded = monthsOf === sources && generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(last) }.all { it in months }
    val items = remember(months, first, last, today) {
        val entries = generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(last) }.flatMap { months[it].orEmpty() }.toList()
        val groups = AgendaLayout.groupByDay(entries, first.atDay(1), last.atEndOfMonth().plusDays(1), today)
        val out = ArrayList<ListItem>()
        var month: YearMonth? = null
        for (g in groups) {
            val ym = YearMonth.from(g.date)
            if (ym != month) { out.add(ListItem.Month(ym)); month = ym }
            out.add(ListItem.Day(g))
        }
        out
    }
    LaunchedEffect(items, target, loaded) {
        val t = target ?: return@LaunchedEffect
        if (!loaded) return@LaunchedEffect
        val idx = items.indexOfFirst { it is ListItem.Day && !it.group.date.isBefore(t) }
        if (idx >= 0) list.scrollToItem(maxOf(0, if (idx > 0 && items[idx - 1] is ListItem.Month) idx - 1 else idx))
        target = null
    }
    // Endless in both directions: the next two years load as they come into view; past them, and towards the past,
    // each new swipe against an end adds six months (bounded to 20 years), so empty months cannot loop.
    LaunchedEffect(list) {
        var wasScrolling = false
        snapshotFlow {
            listOf(list.firstVisibleItemIndex, list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0, list.layoutInfo.totalItemsCount,
                if (list.isScrollInProgress) 1 else 0, first.hashCode(), last.hashCode(), if (target == null) 0 else 1)
        }.collect { (top, bottom, total, scrolling) ->
            val newGesture = scrolling == 1 && !wasScrolling
            wasScrolling = scrolling == 1
            if (target != null) return@collect
            val horizon = YearMonth.from(today)
            if (bottom >= total - 3) {
                if (last.isBefore(horizon.plusYears(2))) last = last.plusMonths(3)
                else if (newGesture && last.isBefore(horizon.plusYears(20))) last = last.plusMonths(6)
            }
            if (top == 0 && newGesture && first.isAfter(horizon.minusYears(20))) first = first.minusMonths(6)
        }
    }

    LazyColumn(state = list, contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is ListItem.Month -> Text(
                    AgendaText.monthTitle(item.ym.year, item.ym.monthValue), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
                )
                is ListItem.Day -> DayRow(c, item.group, today, zone, h24)
            }
        }
    }
}

@Composable
private fun DayRow(c: AgendaController, g: DayGroup, today: LocalDate, zone: ZoneId, h24: Boolean) {
    Column {
        if (g.date == today) {
            Text("Aujourd'hui", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            DayBadge(g.date, today, Modifier.clickable { c.selected = g.date; c.changeView(AgendaView.DAY) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (g.entries.isEmpty()) {
                    Text("Rien de prévu aujourd'hui", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp))
                }
                g.entries.forEach { e -> EntryChip(e, AgendaText.timeLineOn(e, g.date, zone, h24, c.eventZoneTimes)) { c.open(e) } }
            }
        }
    }
}

// ---- month ----

private fun monthPage(base: YearMonth, page: Int): YearMonth = base.plusMonths((page - MID).toLong())

/** Month grid (≤ 3 bars a day and "+N"), swiped between months; the selected day's entries below it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MonthView(c: AgendaController, sources: AgendaSources, today: LocalDate, h24: Boolean) {
    val base = remember { YearMonth.now() }
    val pager = rememberPagerState(initialPage = MID + ChronoUnit.MONTHS.between(base, YearMonth.from(c.selected)).toInt()) { PAGES }
    SyncPager(pager, { MID + ChronoUnit.MONTHS.between(base, YearMonth.from(c.selected)).toInt() }) { page ->
        val ym = monthPage(base, page)
        if (YearMonth.from(c.selected) != ym) c.selected = if (ym == YearMonth.from(today)) today else ym.atDay(1)
    }
    val zone = ZoneId.systemDefault()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            AgendaLayout.weekdayOrder(c.weekStart).forEach { d ->
                Text(AgendaText.WEEKDAYS_LETTER[d.value - 1], Modifier.weight(1f).padding(vertical = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalPager(pager, Modifier.weight(0.62f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
            MonthGrid(c, sources, monthPage(base, page), today)
        }
        HorizontalDivider()
        val sel = c.selected
        val day by produceState<List<Entry>?>(null, sources, sel) {
            value = AgendaLayout.perDay(c.entries(sources, sel, sel.plusDays(1)).entries, listOf(sel))[sel]
        }
        LazyColumn(Modifier.weight(0.38f).fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(AgendaText.dayTitle(sel, today).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { c.changeView(AgendaView.DAY) })
            }
            val list = day.orEmpty()
            if (day != null && list.isEmpty()) item { Text("Rien de prévu", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(list, key = { it.key }) { e -> EntryChip(e, AgendaText.timeLineOn(e, sel, zone, h24, c.eventZoneTimes)) { c.open(e) } }
        }
    }
}

@Composable
private fun MonthGrid(c: AgendaController, sources: AgendaSources, ym: YearMonth, today: LocalDate) {
    val days = remember(ym, c.weekStart) { AgendaLayout.monthGrid(ym, c.weekStart) }
    val byDay by produceState<Map<LocalDate, List<Entry>>>(emptyMap(), sources, days) {
        value = AgendaLayout.perDay(c.entries(sources, days.first(), days.last().plusDays(1)).entries, days)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 2.dp)) {
        days.chunked(7).forEach { week ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                week.forEach { d ->
                    val (shown, more) = AgendaLayout.cell(byDay[d].orEmpty())
                    val selected = d == c.selected
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(1.dp).clip(RoundedCornerShape(6.dp))
                            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                            .clickable { if (selected) c.changeView(AgendaView.DAY) else c.selected = d },
                    ) {
                        Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(22.dp).clip(CircleShape).background(if (d == today) MaterialTheme.colorScheme.primary else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(d.dayOfMonth.toString(), fontSize = 12.sp, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        d == today -> MaterialTheme.colorScheme.onPrimary
                                        YearMonth.from(d) != ym -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    })
                            }
                        }
                        shown.forEach { e ->
                            Text(
                                entryTitle(e), color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Clip,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp, vertical = 1.dp).clip(RoundedCornerShape(3.dp))
                                    .background(agendaChipColor(e.color)).padding(horizontal = 2.dp),
                            )
                        }
                        if (more > 0) Text("+$more", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 3.dp))
                    }
                }
            }
        }
    }
}

/** Keeps a pager and [AgendaController.selected] in step: a swipe moves the date, a new date moves the pager. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncPager(pager: PagerState, targetPage: () -> Int, onSettled: (Int) -> Unit) {
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { onSettled(it) }
    }
    val target = targetPage()
    LaunchedEffect(target) {
        if (pager.settledPage != target && !pager.isScrollInProgress) pager.scrollToPage(target)
    }
}

// ---- week and day ----

private val HOUR = 52.dp
private val GUTTER = 44.dp

/** Semaine (7 columns) or Jour (1): all-day strip, hour grid, current-time line; swiped between periods. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TimeGridView(c: AgendaController, sources: AgendaSources, today: LocalDate, h24: Boolean, days: Int) {
    val base = remember(days, c.weekStart) { if (days == 7) AgendaLayout.weekStartOf(LocalDate.now(), c.weekStart) else LocalDate.now() }
    fun startOf(d: LocalDate) = if (days == 7) AgendaLayout.weekStartOf(d, c.weekStart) else d
    fun pageOf(d: LocalDate) = MID + (ChronoUnit.DAYS.between(base, startOf(d)) / days).toInt()
    val pager = rememberPagerState(initialPage = pageOf(c.selected)) { PAGES }
    SyncPager(pager, { pageOf(c.selected) }) { page ->
        val start = base.plusDays((page - MID).toLong() * days)
        if (startOf(c.selected) != start) {
            // a week keeps the selected weekday; a day is that day
            c.selected = if (days == 7) start.plusDays(ChronoUnit.DAYS.between(startOf(c.selected), c.selected)) else start
        }
    }
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR.toPx() }
    // the vertical position is shared by the pages: swiping keeps the same hours in view
    var scrollY by remember { mutableIntStateOf(((java.time.LocalTime.now().hour - 1).coerceAtLeast(0) * hourPx).toInt()) }
    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page ->
        val start = base.plusDays((page - MID).toLong() * days)
        TimeGridPage(c, sources, (0 until days).map { start.plusDays(it.toLong()) }, today, h24, scrollY, isCurrent = { pager.currentPage == page }) { scrollY = it }
    }
}

@Composable
private fun TimeGridPage(
    c: AgendaController, sources: AgendaSources, days: List<LocalDate>, today: LocalDate, h24: Boolean,
    initialScroll: Int, isCurrent: () -> Boolean, onScroll: (Int) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val scroll = rememberScrollState(initialScroll)
    LaunchedEffect(scroll) { snapshotFlow { scroll.value }.collect { if (isCurrent()) onScroll(it) } }
    LaunchedEffect(initialScroll) { if (!isCurrent() && scroll.value != initialScroll) scroll.scrollTo(initialScroll) }
    val entries by produceState<List<Entry>>(emptyList(), sources, days) {
        value = c.entries(sources, days.first(), days.last().plusDays(1)).entries
    }
    val byDay = remember(entries, days) { AgendaLayout.perDay(entries, days) }
    val now by produceState(Instant.now()) {
        while (true) { kotlinx.coroutines.delay(60_000); value = Instant.now() }
    }
    Column(Modifier.fillMaxSize()) {
        // day headers
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(GUTTER))
            days.forEach { d ->
                Column(Modifier.weight(1f).clickable(enabled = days.size > 1) { c.selected = d; c.changeView(AgendaView.DAY) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(AgendaText.weekdayShort(d.dayOfWeek), style = MaterialTheme.typography.labelSmall,
                        color = if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(30.dp).clip(CircleShape).background(if (d == today) MaterialTheme.colorScheme.primary else Color.Transparent),
                        contentAlignment = Alignment.Center) {
                        Text(d.dayOfMonth.toString(), fontWeight = FontWeight.SemiBold,
                            color = if (d == today) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        // all-day strip
        val strip = days.map { d -> byDay[d].orEmpty().filter { AgendaLayout.isStrip(it) } }
        if (strip.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Spacer(Modifier.width(GUTTER))
                strip.forEachIndexed { i, list ->
                    val (shown, more) = AgendaLayout.cell(list, if (days.size == 1) 4 else 2)
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        shown.forEach { e ->
                            Text(entryTitle(e), color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(agendaChipColor(e.color))
                                    .clickable { c.open(e) }.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                        if (more > 0) Text("+$more", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { c.selected = days[i]; c.changeView(AgendaView.DAY) })
                    }
                }
            }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().height(HOUR * 24)) {
                Column(Modifier.width(GUTTER)) {
                    (0 until 24).forEach { h ->
                        Box(Modifier.height(HOUR).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                            if (h > 0) Text(AgendaText.time(java.time.LocalTime.of(h, 0), h24).let { if (h24) it else it.replace(":00", "") },
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.offset(y = (-7).dp).padding(end = 6.dp))
                        }
                    }
                }
                days.forEach { d ->
                    DayColumn(c, d, byDay[d].orEmpty(), zone, h24, AgendaLayout.nowMinute(now, d, zone), Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun DayColumn(c: AgendaController, day: LocalDate, entries: List<Entry>, zone: ZoneId, h24: Boolean, nowMinute: Int?, modifier: Modifier) {
    val placed = remember(entries, day) { AgendaLayout.placeTimed(entries, day, zone) }
    val line = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR.toPx() }
    BoxWithConstraints(
        modifier.border(0.5.dp, line.copy(alpha = 0.4f))
            .pointerInput(day) {
                detectTapGestures { o ->
                    val hour = (o.y / hourPx).toInt().coerceIn(0, 23)
                    c.newEvent(day, day.atTime(hour, 0))
                }
            },
    ) {
        val width = maxWidth
        (1 until 24).forEach { h ->
            Box(Modifier.offset(y = HOUR * h).fillMaxWidth().height(0.5.dp).background(line.copy(alpha = 0.5f)))
        }
        placed.forEach { p ->
            val top = HOUR * (p.startMin / 60f)
            val height = HOUR * ((p.endMin - p.startMin) / 60f)
            val w = width / p.columns
            Column(
                Modifier.offset(x = w * p.column, y = top).width(w).height(height).padding(1.dp)
                    .clip(RoundedCornerShape(5.dp)).background(agendaChipColor(p.entry.color)).clickable { c.open(p.entry) }
                    .padding(horizontal = 3.dp, vertical = 2.dp),
            ) {
                Text(entryTitle(p.entry), color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = if (p.endMin - p.startMin >= 60) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (p.endMin - p.startMin >= 45) {
                    // a week column only has room for the start: the full range was cut to "07:00 -"
                    val time = AgendaText.timeLine(p.entry, zone, h24, c.eventZoneTimes)
                    Text(if (w < 96.dp) time.substringBefore(" - ") else time, color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        if (nowMinute != null) {
            Box(Modifier.offset(y = HOUR * (nowMinute / 60f) - 1.dp).fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.error))
        }
    }
}
