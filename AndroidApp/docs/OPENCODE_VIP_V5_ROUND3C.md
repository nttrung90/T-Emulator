# Round3C: corrections proved by production call tests

User authorizes Gemini/OpenCode source fixes and Codex independent verification. Fix these before Round4; don't change audit assertions, don't deploy, don't bump versions.

1. `EntitlementRepository.ensureFreshEntitlement` still performs a second `isCachedVipValid` fallback after `refreshEntitlement` returns false. Remove this bypass. Typed refresh already decides the only allowable transport fallback. Missing backend config/token, denied/malformed response must remain denial. Return result only for the captured current session. Codex added `EntitlementRepositoryIntegrationAuditTest` invoking actual repositories and seeded valid lifetime cache: missing config currently assertion FAIL at line47. Make it pass, preserving test/fixtures. Run actual test, not merely copied policy helpers.

2. `VipMetadataDecoder` currently trusts JSON `verifiedAt` for local cache freshness. P09 in docs/audit-vip-2026-10-11-v5/PolicyAudit.kt FAIL: successful authoritative HTTP response for active lifetime has purchase verifiedAt five days old; should grant now. Cache freshness timestamp must local successful check `now`, receipt timestamp can be separate history. Run `run-policy-audit.ps1`: all20 cases must pass including P09.

3. Inspect every global `_isVipActive` write/timer revoke and session-scoped prefs mutation. `verifyAndAcknowledge` still saves inside auth.commitIfCurrent then computes cache/writes StateFlow outside guard; fallback/timer paths also check then write outside lock. Keep current-check + cache/flow commit under actual AuthSessionTracker same lock, no after-guard late flow writes. Targeted cache revoke A must not set currentB state false or cancel B timer. Use actual production tracker/repository tests; guard helper test alone isn't evidence consumers wired correctly.

Report source file changes and real test exit codes. Compile debug+release/testDebug offline using existing JBR17. Keep C01-C21/oldrestore tests. Scope billing/cloud/config to later rounds; don't claim solved here.

Codex added two more actual repository integration tests: writingOtherUsersMetadataMustNotGrantCurrentUser and revokingOtherUsersCacheMustPreserveCurrentGrant. Run and preserve all three assertions. Metadata for B may persist cacheB but must not grant flowA; revokeA must not cancel timerB.
