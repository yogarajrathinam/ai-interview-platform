import { CompletedScreen } from './components/CompletedScreen'
import { ErrorPanel } from './components/ErrorPanel'
import { EvaluatingScreen } from './components/EvaluatingScreen'
import { LoadingScreen } from './components/LoadingScreen'
import { QuestionScreen } from './components/QuestionScreen'
import { ResultScreen } from './components/ResultScreen'
import { StartScreen } from './components/StartScreen'
import { useInterview } from './hooks/useInterview'

export interface AppProps {
  candidateUserId: string
  templateKey: string
}

/**
 * Renders whichever screen the current state names.
 *
 * An exhaustive switch over the state union, and nothing else — no conditions
 * combining flags, no screen that can appear in two states at once. When a new
 * state is added, TypeScript fails this switch until it is handled, which is the
 * practical benefit of modelling the state as a union rather than as booleans.
 */
export function App({ candidateUserId, templateKey }: AppProps) {
  const { state, start, submit, finish, retry } = useInterview({ candidateUserId, templateKey })

  return (
    <main className="app">
      {(() => {
        switch (state.kind) {
          case 'START':
            return <StartScreen title={state.templateTitle} onStart={start} />

          case 'LOADING':
            return <LoadingScreen message={state.message} />

          case 'QUESTION':
            return (
              <QuestionScreen
                interview={state.interview}
                submitting={false}
                onSubmit={submit}
                onFinish={finish}
              />
            )

          case 'SUBMITTING':
            // The same screen, locked. Swapping to a spinner here would throw
            // away the candidate's words if the submission failed.
            return (
              <QuestionScreen
                interview={state.interview}
                submitting
                onSubmit={submit}
                onFinish={finish}
              />
            )

          case 'EVALUATING':
            return <EvaluatingScreen />

          case 'COMPLETED':
            return <CompletedScreen />

          case 'RESULT':
            return <ResultScreen result={state.result} />

          case 'ERROR':
            return (
              <ErrorPanel error={state.error} recoverable={state.recoverable} onRetry={retry} />
            )
        }
      })()}
    </main>
  )
}
