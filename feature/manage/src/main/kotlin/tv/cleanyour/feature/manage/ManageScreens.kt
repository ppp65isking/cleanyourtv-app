@file:OptIn(ExperimentalMaterial3Api::class)

package tv.cleanyour.feature.manage

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import tv.cleanyour.core.engine.PauseReason
import tv.cleanyour.core.flows.AppConfirm
import tv.cleanyour.core.flows.AppSettingsIntent
import tv.cleanyour.core.flows.AppSettingsState
import tv.cleanyour.core.flows.CastApp
import tv.cleanyour.core.flows.CastResult
import tv.cleanyour.core.flows.CastTv
import tv.cleanyour.core.flows.UiStyle
import tv.cleanyour.core.flows.UserProfile
import tv.cleanyour.core.flows.LeftoversIntent
import tv.cleanyour.core.flows.LeftoversState
import tv.cleanyour.core.flows.ManageIntent
import tv.cleanyour.core.flows.ManageSection
import tv.cleanyour.core.flows.ManageState
import tv.cleanyour.core.flows.Confirmations
import tv.cleanyour.core.flows.OpenWrite
import tv.cleanyour.core.flows.OpenWriteAction
import tv.cleanyour.core.flows.Reach
import tv.cleanyour.core.flows.OffGroup
import tv.cleanyour.core.flows.SwitchedOffIntent
import tv.cleanyour.core.flows.SwitchedOffState
import tv.cleanyour.core.flows.TvSettingsIntent
import tv.cleanyour.core.flows.TvSettingsState
import tv.cleanyour.core.flows.UpdateCheck
import tv.cleanyour.core.model.Level
import tv.cleanyour.core.model.PackageName
import tv.cleanyour.core.planner.PlanKind
import tv.cleanyour.ui.design.component.ActionColumn
import tv.cleanyour.ui.design.component.Banner
import tv.cleanyour.ui.design.component.BannerTone
import tv.cleanyour.ui.design.component.CheckLine
import tv.cleanyour.ui.design.component.ChoiceList
import tv.cleanyour.ui.design.component.ChoiceOption
import tv.cleanyour.ui.design.component.CytBottomSheet
import tv.cleanyour.ui.design.component.CytIconButton
import tv.cleanyour.ui.design.component.CytTextField
import tv.cleanyour.ui.design.component.CytTopBar
import tv.cleanyour.ui.design.component.DiffGroup
import tv.cleanyour.ui.design.component.DiffGroupSection
import tv.cleanyour.ui.design.component.DiffItem
import tv.cleanyour.ui.design.component.Gap
import tv.cleanyour.ui.design.component.Lead
import tv.cleanyour.ui.design.component.LinkRow
import tv.cleanyour.ui.design.component.ListDivider
import tv.cleanyour.ui.design.component.ListGroup
import tv.cleanyour.ui.design.component.ListRow
import tv.cleanyour.ui.design.component.MonospaceBlock
import tv.cleanyour.ui.design.component.PrimaryButton
import tv.cleanyour.ui.design.component.ProgressBar
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.component.ScreenTitle
import tv.cleanyour.ui.design.component.SecondaryButton
import tv.cleanyour.ui.design.component.SheetHeader
import tv.cleanyour.ui.design.component.Spinner
import tv.cleanyour.ui.design.component.StepCard
import tv.cleanyour.ui.design.component.StopCard
import tv.cleanyour.ui.design.component.SwitchRow
import tv.cleanyour.ui.design.component.TertiaryButton
import tv.cleanyour.ui.design.component.TvCard
import tv.cleanyour.ui.design.component.TvStatus
import tv.cleanyour.ui.design.component.TvStatusChip
import tv.cleanyour.ui.design.component.rememberPng
import tv.cleanyour.ui.design.icon.CytIcons
import tv.cleanyour.ui.design.theme.CytTheme
import tv.cleanyour.ui.design.theme.Glow

// Screens M1, M1a, M4, M5, M7 and M8 (PRODUCT-SPEC 4, UX 4.3, 4.11, 4.13, 6.1), built from the
// :ui:design components (ui/design/API.md). Each sheet keeps its body in an internal *Content
// composable, so the screenshot tests can draw it on SheetSurface (a modal window is not captured).

fun shortDate(ms: Long, locale: java.util.Locale): String = SimpleDateFormat("d MMM", locale).format(Date(ms))
fun shortDateTime(ms: Long, locale: java.util.Locale): String = SimpleDateFormat("d MMM, HH:mm", locale).format(Date(ms))

private val titleTop = Modifier.padding(top = 20.dp)

