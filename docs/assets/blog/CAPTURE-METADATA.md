# Blog Capture Metadata

These captures are troubleshooting and profile-orientation evidence. They do not
establish answer quality, provider availability, or a benchmark result.

| Asset | Captured | UI state | Meaning |
|---|---|---|---|
| `01-local-quick-preflight.jpg` | 2026-08-27 | `local` / `QUICK` | Llama is installed, but the independent Granite chair is missing; the health gate blocks Send. |
| `05-multi-cloud-gemini-preflight.jpg` | 2026-08-27 | `multi-cloud` / `QUICK` | Gemini configuration is missing; the health gate blocks the multi-cloud policy before a cloud run. |
| `06-hybrid-openai-profile.jpg` | 2026-08-27 | `hybrid-openai` / `QUICK` | Local Llama drafting with an OpenAI chair. Credential presence is shown as unverified because the endpoint has not been probed. |

The application was served on loopback (`127.0.0.1:8080`) from a development
worktree whose base commit was `0bd8cfdc1717c3ef2d02a7c5d22c27abbc30d325` and
contained uncommitted documentation and profile changes. Re-capture the chosen
figures from the final published commit before making a release or publication
claim.
