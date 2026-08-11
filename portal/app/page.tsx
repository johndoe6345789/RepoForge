import type { CSSProperties } from "react";

const features = [
  "GitHub + GitLab support",
  "Repository browsing",
  "Issue management",
  "Pull request reviews",
  "Dark theme",
];

const styles = {
  container: {
    maxWidth: 640,
    margin: "0 auto",
    padding: "80px 24px",
    minHeight: "100vh",
  } satisfies CSSProperties,
  icon: {
    width: 96,
    height: 96,
    borderRadius: 24,
    background: "linear-gradient(135deg, #3b82f6, #8b5cf6)",
    margin: "0 auto 32px",
  } satisfies CSSProperties,
  title: {
    fontSize: 40,
    fontWeight: 700,
    textAlign: "center",
    marginBottom: 16,
  } satisfies CSSProperties,
  description: {
    fontSize: 18,
    lineHeight: 1.6,
    color: "var(--text-muted)",
    textAlign: "center",
    marginBottom: 40,
  } satisfies CSSProperties,
  section: {
    background: "var(--bg-secondary)",
    border: "1px solid var(--border)",
    borderRadius: "var(--radius)",
    padding: 24,
    marginBottom: 24,
  } satisfies CSSProperties,
  sectionTitle: {
    fontSize: 18,
    fontWeight: 600,
    marginBottom: 16,
  } satisfies CSSProperties,
  featureList: {
    listStyle: "none",
    display: "flex",
    flexDirection: "column",
    gap: 10,
  } satisfies CSSProperties,
  featureItem: {
    fontSize: 15,
    color: "var(--text-muted)",
    paddingLeft: 20,
    position: "relative",
  } satisfies CSSProperties,
  downloadButton: {
    display: "block",
    width: "100%",
    padding: "16px 24px",
    fontSize: 18,
    fontWeight: 600,
    color: "#fff",
    background: "var(--accent)",
    border: "none",
    borderRadius: "var(--radius)",
    cursor: "pointer",
    textAlign: "center",
    textDecoration: "none",
    marginBottom: 24,
  } satisfies CSSProperties,
  code: {
    display: "block",
    background: "var(--bg)",
    border: "1px solid var(--border)",
    borderRadius: 8,
    padding: 16,
    fontSize: 14,
    fontFamily: "monospace",
    color: "var(--text-muted)",
    overflowX: "auto",
    whiteSpace: "pre",
    lineHeight: 1.6,
  } satisfies CSSProperties,
};

export default function Home() {
  return (
    <main style={styles.container}>
      <div style={styles.icon} />
      <h1 style={styles.title}>RepoForge</h1>
      <p style={styles.description}>
        Mobile Git repository manager for GitHub and GitLab. Browse repos,
        manage issues, review pull requests, and more &mdash; all from your
        phone.
      </p>

      <a
        href="https://github.com/nicholasgriffintn/repoforge/releases/latest"
        style={styles.downloadButton}
        target="_blank"
        rel="noopener noreferrer"
      >
        Download APK
      </a>

      <section style={styles.section}>
        <h2 style={styles.sectionTitle}>Features</h2>
        <ul style={styles.featureList}>
          {features.map((feature) => (
            <li key={feature} style={styles.featureItem}>
              &bull;&ensp;{feature}
            </li>
          ))}
        </ul>
      </section>

      <section style={styles.section}>
        <h2 style={styles.sectionTitle}>Build from source</h2>
        <code style={styles.code}>
          {`git clone https://github.com/nicholasgriffintn/repoforge.git
cd repoforge
./gradlew assembleDebug`}
        </code>
      </section>
    </main>
  );
}
