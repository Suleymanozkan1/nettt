package com.bookmind.app.ui.quiz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.QuizAttemptEntity
import com.bookmind.app.data.repo.QuizRepository
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.ui.components.AppCard
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.Gutter
import com.bookmind.app.ui.components.HeroButton
import com.bookmind.app.ui.components.HeroCard
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.app.ui.components.ProgressRing
import com.bookmind.app.ui.theme.extended
import com.bookmind.core.quiz.QuizScorer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class QuizResultViewModel @Inject constructor(handle: SavedStateHandle, quizzes: QuizRepository) : ViewModel() {
    private val _state = MutableStateFlow<Pair<QuizAttemptEntity?, List<QuizRepository.ResultRow>>?>(null)
    val state = _state.asStateFlow()
    init { viewModelScope.launch { _state.value = quizzes.results(handle["attemptId"]!!) } }
}

@Composable
fun QuizResultScreen(attemptId: String, onBack: () -> Unit, onReview: () -> Unit, vm: QuizResultViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val x = MaterialTheme.extended
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.quiz_results), onBack)
        val (attempt, rows) = state ?: run { LoadingState(); return@Column }
        val answered = rows.count { it.answer != null }
        val correct = rows.count { it.answer?.correct == true }
        val score = (attempt?.score ?: 0.0).toFloat()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 32.dp)) {
            item {
                HeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        ProgressRing(score, size = 132.dp, stroke = 12.dp, color = Color.White, track = Color.White.copy(alpha = 0.22f)) {
                            Text("%${(score * 100).toInt()}", style = MaterialTheme.typography.displaySmall, color = Color.White)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(stringResource(if (score >= 0.8f) R.string.result_great else if (score >= 0.5f) R.string.result_good else R.string.result_keep_going),
                            style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.result_summary, correct, answered, rows.size), style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                        if (correct < answered) Text(stringResource(R.string.result_wrong_added), style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                        Spacer(Modifier.height(16.dp))
                        HeroButton(stringResource(R.string.home_start_reviews), onReview, icon = Icons.Filled.Style)
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            items(rows, key = { it.question.id }) { r ->
                val ok = r.answer?.correct == true
                AppCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel, stringResource(if (ok) R.string.correct else R.string.incorrect),
                            tint = if (ok) x.success else cs.error, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.question.prompt, style = MaterialTheme.typography.titleSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.your_answer) + ": " + (r.answer?.userAnswer ?: "—"), style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            if (!ok) r.draft?.let {
                                Text(stringResource(R.string.expected_answer) + ": " + QuizScorer.displayAnswer(it, AppLanguage.tag), style = MaterialTheme.typography.bodySmall,
                                    color = x.success, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