// M1 Manage: the app's home once a TV exists. Nothing is more than two taps away.
@Composable
fun ManageScreen(state: ManageState, onIntent: (ManageIntent) -> Unit, modifier: Modifier = Modifier) {
    val tv = state.current
    val locale = LocalConfiguration.current.locales[0]
    ScreenScaffold(Glow.Tv, modifier, topBar = {
        CytTopBar(
            center = { if (tv != null) TvStatusChip(tv.name, tv.chip.toTvStatus()) },
            trailing = { CytIconButton(CytIcons.Settings, stringResource(R.string.manage_settings_cd), { onIntent(ManageIntent.AppSettings) }) },
        )
    }) {
        ScreenTitle(stringResource(R.string.manage_title), titleTop)
        if (state.offline) {
            Banner(
                stringResource(R.string.manage_offline_banner), BannerTone.NEUTRAL, Modifier.padding(top = 16.dp), icon = CytIcons.WifiOff,
                actionLabel = if (state.reconnecting) null else stringResource(R.string.manage_reconnect), onAction = { onIntent(ManageIntent.Reconnect) },
            )
            if (state.reconnecting) LiveLine(stringResource(R.string.manage_reconnecting))
        }
        if (tv != null) {
            val room = tv.room
            val stateWord = stringResource(if (tv.clean) R.string.manage_state_clean else R.string.manage_state_original)
            val checked = msg(R.string.manage_checked, "date" to shortDate(tv.checkedAtMs, locale))
            TvCard(
                name = if (room != null) msg(R.string.manage_tv_title, "tv" to tv.name, "room" to room) else msg(R.string.manage_tv_title_noroom, "tv" to tv.name),
                statusLine = "$stateWord. $checked.",
                thumbnail = rememberPng(tv.thumbnail),
                thumbnailDescription = msg(R.string.manage_thumbnail_cd, "tv" to tv.name),
                modifier = Modifier.padding(top = 20.dp),
                level = stringResource(if (tv.level == Level.CUSTOM) R.string.manage_level_custom else R.string.manage_level_simple),
                onClick = { onIntent(ManageIntent.RefreshThumbnail) },
            )
            // Navigation into the wizard, whose own "Make it clean" is the guarded action (R5-W8 O1).
            // 0.4.7 (PM 11): "Run Setup again" stays visible for every TV, the one name for the way back in.
            // 0.4.7 (StockHomeBack): the TV turned its own home screen back on, so Run Setup again is the way back.
            if (!tv.clean || tv.stockHomeBack) PrimaryButton(stringResource(R.string.manage_clean_again), { onIntent(ManageIntent.CleanAgain) }, Modifier.padding(top = 16.dp), guardObscured = false)
            else SecondaryButton(stringResource(R.string.manage_clean_again), { onIntent(ManageIntent.CleanAgain) }, Modifier.padding(top = 16.dp))
            // 0.3.2 B1: an unlisted TV (no exact profile) invites the person to help add it.
            // 0.4.7 (must 4): once its report was sent, the banner says when and that the issue is
            // where news comes; sending again stays one tap away.
            if (tv.helpAddTv) {
                val sent = tv.reportSentAtMs
                Banner(if (sent != null) msg(R.string.manage_report_sent_banner, "date" to shortDate(sent, locale)) else stringResource(R.string.manage_help_add_tv_banner),
                    BannerTone.INFO, Modifier.padding(top = 16.dp),
                    actionLabel = stringResource(if (sent != null) R.string.manage_send_again else R.string.manage_help_add_tv), onAction = { onIntent(ManageIntent.HelpAddTv) })
            }
            // 0.4.8 (Restarts): what the app counted across this TV's restarts. A restart that failed comes first,
            // with Report a problem; else, once one held, "Tell us it worked" opens the confirmation's preview.
            tv.restarts?.let { r -> RestartsBanner(r, onIntent) }
            // 0.4.6: on this connect a setup wizard claims Home while the TV's own home screen is off
            // (WizardHome). The way back sits above the rows, like the drift banner's "Restore mine";
            // its button writes to the TV, so it is guarded.
            if (tv.wizardHome) {
                val title = stringResource(R.string.manage_wizard_home_title)
                val body = stringResource(R.string.manage_wizard_home_body)
                Banner(
                    buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(title) }; append("\n"); append(body) },
                    BannerTone.WARNING, Modifier.padding(top = 16.dp),
                    actionLabel = if (state.switchingHome) null else stringResource(R.string.manage_wizard_home_button),
                    onAction = { onIntent(ManageIntent.SwitchStockHomeOn) }, guardAction = true,
                )
                if (state.switchingHome) LiveLine(stringResource(R.string.manage_wizard_home_working))
            }
            ListGroup(Modifier.padding(top = 16.dp)) {
                // 0.3.3 D2: without the new home screen there is no dock to name; the row opens the reason.
                // 0.4.7 (UX finding 8): installed but not in use says the TV's own is in use; and when the
                // TV turned its own back on behind this app (StockHomeBack), the row says that.
                ListRow(stringResource(R.string.manage_row_home),
                    subtitle = when {
                        tv.stockHomeBack -> stringResource(R.string.manage_row_home_stock_back)
                        tv.homeHelperOn -> stringResource(R.string.manage_row_home_helper)   // RFC-HELPER-1 (test build)
                        tv.ownHome -> stringResource(R.string.manage_row_home_own)
                        tv.homeInstalledNotInUse -> stringResource(R.string.manage_row_home_not_in_use)
                        else -> tv.dockNames.joinToString(", ").ifEmpty { null }
                    },
                    onClick = { onIntent(ManageIntent.Open(ManageSection.HOME_SCREEN)) })
                ListDivider()
                // STORES-PLAN 1: the Play build has no add-ons, so no row that leads to them.
                // UX finding 9: without the new home screen a card shows as an app, and the row says so.
                if (state.addOns) {
                    ListRow(stringResource(R.string.manage_row_cards), subtitle = if ((tv.ownHome || tv.homeInstalledNotInUse) && !tv.homeHelperOn) stringResource(R.string.manage_row_cards_own_home) else null,
                        onClick = { onIntent(ManageIntent.Open(ManageSection.MODULES)) })
                    ListDivider()
                }
                // WP4 (C6): what the list for this TV now keeps on, as one line into Switched off.
                if (tv.betterBackOn > 0) {
                    ListRow(msg(R.string.manage_row_better_back_on, "count" to tv.betterBackOn), onClick = { onIntent(ManageIntent.Open(ManageSection.SWITCHED_OFF)) })
                    ListDivider()
                }
                ListRow(msg(R.string.manage_row_switched_off, "count" to tv.switchedOff), subtitle = stringResource(R.string.manage_row_switched_off_sub),
                    onClick = { onIntent(ManageIntent.Open(ManageSection.SWITCHED_OFF)) })
                ListDivider()
                ListRow(stringResource(R.string.manage_row_leftovers), subtitle = stringResource(R.string.manage_row_leftovers_sub),
                    onClick = { onIntent(ManageIntent.Open(ManageSection.LEFTOVERS)) })
                ListDivider()
                ListRow(stringResource(R.string.manage_row_restore), subtitle = tv.restorePointAtMs?.let { msg(R.string.manage_row_restore_sub, "date" to shortDateTime(it, locale)) },
                    onClick = { onIntent(ManageIntent.Open(ManageSection.RESTORE_POINT)) })
                ListDivider()
                ListRow(stringResource(R.string.manage_row_tvsettings), onClick = { onIntent(ManageIntent.Open(ManageSection.TV_SETTINGS)) })
            }
        }
        val others = state.cards.filter { it.id != tv?.id }
        ListGroup(Modifier.padding(top = 12.dp, bottom = 16.dp)) {
            others.forEach { other ->
                ListRow(other.name, subtitle = other.room, onClick = { onIntent(ManageIntent.Select(other.id)) })
                ListDivider()
            }
            LinkRow(stringResource(R.string.manage_add_tv), { onIntent(ManageIntent.SetUpAnother) }, chevron = false, minHeight = 56.dp)
        }
    }
}

