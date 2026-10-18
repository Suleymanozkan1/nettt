package com.bookmind.app.ui.quiz

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bookmind.app.R
import com.bookmind.app.data.db.QuizAttemptEntity
import com.bookmind.app.data.repo.QuizRepository
import com.bookmind.app.ui.components.BackTopBar
import com.bookmind.app.ui.components.LoadingState
import com.bookmind.core.quiz.QuizScorer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QuizResultViewModel @Inject constructor(handle: SavedStateHandle, quizzes: QuizRepository) : ViewModel() {
    private val _state = MutableStateFlow<Pair<QuizAttemptEntity?, List<QuizRepository.ResultRow>>?>(null)
    val state = _state.asStateFlow()
    init { viewModelScope.launch { _state.value = quizzes.results(handle["attemptId"]!!) } }
}

@Composable
fun QuizResultScreen(attemptId: String, onBack: () -> Unit, onReview: () -> Unit, vm: QuizResultViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.quiz_results), onBack)
        val (attempt, rows) = state ?: run { LoadingState(); return@Column }
        val answered = rows.count { it.answer != null }
        val correct = rows.count { it.answer?.correct == true }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("%${((attempt?.score ?: 0.0) * 100).toInt()}", style = MaterialTheme.typography.displaySmall)
                        Text(stringResource(R.string.result_summary, correct, answered, rows.size))
                        if (correct < answered) Text(stringResource(R.string.result_wrong_added), style = MaterialTheme.typography.bodySmall)
                        Button(onReview, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.home_start_reviews)) }
                    }
                }
            }
            items(rows, key = { it.question.id }) { r ->
                val ok = r.answer?.correct == true
                ListItem(
                    leadingContent = { Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Cancel, stringResource(if (ok) R.string.correct else R.string.incorrect),
                        tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(r.question.prompt, maxLines = 3) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.your_answer) + ": " + (r.answer?.userAnswer ?: "—"))
                            r.draft?.let { Text(stringResource(R.string.expected_answer) + ": " + QuizScorer.displayAnswer(it), maxLines = 4) }
                            r.answer?.feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                )
            }
        }
    }
}
