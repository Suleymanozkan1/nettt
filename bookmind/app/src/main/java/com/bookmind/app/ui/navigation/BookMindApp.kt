package com.bookmind.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bookmind.app.ui.theme.extended
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bookmind.app.AppState
import com.bookmind.app.R
import com.bookmind.app.ui.analytics.AnalyticsScreen
import com.bookmind.app.ui.ask.AskScreen
import com.bookmind.app.ui.book.AddBookScreen
import com.bookmind.app.ui.book.BookDetailScreen
import com.bookmind.app.ui.book.ChapterScreen
import com.bookmind.app.ui.book.LibraryScreen
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.concept.ConceptScreen
import com.bookmind.app.ui.connections.ConnectionsScreen
import com.bookmind.app.ui.home.HomeScreen
import com.bookmind.app.ui.notes.NoteEditScreen
import com.bookmind.app.ui.notes.NotesScreen
import com.bookmind.app.ui.onboarding.OnboardingScreen
import com.bookmind.app.ui.plan.PlanScreen
import com.bookmind.app.ui.quiz.QuizResultScreen
import com.bookmind.app.ui.quiz.QuizSessionScreen
import com.bookmind.app.ui.quiz.QuizSetupScreen
import com.bookmind.app.ui.review.ReviewScreen
import com.bookmind.app.ui.settings.NotificationSettingsScreen
import com.bookmind.app.ui.settings.SettingsScreen
import com.bookmind.app.ui.summary.SummaryScreen
import com.bookmind.app.ui.teach.TeachScreen

private data class Tab(val route: String, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.tab_today, Icons.Outlined.WbSunny, Icons.Filled.WbSunny),
    Tab(Routes.LIBRARY, R.string.tab_library, Icons.Outlined.LocalLibrary, Icons.Filled.LocalLibrary),
    Tab("review", R.string.tab_review, Icons.Outlined.Style, Icons.Filled.Style),
    Tab(Routes.ANALYTICS, R.string.tab_progress, Icons.Outlined.Insights, Icons.Filled.Insights),
    Tab(Routes.SETTINGS, R.string.tab_profile, Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
)

@Composable
fun BookMindApp(state: AppState, deepLink: String?, onDeepLinkHandled: () -> Unit) {
    if (state.loading) { LoadingState(); return }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBar = current != null && tabs.any { current == it.route || current.startsWith(it.route + "?") }

    LaunchedEffect(deepLink, state.onboardingDone) {
        if (deepLink != null && state.onboardingDone) {
            nav.navigate(if (deepLink == "review") Routes.review() else Routes.HOME) { launchSingleTop = true }
            onDeepLinkHandled()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) NavigationBar(containerColor = MaterialTheme.extended.card, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    val selected = current == tab.route || current?.startsWith(tab.route + "?") == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(if (tab.route == "review") Routes.review() else tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(if (selected) tab.selectedIcon else tab.icon, null) },
                        label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = if (state.onboardingDone) Routes.HOME else Routes.ONBOARDING, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            graph(nav)
        }
    }
}

