export function Logo() {
  return (
    <span className="logo">
      <svg viewBox="0 0 32 32" width="26" height="26" aria-hidden>
        <rect width="32" height="32" rx="8" fill="var(--accent)" />
        <path d="M9 16.5l4.5 4.5L23 11.5" fill="none" stroke="#fff" strokeWidth="3.2"
          strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <span>Fake<b>JIRA</b></span>
    </span>
  );
}