// 0.4.8: the restart count's banner on Manage. Neither action writes to the TV: one opens the preview, the other the
// prefilled problem form.
@Composable
private fun RestartsBanner(r: tv.cleanyour.core.flows.RestartTally, onIntent: (ManageIntent) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    when {
        r.lastFailed && r.failed > 0 -> {
            val title = stringResource(R.string.manage_restart_failed)
            Banner(
                buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(title) }; append("\n"); append(stringResource(R.string.manage_restart_failed_body)) },
                BannerTone.WARNING, Modifier.padding(top = 16.dp),
                actionLabel = stringResource(R.string.manage_restart_report), onAction = { onIntent(ManageIntent.ReportRestartFailed(title)) },
            )
        }
        r.held > 0 -> {
            val told = r.toldAtMs
            val body = if (told != null) msg(R.string.manage_restarts_told, "date" to shortDate(told, locale)) else stringResource(R.string.manage_restarts_ask)
            val action = when {
                told == null -> stringResource(R.string.manage_restarts_tell)
                r.held > r.toldHeld -> stringResource(R.string.manage_restarts_tell_again)
                else -> null
            }
            Banner(
                buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(msg(R.string.manage_restarts_held, "count" to r.held)) }; append("\n"); append(body) },
                BannerTone.SUCCESS, Modifier.padding(top = 16.dp),
                actionLabel = action, onAction = { onIntent(ManageIntent.TellUsItWorked) },
            )
        }
    }
}

// A Manage write that paused left its run open (R5-W8 O2): every later write on that TV would be
// refused, so the app lays this card over whatever screen asked. A setup run opens the wizard's
// resume card instead, which owns the home screen check.
@Composable
// 0.4.6: `onReport` gets the card's title, for the prefilled GitHub form (ReportContext).
fun OpenWriteSheet(open: OpenWrite, onAction: (OpenWriteAction) -> Unit, onOpenSetup: () -> Unit, onDismiss: () -> Unit, onReport: (String) -> Unit = {}) {
    // 0.4.6: scrolls, so "Report a problem" under the card stays reachable at 200% font.
    CytBottomSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) { OpenWriteContent(open, onAction, onOpenSetup, onDismiss, onReport = onReport) }
    }
}

