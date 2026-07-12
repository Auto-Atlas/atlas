package app.eve.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.eve.di.AppContainer
import app.eve.ui.activity.ActivityViewModel
import app.eve.ui.approvals.ApprovalsViewModel
import app.eve.ui.memory.MemoryViewModel
import app.eve.ui.status.StatusViewModel
import app.eve.ui.talk.TalkViewModel
import app.eve.ui.today.TodayViewModel

/**
 * A single ViewModelProvider.Factory that wires each ViewModel from the manual [AppContainer].
 * Each ViewModel uses its own lifecycle-bound viewModelScope in production (the scope arg is
 * left null), while staying trivially testable by passing an explicit TestScope.
 */
class EveViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ApprovalsViewModel::class.java) ->
            ApprovalsViewModel(
                repo = container.approvalRepository,
                streamEvents = container.streamClient.events(),
            ) as T
        modelClass.isAssignableFrom(StatusViewModel::class.java) ->
            StatusViewModel(
                container.statusRepository,
                glasses = container.glassesToggle,
                health = container.healthController,
            ) as T
        modelClass.isAssignableFrom(ActivityViewModel::class.java) ->
            ActivityViewModel(container.activityRepository) as T
        modelClass.isAssignableFrom(TodayViewModel::class.java) ->
            TodayViewModel(container.todayRepository) as T
        modelClass.isAssignableFrom(MemoryViewModel::class.java) ->
            MemoryViewModel(container.memoryRepository) as T
        modelClass.isAssignableFrom(app.eve.ui.skills.SkillsViewModel::class.java) ->
            app.eve.ui.skills.SkillsViewModel(container.skillsRepository) as T
        modelClass.isAssignableFrom(TalkViewModel::class.java) ->
            TalkViewModel(container) as T
        modelClass.isAssignableFrom(app.eve.ui.onboarding.OnboardingViewModel::class.java) ->
            app.eve.ui.onboarding.OnboardingViewModel(
                api = container.apiClient,
                onboardingState = container.onboardingState,
                appContext = container.appContext,
            ) as T
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}
