interface Props {
  message: string
}

export function LoadingScreen({ message }: Props) {
  return (
    <section className="card centred" aria-live="polite">
      <div className="spinner" aria-hidden="true" />
      <p className="muted">{message}</p>
    </section>
  )
}