@Composable
internal fun ColumnScope.OpenWriteContent(
    open: OpenWrite, onAction: (OpenWriteAction) -> Unit, onOpenSetup: () -> Unit, onDismiss: () -> Unit,
    initiallyAsking: OpenWriteAction? = null, onReport: (String) -> Unit = {},
) {
    val setup = open.kind == PlanKind.SETUP
    // QA of 0.4.5: a run paused STALE (the TV changed after we read it) is checked again, never
    // carried on; with no step done it says nothing was changed and offers no undo.
    val stale = !setup && open.reason == PauseReason.STALE
    val body = when {
        open.notTaken -> stringResource(R.string.openwrite_not_taken)
        setup -> msg(R.string.openwrite_body_setup, "tv" to open.tvName)
        stale && open.nothingDone -> stringResource(R.string.openwrite_body_stale_nothing)
        stale -> stringResource(R.string.openwrite_body_stale)
        open.reason == PauseReason.CONNECTION_LOST -> stringResource(R.string.openwrite_body_lost)
        else -> stringResource(R.string.openwrite_body)
    }
    val title = msg(if (stale) R.string.openwrite_title_stale else R.string.openwrite_title, "tv" to open.tvName)
    // 0.4.7 (UX finding 19): a look that did not reach the TV (an ApplyTheme run paused, not STALE)
    // says so by name, with "Send it again" and "Undo".
    val look = !setup && !stale && open.kind == PlanKind.THEME && open.tookHome == null
    Gap(8.dp)
    if (look) {
        var asking by rememberSaveable(open.planId.value) { mutableStateOf(initiallyAsking) }
        val pending = asking
        val question = pending?.let { Confirmations.forOpenWrite(it) }
        if (pending != null && question != null && !open.busy) {
            ConfirmQuestionFor(question, onConfirm = { asking = null; onAction(pending) }, onCancel = { asking = null })
            return
        }
        fun act(a: OpenWriteAction) { if (Confirmations.forOpenWrite(a) != null) asking = a else onAction(a) }
        val lookTitle = msg(R.string.openwrite_title_look, "tv" to open.tvName)
        StopCard(lookTitle, if (open.notTaken) stringResource(R.string.openwrite_not_taken) else stringResource(R.string.openwrite_body_look),
            primaryLabel = if (open.busy) null else stringResource(R.string.openwrite_send_again), onPrimary = { act(OpenWriteAction.CARRY_ON) },
            secondaryLabel = if (open.busy) null else stringResource(R.string.action_undo), onSecondary = { act(OpenWriteAction.UNDO) },
            tertiaryLabel = if (open.busy) null else stringResource(R.string.openwrite_stop), onTertiary = { act(OpenWriteAction.STOP_HERE) },
            detailsLabel = if (open.busy) null else stringResource(R.string.openwrite_report), onDetails = { onReport(lookTitle) },
            busyText = if (open.busy) stringResource(R.string.openwrite_busy) else null)
    } else if (setup) {
        StopCard(title, body,
            primaryLabel = stringResource(R.string.openwrite_open_setup), onPrimary = onOpenSetup,
            tertiaryLabel = stringResource(R.string.openwrite_later), onTertiary = onDismiss,
            // 0.3.2 B2: a setup that would not finish is worth a report (issue #2).
            detailsLabel = stringResource(R.string.openwrite_report), onDetails = { onReport(title) },
            primaryGuarded = false)   // navigation: it only opens the wizard's resume card
    } else if (open.tookHome != null) {
        // 0.4.6: the app this put back switched on took the Home button. The buttons say exactly what
        // they do, so neither asks first: switch it off again (the run's undo) or keep it (stop here).
        val app = open.tookHome.orEmpty()
        StopCard(msg(R.string.openwrite_title_took_home, "app" to app),
            if (open.notTaken) stringResource(R.string.openwrite_not_taken) else msg(R.string.openwrite_body_took_home, "app" to app),
            primaryLabel = if (open.busy) null else stringResource(R.string.openwrite_switch_off_again), onPrimary = { onAction(OpenWriteAction.UNDO) },
            tertiaryLabel = if (open.busy) null else stringResource(R.string.openwrite_keep_it), onTertiary = { onAction(OpenWriteAction.STOP_HERE) },
            busyText = if (open.busy) stringResource(R.string.openwrite_busy) else null)
    } else {
        // "Undo what we did" and "Stop here" ask first, in this same sheet; the action is sent on yes.
        var asking by rememberSaveable(open.planId.value) { mutableStateOf(initiallyAsking) }
        val pending = asking
        val question = pending?.let { Confirmations.forOpenWrite(it) }
        if (pending != null && question != null && !open.busy) {
            ConfirmQuestionFor(question, onConfirm = { asking = null; onAction(pending) }, onCancel = { asking = null })
            return
        }
        fun act(a: OpenWriteAction) { if (Confirmations.forOpenWrite(a) != null) asking = a else onAction(a) }
        StopCard(title, body,
            primaryLabel = if (open.busy) null else stringResource(if (stale) R.string.openwrite_check_again else R.string.openwrite_carry_on), onPrimary = { act(OpenWriteAction.CARRY_ON) },
            secondaryLabel = if (open.busy || (stale && open.nothingDone)) null else stringResource(R.string.openwrite_undo), onSecondary = { act(OpenWriteAction.UNDO) },
            tertiaryLabel = if (open.busy) null else stringResource(R.string.openwrite_stop), onTertiary = { act(OpenWriteAction.STOP_HERE) },
            // 0.4.6: a paused change is worth a report too, with the card and the step it paused at.
            detailsLabel = if (open.busy) null else stringResource(R.string.openwrite_report), onDetails = { onReport(title) },
            busyText = if (open.busy) stringResource(R.string.openwrite_busy) else null)
    }
}

