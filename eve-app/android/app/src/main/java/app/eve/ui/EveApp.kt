package app.eve.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.eve.di.AppContainer
import app.eve.ui.activity.ActivityScreen
import app.eve.ui.activity.ActivityViewModel
import app.eve.ui.approvals.ApprovalsScreen
import app.eve.ui.approvals.ApprovalsViewModel
import app.eve.ui.memory.MemoryScreen
import app.eve.ui.memory.MemoryViewModel
import app.eve.ui.status.StatusScreen
import app.eve.ui.status.StatusViewModel
import app.eve.ui.talk.TalkScreen
import app.eve.ui.talk.TalkViewModel
import app.eve.ui.today.TodayScreen
import app.eve.ui.today.TodayViewModel
import app.eve.ui.theme.EveTheme

/** Bottom-nav destinations. Icons follow the Atlas design system (shield-check / mic / activity /
 *  brain / heart-pulse), mapped to their closest Material equivalents. */
enum class EveDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
) {
    Today("today", "Today", Icons.Filled.WbSunny),
    Approvals("approvals", "Approvals", Icons.Filled.VerifiedUser),
    Talk("talk", "Talk", Icons.Filled.Mic),
    Activity("activity", "Activity", Icons.Filled.Timeline),
    Memory("memory", "Memory", Icons.Filled.Psychology),
    Skills("skills", "Skills", Icons.Filled.Extension),
    Status("status", "Status", Icons.Filled.MonitorHeart),
}

@Composable
fun EveApp(
    container: AppContainer,
    openCardId: String? = null,
    onOpenCardConsumed: () -> Unit = {},
    autoStartRitual: Boolean = false,
    onReRunSetup: () -> Unit = {},
) {
    val navController = rememberNavController()
    val factory = EveViewModelFactory(container)

    // A notification Review deep-link can arrive while the app is already on another tab. Jump to
    // Approvals so the primed card is actually on screen (the priming itself happens below). The
    // host's pending id is reset only AFTER the card is primed (see the Approvals route effect), so
    // the prime can't lose the race against the reset, and the deep-link can't re-fire afterward.
    androidx.compose.runtime.LaunchedEffect(openCardId) {
        if (openCardId != null) {
            navController.navigate(EveDestination.Approvals.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Scaffold(
        containerColor = EveTheme.colors.surfaceCanvas,
        bottomBar = { EveBottomNav(navController) },
    ) { padding ->
        NavHost(
            navController = navController,
            // A morning-ritual launch opens straight onto Talk so Atlas can auto-connect and speak;
            // otherwise Today is home — the persistent morning ritual + checkable action items.
            startDestination = if (autoStartRitual) EveDestination.Talk.route else EveDestination.Today.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(EveDestination.Today.route) {
                val vm: TodayViewModel = viewModel(factory = factory)
                TodayScreen(viewModel = vm, modifier = Modifier.fillMaxSize())
            }
            composable(EveDestination.Approvals.route) {
                val vm: ApprovalsViewModel = viewModel(factory = factory)
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
                // If launched from a notification Review action, prime the target card open.
                // setExpanded(true) — never a blind toggle, which would collapse an open card.
                // Consume the id only after priming, so the host clears it without dropping the open.
                androidx.compose.runtime.LaunchedEffect(openCardId) {
                    openCardId?.let {
                        vm.setExpanded(it, true)
                        onOpenCardConsumed()
                    }
                }
                ApprovalsScreen(viewModel = vm, modifier = Modifier.fillMaxSize())
            }
            composable(EveDestination.Talk.route) {
                val vm: TalkViewModel = viewModel(factory = factory)
                TalkScreen(viewModel = vm, autoStart = autoStartRitual, modifier = Modifier.fillMaxSize())
            }
            composable(EveDestination.Activity.route) {
                val vm: ActivityViewModel = viewModel(factory = factory)
                ActivityScreen(viewModel = vm, modifier = Modifier.fillMaxSize())
            }
            composable(EveDestination.Memory.route) {
                val vm: MemoryViewModel = viewModel(factory = factory)
                MemoryScreen(viewModel = vm, modifier = Modifier.fillMaxSize())
            }
            composable(EveDestination.Skills.route) {
                val vm: app.eve.ui.skills.SkillsViewModel = viewModel(factory = factory)
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
                app.eve.ui.skills.SkillsScreen(viewModel = vm, modifier = Modifier.fillMaxSize())
            }
            composable(EveDestination.Status.route) {
                val vm: StatusViewModel = viewModel(factory = factory)
                StatusScreen(
                    viewModel = vm,
                    onReRunSetup = onReRunSetup,
                    // Built here (not in the VM) because the permission launcher needs the Android
                    // ActivityResultContract; null when Health Connect isn't available on this device.
                    healthRequest = container.healthPermissionRequest(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun EveBottomNav(navController: androidx.navigation.NavHostController) {
    val colors = EveTheme.colors
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken)
            .navigationBarsPadding()
            .height(EveTheme.layout.tabbarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EveDestination.entries.forEach { dest ->
            val selected = current?.hierarchy?.any { it.route == dest.route } == true
            val tint = when {
                !dest.enabled -> colors.textTertiary
                selected -> colors.accent
                else -> colors.textSecondary
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .then(
                        if (dest.enabled) {
                            Modifier.clickable {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = dest.icon,
                        contentDescription = null, // label below carries the name for a11y
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.size(EveTheme.spacing.s1))
                    Text(dest.label, style = EveTheme.type.caption.copy(color = tint), textAlign = TextAlign.Center)
                    if (!dest.enabled) {
                        Text("soon", style = EveTheme.type.micro.copy(color = colors.textTertiary))
                    }
                }
            }
        }
    }
}