private fun androidx.navigation.NavGraphBuilder.graph(nav: NavHostController) {
    val back: () -> Unit = { nav.popBackStack() }
    val str = { name: String -> navArgument(name) { type = NavType.StringType } }
    val optional = { name: String -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } }

    composable(Routes.ONBOARDING) {
        OnboardingScreen(onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
    }
    composable(Routes.HOME) {
        HomeScreen(
            onStartReview = { nav.navigate(Routes.review()) },
            onOpenBook = { nav.navigate(Routes.book(it)) },
            onAddBook = { nav.navigate(Routes.ADD_BOOK) },
            onOpenConcept = { nav.navigate(Routes.concept(it)) },
            onOpenQuizResult = { nav.navigate(Routes.quizResult(it)) },
            onOpenPlan = { nav.navigate(Routes.plan(it)) },
        )
    }
    composable(Routes.LIBRARY) {
        LibraryScreen(
            onOpenBook = { nav.navigate(Routes.book(it)) },
            onAddBook = { nav.navigate(Routes.ADD_BOOK) },
            onOpenNotes = { nav.navigate(Routes.notes()) },
            onOpenConnections = { nav.navigate(Routes.CONNECTIONS) },
        )
    }
    composable(Routes.ADD_BOOK) {
        AddBookScreen(onBack = back, onAdded = { id -> nav.navigate(Routes.book(id)) { popUpTo(Routes.ADD_BOOK) { inclusive = true } } })
    }
    composable(Routes.BOOK, listOf(str("bookId"))) { entry ->
        val bookId = entry.arguments!!.getString("bookId")!!
        BookDetailScreen(
            bookId = bookId,
            onBack = back,
            onOpenChapter = { nav.navigate(Routes.chapter(it)) },
            onOpenSummary = { type, chapterId -> nav.navigate(Routes.summary(bookId, type, chapterId)) },
            onOpenConcept = { nav.navigate(Routes.concept(it)) },
            onQuiz = { nav.navigate(Routes.quizSetup(bookId)) },
            onReview = { nav.navigate(Routes.review(bookId)) },
            onNotes = { nav.navigate(Routes.notes(bookId)) },
            onPlan = { nav.navigate(Routes.plan(bookId)) },
            onAsk = { nav.navigate(Routes.ask(bookId)) },
            onDeleted = back,
        )
    }
    composable(Routes.CHAPTER, listOf(str("chapterId"))) { entry ->
        ChapterScreen(
            chapterId = entry.arguments!!.getString("chapterId")!!,
            onBack = back,
            onOpenConcept = { nav.navigate(Routes.concept(it)) },
            onSummary = { bookId, chapterId -> nav.navigate(Routes.summary(bookId, "CHAPTER", chapterId)) },
            onAddNote = { bookId, chapterId, highlight -> nav.navigate(Routes.noteEdit(bookId, chapterId = chapterId, highlight = highlight)) },
        )
    }
    composable(Routes.SUMMARY, listOf(str("bookId"), str("type"), optional("chapterId"))) { entry ->
        val a = entry.arguments!!
        SummaryScreen(a.getString("bookId")!!, a.getString("type")!!, a.getString("chapterId"), onBack = back, onOpenChapter = { nav.navigate(Routes.chapter(it)) })
    }
    composable(Routes.CONCEPT, listOf(str("conceptId"))) { entry ->
        ConceptScreen(
            conceptId = entry.arguments!!.getString("conceptId")!!,
            onBack = back,
            onTeach = { bookId, conceptId -> nav.navigate(Routes.teach(bookId, conceptId)) },
            onOpenChapter = { nav.navigate(Routes.chapter(it)) },
        )
    }
    composable(Routes.TEACH, listOf(str("bookId"), str("conceptId"))) { entry ->
        TeachScreen(entry.arguments!!.getString("bookId")!!, entry.arguments!!.getString("conceptId")!!, onBack = back)
    }
    composable(Routes.QUIZ_SETUP, listOf(str("bookId"))) { entry ->
        QuizSetupScreen(entry.arguments!!.getString("bookId")!!, onBack = back, onCreated = { nav.navigate(Routes.quiz(it)) { popUpTo(Routes.QUIZ_SETUP) { inclusive = true } } })
    }
    composable(Routes.QUIZ, listOf(str("quizId"))) { entry ->
        QuizSessionScreen(entry.arguments!!.getString("quizId")!!, onBack = back, onFinished = { nav.navigate(Routes.quizResult(it)) { popUpTo(Routes.QUIZ) { inclusive = true } } })
    }
    composable(Routes.QUIZ_RESULT, listOf(str("attemptId"))) { entry ->
        QuizResultScreen(entry.arguments!!.getString("attemptId")!!, onBack = back, onReview = { nav.navigate(Routes.review()) })
    }
    composable(Routes.REVIEW, listOf(optional("bookId"))) { entry ->
        ReviewScreen(entry.arguments?.getString("bookId"), onBack = if (entry.arguments?.getString("bookId") != null) back else null)
    }
    composable(Routes.ANALYTICS) { AnalyticsScreen(onOpenConnections = { nav.navigate(Routes.CONNECTIONS) }) }
    composable(Routes.NOTES, listOf(optional("bookId"))) { entry ->
        val bookId = entry.arguments?.getString("bookId")
        NotesScreen(bookId, onBack = back, onEdit = { b, id -> nav.navigate(Routes.noteEdit(b, id)) })
    }
    composable(Routes.NOTE_EDIT, listOf(str("bookId"), optional("noteId"), optional("chapterId"), optional("highlight"))) { entry ->
        val a = entry.arguments!!
        NoteEditScreen(a.getString("bookId")!!, a.getString("noteId"), a.getString("chapterId"), a.getString("highlight"), onDone = back)
    }
    composable(Routes.CONNECTIONS) { ConnectionsScreen(onBack = back, onOpenConcept = { nav.navigate(Routes.concept(it)) }) }
    composable(Routes.PLAN, listOf(str("bookId"))) { entry -> PlanScreen(entry.arguments!!.getString("bookId")!!, onBack = back) }
    composable(Routes.ASK, listOf(str("bookId"))) { entry ->
        AskScreen(entry.arguments!!.getString("bookId")!!, onBack = back, onOpenChapter = { nav.navigate(Routes.chapter(it)) })
    }
    composable(Routes.NOTIFICATIONS) { NotificationSettingsScreen(onBack = back) }
    composable(Routes.SETTINGS) { SettingsScreen(onNotifications = { nav.navigate(Routes.NOTIFICATIONS) }) }
}