// M4 Switched off: "Put back" is one op with a 5 s undo snackbar (the app shows the snackbar).
@Composable
fun SwitchedOffScreen(state: SwitchedOffState, onIntent: (SwitchedOffIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    ScreenScaffold(Glow.Tv, modifier, topBar = { CytTopBar(onBack = onBack) }) {
        ScreenTitle(msg(R.string.switchedoff_title, "count" to state.count), titleTop)
        if (state.loading) {
            Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) { Spinner(size = 24.dp) }
            return@ScreenScaffold
        }
        if (state.groups.isEmpty() && state.askGroups.isEmpty()) {
            Lead(stringResource(R.string.switchedoff_empty), Modifier.padding(top = 12.dp))
            Body(stringResource(R.string.switchedoff_empty_sub), Modifier.padding(top = 8.dp))
        } else {
            Lead(stringResource(R.string.switchedoff_intro), Modifier.padding(top = 12.dp))
        }
        state.groups.forEach { g ->
            // WP4 (C6): "Better back on" at the top, each row with our reason and the ordinary PutBack.
            if (g.id == OffGroup.BETTER_BACK_ON) { BetterBackOnGroup(g, state.busy, onIntent); return@forEach }
            SectionLabel(g.title.ifEmpty { stringResource(R.string.switchedoff_group_other) })
            ListGroup {
                g.rows.forEachIndexed { i, r ->
                    if (i > 0) ListDivider()
                    val cd = msg(R.string.switchedoff_put_back_cd, "name" to r.name)
                    ListRow(r.name, subtitle = r.description, trailing = {
                        if (state.busy == r.pkg) Box(Modifier.padding(horizontal = 16.dp)) { Spinner() }
                        else TertiaryButton(stringResource(R.string.switchedoff_put_back), { onIntent(SwitchedOffIntent.PutBack(r.pkg)) },
                            Modifier.semantics { contentDescription = cd }, enabled = state.busy == null)
                    })
                }
            }
        }
        // Custom adds the ask groups with Off / Kept pills and the package names in grey (D2).
        state.askGroups.forEach { g ->
            val group = DiffGroup(g.id, g.title, g.rows.map { DiffItem(it.pkg.value, it.name, it.description.orEmpty(), it.pkg.value, it.off) })
            DiffGroupSection(
                group, { item -> if (state.busy == null) onIntent(SwitchedOffIntent.SetOff(PackageName(item.key), !item.off)) },
                Modifier.padding(top = 20.dp), showPackageNames = true,
            )
        }
        Gap(24.dp)
    }
}

@Composable
private fun BetterBackOnGroup(g: OffGroup, busy: PackageName?, onIntent: (SwitchedOffIntent) -> Unit) {
    SectionLabel(stringResource(R.string.switchedoff_group_better))
    ListGroup {
        g.rows.forEachIndexed { i, r ->
            if (i > 0) ListDivider()
            val cd = msg(R.string.switchedoff_switch_back_on_cd, "name" to r.name)
            val kept = stringResource(R.string.switchedoff_better_kept)
            ListRow(r.name, subtitle = listOfNotNull(r.reason, kept).joinToString(" "), trailing = {
                if (busy == r.pkg) Box(Modifier.padding(horizontal = 16.dp)) { Spinner() }
                else TertiaryButton(stringResource(R.string.switchedoff_switch_back_on), { onIntent(SwitchedOffIntent.PutBack(r.pkg)) },
                    Modifier.semantics { contentDescription = cd }, enabled = busy == null)
            })
        }
    }
}

// M5 Check for leftovers (D22).
@Composable
fun LeftoversScreen(state: LeftoversState, onIntent: (LeftoversIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val bottom: (@Composable ColumnScope.() -> Unit)? = when (state) {
        is LeftoversState.Found -> ({
            ActionColumn {
                PrimaryButton(stringResource(R.string.leftovers_switch_off), { onIntent(LeftoversIntent.SwitchOff) })
                TertiaryButton(stringResource(R.string.leftovers_not_now), { onIntent(LeftoversIntent.NotNow) })
            }
        })
        LeftoversState.Failed -> ({ SecondaryButton(stringResource(R.string.leftovers_title), { onIntent(LeftoversIntent.CheckAgain) }) })
        else -> null
    }
    ScreenScaffold(Glow.Tv, modifier, topBar = { CytTopBar(onBack = onBack) }, bottomBar = bottom) {
        ScreenTitle(stringResource(R.string.leftovers_title), titleTop)
        when (state) {
            LeftoversState.Checking -> LiveLine(stringResource(R.string.leftovers_checking))
            is LeftoversState.Found -> {
                Lead(msg(R.string.leftovers_found, "count" to state.names.size), Modifier.padding(top = 12.dp))
                ListGroup(Modifier.padding(top = 20.dp)) {
                    state.names.forEachIndexed { i, name ->
                        if (i > 0) ListDivider()
                        ListRow(name)
                    }
                }
            }
            LeftoversState.Nothing -> Lead(stringResource(R.string.leftovers_none), Modifier.padding(top = 12.dp))
            is LeftoversState.Working -> {
                LiveLine(stringResource(R.string.leftovers_checking))
                ProgressBar(state.percent / 100f, Modifier.padding(top = 16.dp))
            }
            is LeftoversState.Done -> CheckLine(stringResource(R.string.leftovers_done), Modifier.padding(top = 20.dp, start = 4.dp))
            LeftoversState.Failed -> Lead(stringResource(R.string.leftovers_error), Modifier.padding(top = 12.dp))
        }
    }
}

// M7 TV settings (D46; "Keep debugging on" is Expert, 0.2, and absent). The level choice carries
// the one-line explanation of each level.
@Composable
fun TvSettingsScreen(state: TvSettingsState, onIntent: (TvSettingsIntent) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    var name by rememberSaveable(state.name) { mutableStateOf(state.name) }
    var room by rememberSaveable(state.room) { mutableStateOf(state.room.orEmpty()) }
    ScreenScaffold(Glow.Tv, modifier, topBar = { CytTopBar(onBack = onBack) }) {
        ScreenTitle(stringResource(R.string.tvsettings_title), titleTop)
        SectionLabel(stringResource(R.string.tvsettings_name_room))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CytTextField(name, { name = it.take(40) }, stringResource(R.string.tvsettings_name_label))
            CytTextField(room, { room = it.take(40) }, stringResource(R.string.tvsettings_room_label))
        }
        if (name != state.name || room != state.room.orEmpty()) {
            SecondaryButton(stringResource(R.string.action_done), { onIntent(TvSettingsIntent.Rename(name, room)) }, Modifier.padding(top = 12.dp), enabled = name.isNotBlank())
        }
        SectionLabel(stringResource(R.string.tvsettings_level_label))
        ChoiceList(
            listOf(
                ChoiceOption(Level.SIMPLE.name, stringResource(R.string.tvsettings_level_simple), stringResource(R.string.manage_level_simple_note), icon = null),
                ChoiceOption(Level.CUSTOM.name, stringResource(R.string.tvsettings_level_custom), stringResource(R.string.manage_level_custom_note), icon = null),
            ),
            state.level.name,
            { onIntent(TvSettingsIntent.SetLevel(Level.valueOf(it.key))) },
        )
        Body(stringResource(R.string.tvsettings_level_note), Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp))
        ListGroup(Modifier.padding(top = 20.dp)) {
            ListRow(stringResource(R.string.tvsettings_address), subtitle = state.lastSeenAtMs?.let { msg(R.string.tvsettings_last_seen, "date" to shortDateTime(it, locale)) },
                value = state.address)
        }
        SecondaryButton(stringResource(R.string.tvsettings_forget), { onIntent(TvSettingsIntent.AskForget) }, Modifier.padding(top = 24.dp), enabled = !state.forgetting)
        Body(stringResource(R.string.tvsettings_forget_explain), Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp, bottom = 24.dp))
    }
    if (state.confirmingForget) CytBottomSheet({ onIntent(TvSettingsIntent.CancelForget) }) { ForgetContent(state.name, onIntent) }
}

