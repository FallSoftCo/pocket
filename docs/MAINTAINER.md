# Pocket Maintainer operations

This optional Linux worker implements [the contribution policy](../MAINTAINER_POLICY.md). It polls GitHub every two minutes, reviews immutable PR snapshots with the owner's existing Codex login, and dispatches the trusted main-branch workflow. Public comments and reviews come from GitHub's built-in Actions App, `github-actions[bot]`. There is no hosted FallSoft review service, webhook endpoint, or API key stored in Actions.

## Decision and trust boundaries

- Automatic merging covers suitable implementation changes, including dependencies and sensitive code, after two independent approving reviews without findings. Both `backend` and `android` checks must pass. Small prose edits (at most 120 changed lines in README.md or docs/DEPLOYMENT.md without commands, links, directives, or sensitive terminology) can qualify with one low-risk review. The worker cannot approve changes to its own policy, authority, or CI.
- A scope rejection requires two fresh reviews agreeing on an explicit rule, each with quotes verified against added lines. The bot explains the decision and suggests a fork. Model judgments can be wrong; maintainers can override them.
- Missing files, binary changes, renames, more than 20 files, more than 600 changed lines, or more than 160 KB of context go to the owner. Suspected secrets are not quoted. This is a precaution, not a complete secret scanner.
- Review context includes the policy from the trusted base commit and changed files as text. PR descriptions and files are untrusted. PR comments are never fed back as instructions. Reopening a PR or changing its commit, base, title, body, or policy requires reconsideration.
- The reviewer runs inside Bubblewrap with a minimal filesystem and a copied Codex login. It has no GitHub/Pocket credential, shell, repository checkout, user configuration, plugin, or executable tool host. Execution, browsing, agents, and integrations are disabled. CLI tool declarations may still be advertised; execution fails closed, and non-text action events invalidate the result. Outbound networking remains available for Codex authentication/inference. This is not a general sandbox for running contributor code.
- The privileged Actions job checks out only trusted `main`. It recomputes the policy decision and snapshot digest, checks for holds and changed revisions, and merges with GitHub's expected-head SHA. GitHub branch protection supplies the final required-check and stale-review gate. Closing uses a fresh snapshot check, but GitHub's close API has no atomic expected-head parameter.
- The controller has GitHub write/dispatch access. Keep its code, config, cache, and login private and owner-writable only. Do not run it from an untrusted development checkout. GitHub Actions logs contain the public decision, not the reviewer's login. Review content is sent to the configured OpenAI model under the owner's account.

## Installation

Requirements: Linux user namespaces, `bwrap`, Node 22.13+, `gh auth login` with repository/workflow administration, and authenticated **Codex CLI 0.157.1**. The isolation adapter rejects other CLI versions until revalidated. The default model is `gpt-6-astra`; set `reviewer.model` to an available model if necessary. Review usage counts against the owner's Codex account. The default cap is 20 inference calls per UTC day, including corroborating reviews and failed attempts.

1. Install this code from a trusted revision into a private directory outside contributor worktrees. Copy `maintainer/` there; no npm dependencies are needed.
2. Create private configuration using [the example](../maintainer/config.example.json). Expand all paths to absolute paths. Review and runtime-error notifications default to off (`notifyReviews` and `notifyErrors`). `pocket` is optional; its thread must be an existing owner-controlled Codex conversation. If enabled, escalations include the PR link and reviewed commit. Respond in GitHub, or ask your trusted Codex session to handle the linked PR; the worker does not interpret phone replies as merge authorization.
3. Publish `maintainer/`, MAINTAINER_POLICY.md, and `.github/workflows/maintainer.yml` on the repository's default branch. This initial distribution targets `FallSoftCo/pocket`; a fork must deliberately update the repository identity in the adapter, workflow guard, and policy.
4. Configure main-branch protection: require successful `backend` and `android` checks, require branches up to date, require one approving review, and dismiss stale approvals. Do not give the Actions bot a bypass. Enable **Allow GitHub Actions to create and approve pull requests** under Actions settings; the default workflow token can remain read-only.
5. Create labels `maintainer:hold`, `maintainer:owner`, `maintainer:changes`, `maintainer:out-of-scope`, and `maintainer:ready`.
6. Adapt the [service](../deploy/pocket-maintainer.service.example) and [timer](../deploy/pocket-maintainer.timer.example) into your user systemd directory. Run a cycle with `dryRun: true`, inspect the result, then set it to false and enable the timer.

The controller obtains its GitHub credential using `gh auth token` (or `GH_TOKEN`). Never place credentials in the example config or repository. The review subprocess receives only its separate Codex login. Use a fine-grained GitHub token limited to this repository when provisioning a separate worker account.

## Control and troubleshooting

```bash
systemctl --user status pocket-maintainer.timer
journalctl --user -u pocket-maintainer.service -n 50
systemctl --user stop pocket-maintainer.timer pocket-maintainer.service
```

Apply `maintainer:hold` before editing or manually deciding a PR to pause pending automatic actions. Disabling the GitHub `Pocket Maintainer` workflow also prevents queued dispatches from acting. Stopping the local timer alone does not cancel an already-running Actions job. To resume, remove the hold or start the timer again.

Offline workstations pause polling. Failed or unavailable reviews do not approve anything; optional error alerts are bounded. A dispatch is retried after ten minutes if no bot receipt appears; feedback is idempotent for the reviewed snapshot. Merges awaiting checks are reconsidered on subsequent polls. GitHub history above 1,000 records fails closed. Only the ten most recently updated open PRs are handled per cycle; sustained high-volume use needs pagination and a proper work queue.

Keep state outside the checkout. Never delete it casually: it records inference budgets and notification/dispatch receipts. If Codex authentication expires, authenticate again on the workstation. After changing the policy, worker, model, or CLI version, run `npm test`, validate isolation and a real review, then deploy the trusted code copy. This is deliberately a narrow initial maintainer, not an autonomous security auditor.
