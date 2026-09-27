## Improvements

1) Smarter session tracking: ie if someone tries a login with azure id and soon after tries another, they will alr be logged in, which case we dont need to return a challenge code, and we dont need to return the thing about an exisiting push (which is sorta inaccurate), we can maybe just do a poll before we even start the login flow to see if the user is logged in alr or not, if yes we can just POST with existing cookies
2) performance enhancements ? pretty open ended - biggest bottleneck is probably nio when talking to the login services, not sure thugh, needs examining
3) User an opensource/free model instead of relying on Anthropic - OpenRouter Nvidia Nim?
4) Update the LLM system prompt, so that you can be less specific about a time. Ideally we want to just say what we did and it gets logged
5) Some kinda of automated calendar scraper, that will fetch details of lectures and labs from a uni calendar and prepare an activty log for everyone, that way people dont have to log uni events themselves. They can just log the extra stuff
6) Finish the multi-user rollout's test sweep (was step 08 of the deleted `steps-04-08-implementation-plan.md`; git history has the detail):
   - `MongoIndexesIT`: against a fresh Testcontainer, assert the unique index on `users.appUsername`, unique `sessions.tokenHash` plus the TTL on `sessions.expiresAt`, unique `inviteCodes.code`, and unique compound `llmQuota {userId, date}`.
   - `isolation.feature`: sign up users A and B; B must not be able to change or delete A's activity logs (404), and A's logs stay intact.
   - Make `*IT` classes run under `mvn test` (Surefire's default includes skip them), so the integration suite can't be skipped by accident.