@Composable
internal fun ColumnScope.ForgetContent(tvName: String, onIntent: (TvSettingsIntent) -> Unit) {
    SheetHeader(msg(R.string.tvsettings_forget_title, "tv" to tvName))
    Body(stringResource(R.string.tvsettings_forget_explain), Modifier.padding(top = 8.dp))
    ActionColumn(Modifier.padding(top = 20.dp)) {
        PrimaryButton(stringResource(R.string.tvsettings_forget_confirm), { onIntent(TvSettingsIntent.Forget) })
        TertiaryButton(stringResource(R.string.action_cancel), { onIntent(TvSettingsIntent.CancelForget) }, quiet = true)
    }
}

// M8 App settings (D12, F2, F13). The Reset my key confirmation is FLAG_SECURE (D35, set by the app).
@Composable
// RFC-REACH-1: `details` and `onDetails` drive "Connection details" (the app collects and copies or shares the block).
fun AppSettingsScreen(state: AppSettingsState, onIntent: (AppSettingsIntent) -> Unit, onBack: () -> Unit, onOpenRelease: () -> Unit, modifier: Modifier = Modifier,
    details: DetailsUi = DetailsUi(), onDetails: (share: Boolean) -> Unit = {}) {
    var licences by rememberSaveable { mutableStateOf(false) }
    var connection by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    var newProfile by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    ScreenScaffold(Glow.Tv, modifier, topBar = { CytTopBar(onBack = onBack) }) {
        ScreenTitle(stringResource(R.string.appsettings_title), titleTop)
        ListGroup(Modifier.padding(top = 20.dp)) {
            SwitchRow(stringResource(R.string.appsettings_large_text), state.prefs.largeTextFollowsSystem, { onIntent(AppSettingsIntent.SetLargeText(it)) },
                subtitle = stringResource(R.string.appsettings_large_text_note))
            ListDivider()
            SwitchRow(stringResource(R.string.appsettings_haptics), state.prefs.haptics, { onIntent(AppSettingsIntent.SetHaptics(it)) },
                subtitle = stringResource(R.string.appsettings_haptics_note))
        }
        // 0.2: the choosable interface. The choice applies at once (AppPrefs.uiStyle is a
        // live flow MainActivity collects) and is kept in settings.txt.
        SectionLabel(stringResource(R.string.appsettings_interface))
        ChoiceList(
            options = listOf(
                ChoiceOption(UiStyle.CLASSIC.name, stringResource(R.string.appsettings_interface_classic), stringResource(R.string.appsettings_interface_classic_note), icon = null),
                ChoiceOption(UiStyle.LIQUID_GLASS.name, stringResource(R.string.appsettings_interface_liquid), stringResource(R.string.appsettings_interface_liquid_note), icon = null),
            ),
            selectedKey = state.prefs.uiStyle.name,
            onSelect = { onIntent(AppSettingsIntent.SetUiStyle(UiStyle.valueOf(it.key))) },
            modifier = Modifier.padding(top = 12.dp),
        )
        SectionLabel(stringResource(R.string.appsettings_about))
        ListGroup {
            ListRow(msg(R.string.appsettings_version, "version" to state.version))
            ListDivider()
            ListRow(stringResource(R.string.appsettings_launcher_pin_label))
            MonospaceBlock(
                listOf(msg(R.string.appsettings_launcher_pin_line, "package" to state.launcherPkg, "version" to state.launcherVersion, "digest" to state.launcherDigest)),
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            ListDivider()
            ListRow(stringResource(R.string.appsettings_key_label), subtitle = stringResource(when (state.keyBacking) {
                "STRONGBOX" -> R.string.appsettings_key_strongbox
                "TEE" -> R.string.appsettings_key_tee
                else -> R.string.appsettings_key_wrapped
            }))
            if (state.keyFingerprint.isNotEmpty()) MonospaceBlock(listOf(state.keyFingerprint), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            // STORES-PLAN 1: the Play build is updated by Google Play and has no update check.
            if (state.updateCheck) {
                ListDivider()
                val update = state.update
                ListRow(stringResource(R.string.appsettings_check_updates), subtitle = when (update) {
                    UpdateCheck.Checking -> stringResource(R.string.appsettings_checking)
                    is UpdateCheck.Available -> msg(R.string.appsettings_update_available, "version" to update.version)
                    UpdateCheck.Latest -> stringResource(R.string.appsettings_up_to_date)
                    UpdateCheck.Failed -> stringResource(R.string.appsettings_update_error)
                    UpdateCheck.Idle -> null
                }, trailing = if (update == UpdateCheck.Checking) ({ Spinner() }) else null,
                    onClick = { if (update is UpdateCheck.Available) onOpenRelease() else onIntent(AppSettingsIntent.CheckUpdates) })
            }
        }
        // 0.2 Profiles: switch between saved setups. Creating one is a sheet with a name, an
        // optional TV and an optional style; switching applies the style and opens the TV.
        SectionLabel(stringResource(R.string.appsettings_profiles))
        if (state.profiles.isNotEmpty()) {
            ChoiceList(
                options = state.profiles.map { p -> ChoiceOption(p.id, p.name, profileSubtitle(p), icon = null) },
                selectedKey = state.activeProfileId,
                onSelect = { onIntent(AppSettingsIntent.SwitchProfile(it.key)) },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        ListGroup(Modifier.padding(top = 12.dp)) {
            ListRow(stringResource(R.string.appsettings_profile_add), onClick = { newProfile = true })
            if (state.activeProfileId != null) {
                ListDivider()
                ListRow(stringResource(R.string.appsettings_profile_delete),
                    onClick = { onIntent(AppSettingsIntent.DeleteProfile(state.activeProfileId!!)) })
            }
        }
        // 0.2 Cast: open an app on a saved TV over DIAL, or hand screen casting to Android's
        // own Cast settings. The note says what this app cannot do, so nobody expects it to
        // be a Cast receiver.
        SectionLabel(stringResource(R.string.appsettings_cast))
        ListGroup {
            state.castTvs.forEachIndexed { i, tv ->
                if (i > 0) ListDivider()
                ListRow(stringResource(R.string.appsettings_cast_youtube), subtitle = tv.name,
                    trailing = if (state.castBusy == tv.id) ({ Spinner() }) else null,
                    onClick = { if (state.castBusy == null) onIntent(AppSettingsIntent.CastLaunch(tv.id, CastApp.YOUTUBE)) })
            }
            if (state.castTvs.isNotEmpty()) ListDivider()
            ListRow(stringResource(R.string.appsettings_cast_screen), subtitle = stringResource(R.string.appsettings_cast_screen_note),
                onClick = { openCastSettings(context) })
            state.castResult?.let { result ->
                ListDivider()
                ListRow(stringResource(when (result) {
                    CastResult.OPENED -> R.string.appsettings_cast_opened
                    CastResult.FAILED -> R.string.appsettings_cast_failed
                }), subtitle = state.castResultName)
            }
            ListDivider()
            ListRow(stringResource(R.string.appsettings_cast_note))
        }
        ListGroup(Modifier.padding(top = 12.dp)) {
            ListRow(stringResource(R.string.appsettings_licences), onClick = { licences = true })
            ListDivider()
            ListRow(stringResource(R.string.appsettings_privacy), onClick = { privacy = true })
            ListDivider()
            ListRow(stringResource(R.string.appsettings_report), subtitle = stringResource(R.string.appsettings_report_note),
                onClick = { onIntent(AppSettingsIntent.ReportProblem) })
            ListDivider()
            ListRow(stringResource(R.string.appsettings_details), subtitle = stringResource(R.string.appsettings_details_note),
                onClick = { connection = true })
        }
        ListGroup(Modifier.padding(top = 12.dp, bottom = 24.dp)) {
            ListRow(stringResource(R.string.appsettings_reset_key), subtitle = stringResource(R.string.appsettings_reset_key_note),
                onClick = { onIntent(AppSettingsIntent.Ask(AppConfirm.RESET_KEY)) })
            ListDivider()
            ListRow(stringResource(R.string.appsettings_delete_all), subtitle = stringResource(R.string.appsettings_delete_all_note),
                onClick = { onIntent(AppSettingsIntent.Ask(AppConfirm.DELETE_EVERYTHING)) })
        }
    }
    if (licences) CytBottomSheet({ licences = false }) { LicencesContent() }
    if (privacy) CytBottomSheet({ privacy = false }) { PrivacyContent(online = state.updateCheck || state.addOns) }
    if (connection) CytBottomSheet({ connection = false }) { DetailsContent(details, onDetails) }
    if (newProfile) CytBottomSheet({ newProfile = false }) {
        NewProfileContent(state.castTvs, onCreate = { name, tv, style ->
            onIntent(AppSettingsIntent.CreateProfile(name, tv?.id, tv?.name, style))
            newProfile = false
        })
    }
    state.confirm?.let { what ->
        CytBottomSheet({ onIntent(AppSettingsIntent.CancelConfirm) }) { ConfirmContent(what, state.working, onIntent) }
    }
}

// 0.2 Cast: screen casting belongs to Android, so the row opens the system's Cast settings
// (the phone's own Cast picker), not a screen of this app's.
private fun openCastSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
    }
}

// 0.2 Profiles: the subtitle of a profile row — its TV and its style, when it has them.
@Composable
private fun profileSubtitle(p: UserProfile): String? {
    val style = when (p.uiStyle) {
        UiStyle.CLASSIC -> stringResource(R.string.appsettings_interface_classic)
        UiStyle.LIQUID_GLASS -> stringResource(R.string.appsettings_interface_liquid)
        null -> null
    }
    return listOfNotNull(p.tvName, style).joinToString(" · ").takeIf { it.isNotEmpty() }
}

// 0.2 Profiles: the new-profile sheet — a name, an optional TV from the saved ones, an
// optional style. Creating switches to the new profile.
@Composable
private fun ColumnScope.NewProfileContent(castTvs: List<CastTv>, onCreate: (String, CastTv?, UiStyle?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var tvKey by rememberSaveable { mutableStateOf("") }
    var styleKey by rememberSaveable { mutableStateOf("") }
    SheetHeader(stringResource(R.string.appsettings_profile_new))
    CytTextField(value = name, onValueChange = { name = it.take(40) }, label = stringResource(R.string.appsettings_profile_name),
        supporting = stringResource(R.string.appsettings_profile_name_hint), modifier = Modifier.padding(top = 16.dp))
    SectionLabel(stringResource(R.string.appsettings_profile_tv))
    ChoiceList(
        options = listOf(ChoiceOption("", stringResource(R.string.appsettings_profile_no_tv), icon = null)) +
            castTvs.map { ChoiceOption(it.id.value, it.name, icon = null) },
        selectedKey = tvKey, onSelect = { tvKey = it.key }, modifier = Modifier.padding(top = 8.dp),
    )
    SectionLabel(stringResource(R.string.appsettings_profile_style))
    ChoiceList(
        options = listOf(
            ChoiceOption("", stringResource(R.string.appsettings_profile_keep_style), icon = null),
            ChoiceOption(UiStyle.CLASSIC.name, stringResource(R.string.appsettings_interface_classic), icon = null),
            ChoiceOption(UiStyle.LIQUID_GLASS.name, stringResource(R.string.appsettings_interface_liquid), icon = null),
        ),
        selectedKey = styleKey, onSelect = { styleKey = it.key }, modifier = Modifier.padding(top = 8.dp),
    )
    PrimaryButton(stringResource(R.string.appsettings_profile_create), {
        onCreate(name.trim(), castTvs.firstOrNull { it.id.value == tvKey }, styleKey.takeIf { it.isNotEmpty() }?.let { UiStyle.valueOf(it) })
    }, Modifier.padding(top = 20.dp, bottom = 24.dp), enabled = name.isNotBlank())
}

@Composable
internal fun ColumnScope.LicencesContent() {
    SheetHeader(stringResource(R.string.appsettings_licences))
    ListGroup(Modifier.padding(top = 16.dp)) {
        listOf(R.string.appsettings_licence_app, R.string.appsettings_licence_font, R.string.appsettings_licence_pictures, R.string.appsettings_licence_apache,
            R.string.appsettings_licence_bouncycastle)
            .forEachIndexed { i, id ->
                if (i > 0) ListDivider()
                ListRow(stringResource(id))
            }
    }
}

@Composable
internal fun ColumnScope.PrivacyContent(online: Boolean = true) {
    SheetHeader(stringResource(R.string.appsettings_privacy))
    // `online`: the build checks GitHub for updates or downloads add-ons from it (STORES-PLAN 1).
    Body(stringResource(if (online) R.string.appsettings_privacy_text else R.string.appsettings_privacy_text_offline), Modifier.padding(top = 8.dp),
        color = CytTheme.colors.textPrimary)
}

@Composable
internal fun ColumnScope.ConfirmContent(what: AppConfirm, working: Boolean, onIntent: (AppSettingsIntent) -> Unit) {
    val reset = what == AppConfirm.RESET_KEY
    SheetHeader(stringResource(if (reset) R.string.appsettings_reset_key_title else R.string.appsettings_delete_all_title))
    Body(stringResource(if (reset) R.string.appsettings_reset_key_note else R.string.appsettings_delete_all_note), Modifier.padding(top = 8.dp))
    ActionColumn(Modifier.padding(top = 20.dp)) {
        PrimaryButton(stringResource(if (reset) R.string.appsettings_reset_key_confirm else R.string.appsettings_delete_all_confirm),
            { onIntent(if (reset) AppSettingsIntent.ResetKey else AppSettingsIntent.DeleteEverything) }, enabled = !working)
        TertiaryButton(stringResource(R.string.action_cancel), { onIntent(AppSettingsIntent.CancelConfirm) }, quiet = true)
    }
}
